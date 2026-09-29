package os.aiworkforce.orchestrator.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import os.aiworkforce.orchestrator.domain.ChatMessage;

/**
 * Builds the earlier-turns preamble an agent needs when a chat message is really a follow-up to
 * what came before it - without this, an agent addressed by a short reply such as "shorter" or
 * "now send it" sees only that one line and nothing it refers to.
 */
final class ThreadContext {

    private ThreadContext() {}

    /** At most this many earlier turns are kept, newest first before they are reversed for display. */
    private static final int MAX_TURNS = 6;

    /** Each message is cut to this many characters, at a word boundary. */
    private static final int MAX_MESSAGE_CHARS = 600;

    /** The whole block is capped at this many characters, with the oldest turn dropped first. */
    private static final int MAX_BLOCK_CHARS = 3_000;

    private static final String HEADER =
            "Earlier in this conversation, for context (the request itself comes after this):\n";
    private static final String FOOTER = "\nRequest:\n";

    /**
     * The preamble to prepend to a new instruction, or blank when there is nothing to add.
     *
     * @param earlierChronological earlier messages in this conversation, oldest first
     * @param agentNames agent id to display name, for the turns an agent answered
     */
    static String preamble(List<ChatMessage> earlierChronological, Map<UUID, String> agentNames) {
        if (earlierChronological == null || earlierChronological.isEmpty()) {
            return "";
        }
        Deque<String> lines = new ArrayDeque<>();
        int kept = 0;
        for (int i = earlierChronological.size() - 1; i >= 0 && kept < MAX_TURNS; i--) {
            ChatMessage message = earlierChronological.get(i);
            String line = lineFor(message, agentNames);
            if (line == null) {
                continue;
            }
            lines.addFirst(line);
            kept++;
        }
        if (lines.isEmpty()) {
            return "";
        }

        StringBuilder block = new StringBuilder(HEADER);
        // Drop the oldest turn first when the block would otherwise exceed the cap.
        while (!lines.isEmpty()) {
            StringBuilder attempt = new StringBuilder(HEADER);
            for (String line : lines) {
                attempt.append(line).append('\n');
            }
            if (attempt.length() <= MAX_BLOCK_CHARS || lines.size() == 1) {
                block = attempt;
                break;
            }
            lines.removeFirst();
        }
        return block.append(FOOTER).toString();
    }

    /** {@code null} for anything that is not a plain user message or an agent's answer. */
    private static String lineFor(ChatMessage message, Map<UUID, String> agentNames) {
        String content = message.getContent() == null ? "" : message.getContent();
        if ("user".equals(message.getAuthorKind()) && "text".equals(message.getKind())) {
            return "Person: " + cut(content);
        }
        if ("answer".equals(message.getKind())) {
            String name = agentNames == null ? null : agentNames.get(message.getAgentId());
            return (name == null ? "The agent" : name) + ": " + cut(content);
        }
        return null;
    }

    private static String cut(String text) {
        return CoordinatorService.truncateAtWord(text, MAX_MESSAGE_CHARS);
    }
}
