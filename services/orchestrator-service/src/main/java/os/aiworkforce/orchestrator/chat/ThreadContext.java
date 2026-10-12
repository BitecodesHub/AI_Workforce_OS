// @find: thread context, earlier turns, follow-up message, last reply, conversation history for agent, shorter, now send it, ThreadContext, history preamble
// @what: Builds the earlier-turns preamble that lets an agent understand a short follow-up message.
// @flow: Called by CoordinatorService when starting work for a message.
package os.aiworkforce.orchestrator.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.service.GoalService;

/**
 * Builds the earlier-turns preamble an agent needs when a chat message is really a follow-up to
 * what came before it - without this, an agent addressed by a short reply such as "shorter" or
 * "now send it" sees only that one line and nothing it refers to.
 *
 * <p>The most recent agent answer is the one a follow-up almost always means, so it is given in a
 * block of its own, exactly as it was written - line breaks, lists and tables kept - and up to
 * {@value #MAX_LAST_REPLY_CHARS} characters, with a line saying how much was left out when it is
 * longer. Every other turn is one line of at most {@value #MAX_MESSAGE_CHARS} characters. Only the
 * older turns may be dropped to make room, oldest first; the header, that last reply and the
 * closing "Request:" line are always kept.
 */
final class ThreadContext {

    /** Not instantiable; every member is static. */
    private ThreadContext() {}

    /** At most this many earlier turns are kept, newest first before they are reversed for display. */
    private static final int MAX_TURNS = 6;

    /** Each older message is cut to this many characters, at a word boundary, on one line. */
    private static final int MAX_MESSAGE_CHARS = 600;

    /** The older turns together are capped at this many characters, the oldest dropped first. */
    private static final int MAX_OLDER_CHARS = 3_000;

    /** The last answer is kept verbatim up to this many characters. */
    static final int MAX_LAST_REPLY_CHARS = 6_000;

    /** The line that opens the preamble. */
    static final String HEADER = "Earlier in this conversation, for context (the request itself comes after this):\n";
    /** The line that closes the preamble and introduces the request itself. */
    static final String FOOTER = "\nRequest:\n";
    /** The label of the most recent answer when it was written by the agent now being asked. */
    static final String LAST_REPLY_LABEL = "Your last reply (verbatim):\n";

    /** What a user turn is labelled when its author is the person this request is for. */
    static final String REQUESTER = "The requester";

    /** What a user turn is labelled when someone else in the conversation wrote it. */
    static final String SOMEONE_ELSE = "Another person";

    /**
     * One earlier turn, already in the words the agent reads.
     *
     * One earlier turn, already in the words the agent reads.
     *
     * @param agentId the agent that wrote it, for an answer
     * @param lastReply whether this is the most recent answer, which is never dropped
     */
    record Turn(String text, UUID agentId, String agentName, boolean lastReply, boolean earlierAnswer) {

        /** A turn that is not an earlier answer a card was used on. */
        Turn(String text, UUID agentId, String agentName, boolean lastReply) {
            this(text, agentId, agentName, lastReply, false);
        }
    }

    /** The label of the answer a card was used on, when newer answers came after it and it was written by the agent now being asked. */
    static final String EARLIER_REPLY_LABEL = "Your earlier reply this request is about (verbatim):\n";

    /**
     * The earlier turns of a conversation, oldest first.
     *
     * <p>Built by {@link #of}; {@link #render} turns it into the text an agent is given.
     */
    record History(List<Turn> turns) {

        /** A history with no turns; renders as an empty string. */
        static final History EMPTY = new History(List.of());

        /** Whether there are no turns. */
        boolean isEmpty() {
            return turns.isEmpty();
        }

        /**
         * How many turns may be dropped to make room, oldest first: every one but the last reply
         * - or, when there is none, every one but the newest - so that a thread that has anything
         * in it always keeps its header, something under it and its closing "Request:" line.
         */
        int droppable() {
            boolean hasLastReply = turns.stream().anyMatch(Turn::lastReply);
            long others = turns.stream().filter(turn -> !turn.lastReply()).count();
            return (int) (hasLastReply ? others : Math.max(0, others - 1));
        }

        /**
         * The preamble as the given agent reads it, with nothing dropped.
         *
         * <p>Called by {@code CoordinatorService}; pure.
         */
        String render(UUID forAgentId) {
            return render(forAgentId, 0);
        }

        /**
         * The preamble as the given agent reads it, with the oldest {@code dropOldest} droppable
         * turns left out; blank when no turn is left.
         *
         * @param forAgentId the agent the request goes to: its own last answer is "Your last
         *     reply", anyone else's is named
         */
        String render(UUID forAgentId, int dropOldest) {
            List<Turn> kept = new ArrayList<>();
            int dropped = 0;
            for (Turn turn : turns) {
                if (!turn.lastReply() && dropped < dropOldest) {
                    dropped++;
                    continue;
                }
                kept.add(turn);
            }
            if (kept.isEmpty()) {
                return "";
            }
            StringBuilder block = new StringBuilder(HEADER);
            for (int index = 0; index < kept.size(); index++) {
                Turn turn = kept.get(index);
                if (!turn.lastReply()) {
                    block.append(turn.text()).append('\n');
                    continue;
                }
                // A block of its own: a blank line either side, so the agent can see where it starts
                // and ends however many lines it runs to.
                if (index > 0) {
                    block.append('\n');
                }
                block.append(lastReplyLabel(turn, forAgentId)).append(turn.text()).append('\n');
                if (index < kept.size() - 1) {
                    block.append('\n');
                }
            }
            return block.append(FOOTER).toString();
        }

        /**
         * The label placed before the last reply: second person for the agent that wrote it, otherwise the writer's name.
         */
        private static String lastReplyLabel(Turn turn, UUID forAgentId) {
            if (forAgentId != null && forAgentId.equals(turn.agentId())) {
                return turn.earlierAnswer() ? EARLIER_REPLY_LABEL : LAST_REPLY_LABEL;
            }
            String name = turn.agentName() == null ? "The agent's" : turn.agentName() + "'s";
            return name + (turn.earlierAnswer() ? " earlier reply this request is about" : " last reply") + " (verbatim):\n";
        }
    }

    /**
     * The earlier turns worth giving an agent with a new request: people's own messages and agents'
     * answers, nothing else.
     *
     * <p>Called by {@code CoordinatorService} when starting work for a message. The newest answer
     * with content becomes the last reply; up to {@link #MAX_TURNS} turns in all are kept, older
     * ones within {@link #MAX_OLDER_CHARS} characters, and anything that is not a user text message
     * or an answer is ignored. Pure; the caller supplies the messages and the names.
     *
     * @param earlierChronological earlier messages in this conversation, oldest first
     * @param agentNames agent id to display name, for the turns an agent answered
     * @param personNames person id to display name, for the people who wrote, where known
     * @param requesterId the person the new request is for; their turns read "The requester"
     */
    static History of(
            List<ChatMessage> earlierChronological,
            Map<UUID, String> agentNames,
            Map<UUID, String> personNames,
            UUID requesterId) {
        return of(earlierChronological, agentNames, personNames, requesterId, null);
    }

    // @find: thread context anchored on a card's answer, reply a card belongs to
    /**
     * As {@link #of(List, Map, Map, UUID)}, for a request about one particular answer: a message sent
     * from a card in it. That answer, when it is among the earlier turns, is the one given verbatim,
     * however many answers came after it, so a choice made on an older answer is read against the
     * question it answers and not against whatever the newest answer asked.
     *
     * @param aboutAnswerId the answer the request is about, or null for the newest answer
     */
    static History of(
            List<ChatMessage> earlierChronological,
            Map<UUID, String> agentNames,
            Map<UUID, String> personNames,
            UUID requesterId,
            UUID aboutAnswerId) {
        if (earlierChronological == null || earlierChronological.isEmpty()) {
            return History.EMPTY;
        }
        // The newest answer that says anything: the one a follow-up such as "now send it" means -
        // unless the request names the answer it is about.
        int lastAnswer = -1;
        boolean earlierAnswer = false;
        for (int i = earlierChronological.size() - 1; i >= 0; i--) {
            ChatMessage message = earlierChronological.get(i);
            if ("answer".equals(message.getKind()) && message.getContent() != null && !message.getContent().isBlank()) {
                if (aboutAnswerId == null || aboutAnswerId.equals(message.getId())) {
                    earlierAnswer = lastAnswer >= 0;
                    lastAnswer = i;
                    break;
                }
                if (lastAnswer < 0) {
                    lastAnswer = i;
                }
            }
        }

        // Newest first, so the turn cap and the size cap both keep the most recent turns. The last
        // reply has a place of its own, however many messages came after it.
        record Kept(int index, Turn turn) {}
        List<Kept> kept = new ArrayList<>();
        int olderAllowed = lastAnswer < 0 ? MAX_TURNS : MAX_TURNS - 1;
        int olderChars = 0;
        int older = 0;
        for (int i = earlierChronological.size() - 1; i >= 0 && older < olderAllowed; i--) {
            if (i == lastAnswer) {
                continue;
            }
            Turn turn = lineFor(earlierChronological.get(i), agentNames, personNames, requesterId);
            if (turn == null) {
                continue;
            }
            if (older > 0 && olderChars + turn.text().length() + 1 > MAX_OLDER_CHARS) {
                break; // no room for this or anything older
            }
            olderChars += turn.text().length() + 1;
            older++;
            kept.add(new Kept(i, turn));
        }
        if (lastAnswer >= 0) {
            kept.add(new Kept(lastAnswer, lastReplyTurn(earlierChronological.get(lastAnswer), agentNames, earlierAnswer)));
        }
        kept.sort(Comparator.comparingInt(Kept::index));
        return new History(kept.stream().map(Kept::turn).toList());
    }

    /**
     * The most recent answer (or the earlier one a card was used on, {@code earlierAnswer}) as a
     * turn, kept verbatim and cut only at {@link #MAX_LAST_REPLY_CHARS}, with a marker saying how
     * much was left out.
     */
    private static Turn lastReplyTurn(ChatMessage message, Map<UUID, String> agentNames, boolean earlierAnswer) {
        String content = message.getContent() == null ? "" : message.getContent().strip();
        return new Turn(
                GoalService.cutWithMarker(content, MAX_LAST_REPLY_CHARS),
                message.getAgentId(),
                agentName(agentNames, message.getAgentId()),
                true,
                earlierAnswer);
    }

    /** {@code null} for anything that is not a plain user message or an agent's answer, or says nothing. */
    private static Turn lineFor(
            ChatMessage message, Map<UUID, String> agentNames, Map<UUID, String> personNames, UUID requesterId) {
        String content = message.getContent() == null ? "" : message.getContent();
        if (content.isBlank()) {
            return null;
        }
        if ("user".equals(message.getAuthorKind()) && "text".equals(message.getKind())) {
            String who = personLabel(message.getAuthorId(), personNames, requesterId);
            return new Turn(who + ": " + cut(content), null, null, false);
        }
        if ("answer".equals(message.getKind())) {
            String name = agentName(agentNames, message.getAgentId());
            return new Turn(
                    (name == null ? "The agent" : name) + ": " + cut(content), message.getAgentId(), name, false);
        }
        return null;
    }

    /** The agent's display name, or null when it is not known. */
    private static String agentName(Map<UUID, String> agentNames, UUID agentId) {
        return agentNames == null || agentId == null ? null : agentNames.get(agentId);
    }

    /**
     * Who wrote a person's turn: their name when it is known, otherwise whether it was the person
     * this request is for or someone else - so an agent never takes a colleague's words in a shared
     * thread as the requester's own.
     */
    private static String personLabel(UUID authorId, Map<UUID, String> personNames, UUID requesterId) {
        String name = authorId == null || personNames == null ? null : personNames.get(authorId);
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        return authorId != null && Objects.equals(authorId, requesterId) ? REQUESTER : SOMEONE_ELSE;
    }

    /** A message cut at a word to {@link #MAX_MESSAGE_CHARS}. */
    private static String cut(String text) {
        return CoordinatorService.truncateAtWord(text, MAX_MESSAGE_CHARS);
    }
}
