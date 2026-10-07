package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.ChatMessage;

class ThreadContextTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID OTHER_AGENT = UUID.randomUUID();
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID COLLEAGUE = UUID.randomUUID();

    private static ChatMessage userText(int position, String content) {
        return ChatMessage.of(ORG, CONVERSATION, position, "user", REQUESTER, null, "text", content, Map.of(), null);
    }

    private static ChatMessage answer(int position, String content) {
        return ChatMessage.of(ORG, CONVERSATION, position, "agent", null, AGENT, "answer", content, Map.of(), null);
    }

    private static ChatMessage card(int position, String kind) {
        return ChatMessage.of(ORG, CONVERSATION, position, "coordinator", null, null, kind, "ignored", Map.of(), null);
    }

    private static ThreadContext.History history(List<ChatMessage> turns) {
        return ThreadContext.of(turns, Map.of(AGENT, "Research"), Map.of(), REQUESTER);
    }

    private static String render(List<ChatMessage> turns) {
        return history(turns).render(AGENT);
    }

    @Test
    @DisplayName("no earlier turns produces an empty thread and an empty preamble")
    void emptyWhenNoTurns() {
        assertThat(ThreadContext.of(List.of(), Map.of(), Map.of(), REQUESTER).isEmpty())
                .isTrue();
        assertThat(ThreadContext.of(List.of(), Map.of(), Map.of(), REQUESTER).render(AGENT))
                .isEmpty();
        assertThat(ThreadContext.of(null, Map.of(), Map.of(), REQUESTER).isEmpty())
                .isTrue();
    }

    @Test
    @DisplayName("only user text and agent answers are kept - routing and other cards are skipped")
    void skipsCards() {
        List<ChatMessage> turns =
                List.of(userText(0, "please help"), card(1, "routing"), card(2, "progress"), answer(3, "here you go"));

        String preamble = render(turns);

        assertThat(preamble).contains("The requester: please help");
        assertThat(preamble).contains("Your last reply (verbatim):\nhere you go");
        assertThat(preamble).doesNotContain("ignored");
    }

    @Test
    @DisplayName("a message that says nothing is not a turn")
    void skipsBlankMessages() {
        List<ChatMessage> turns = List.of(userText(0, "   "), answer(1, ""), userText(2, "real"));

        String preamble = render(turns);

        assertThat(preamble).contains("The requester: real");
        assertThat(preamble).doesNotContain("last reply");
        assertThat(history(turns).turns()).hasSize(1);
    }

    @Test
    @DisplayName("the most recent answer is a block of its own, with its line breaks, list and table kept")
    void lastAnswerKeepsItsShape() {
        String reply = "Subject: Welcome\n\nHi Priya,\n\n- Day one: laptop\n- Day two: payroll\n\n| Item | Owner |\n|---|---|\n| Desk | IT |\n\nThanks";
        List<ChatMessage> turns = List.of(userText(0, "Draft a welcome email"), answer(1, reply));

        String preamble = render(turns);

        assertThat(preamble).contains("Your last reply (verbatim):\n" + reply + "\n");
        assertThat(preamble).startsWith(ThreadContext.HEADER);
        assertThat(preamble).endsWith("\nRequest:\n");
    }

    @Test
    @DisplayName("the last answer is cut at 6,000 characters with a marker saying how much was left out")
    void lastAnswerIsCappedWithAMarker() {
        String reply = ("Paragraph of the long draft.\n\n").repeat(400); // about 11,600 characters
        List<ChatMessage> turns = List.of(userText(0, "Write the report"), answer(1, reply));

        ThreadContext.History history = history(turns);
        String shown = history.turns().getLast().text();

        assertThat(shown.length()).isLessThanOrEqualTo(ThreadContext.MAX_LAST_REPLY_CHARS + 80);
        assertThat(shown).contains("Paragraph of the long draft.\n\nParagraph of the long draft.");
        assertThat(shown).matches("(?s).*\\n\\[\\.\\.\\. \\d+ more characters not shown]$");
        int omitted = Integer.parseInt(shown.replaceAll("(?s).*\\[\\.\\.\\. (\\d+) more characters not shown]$", "$1"));
        String head = shown.substring(0, shown.lastIndexOf("\n[... "));
        assertThat(head.length() + omitted).isEqualTo(reply.strip().length());
    }

    @Test
    @DisplayName("a reply that fits is never marked as cut")
    void shortReplyHasNoMarker() {
        String reply = "x ".repeat(2_000).strip(); // 3,999 characters
        assertThat(render(List.of(userText(0, "go"), answer(1, reply)))).doesNotContain("more characters not shown");
    }

    @Test
    @DisplayName("older turns are one line of at most 600 characters, at a word boundary")
    void olderTurnsAreCapped() {
        String longMessage = ("word\n").repeat(200).strip();
        List<ChatMessage> turns = List.of(userText(0, longMessage), answer(1, "latest"), userText(2, "now"));

        String preamble = render(turns);

        String line = preamble.lines()
                .filter(l -> l.startsWith("The requester: word"))
                .findFirst()
                .orElseThrow();
        assertThat(line.substring("The requester: ".length())).hasSizeLessThanOrEqualTo(600);
        assertThat(line).doesNotEndWith(" ");
        assertThat(line).doesNotContain("\n");
    }

    @Test
    @DisplayName("an older answer is one flattened line too; only the newest one is a block of its own")
    void olderAnswersAreLines() {
        List<ChatMessage> turns = List.of(answer(0, "first\n\nanswer"), userText(1, "again"), answer(2, "second"));

        String preamble = render(turns);

        assertThat(preamble).contains("Research: first answer\n");
        assertThat(preamble).contains("Your last reply (verbatim):\nsecond\n");
    }

    @Test
    @DisplayName("at most six turns are kept, the last reply among them")
    void keepsSixTurns() {
        List<ChatMessage> turns = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            turns.add(userText(i, "turn number " + i));
        }
        String preamble = render(turns);
        assertThat(preamble).doesNotContain("turn number 3");
        for (int i = 4; i < 10; i++) {
            assertThat(preamble).contains("turn number " + i);
        }

        turns.add(4, answer(4, "the answer"));
        String withAnswer = render(turns);
        assertThat(history(turns).turns()).hasSize(6);
        assertThat(withAnswer).contains("the answer");
    }

    @Test
    @DisplayName("the last reply is kept however many messages came after it")
    void lastReplySurvivesLaterMessages() {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(answer(0, "the reply that matters"));
        for (int i = 1; i <= 9; i++) {
            turns.add(userText(i, "later message " + i));
        }

        String preamble = render(turns);

        assertThat(preamble).contains("Your last reply (verbatim):\nthe reply that matters");
        assertThat(preamble).contains("later message 9").doesNotContain("later message 1\n");
        assertThat(history(turns).turns()).hasSize(6);
        assertThat(history(turns).turns().getFirst().lastReply()).isTrue();
    }

    @Test
    @DisplayName("the older turns together stay within 3,000 characters, the oldest dropped first")
    void olderTurnsAreCappedTogether() {
        List<ChatMessage> turns = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            turns.add(userText(i, ("turn-" + i + " ") + "filler ".repeat(120)));
        }

        String preamble = render(turns);

        assertThat(preamble.length()).isLessThanOrEqualTo(3_000 + ThreadContext.HEADER.length() + 200);
        assertThat(preamble).doesNotContain("turn-0 ");
        assertThat(preamble).contains("turn-5 ");
    }

    @Test
    @DisplayName("trimming drops the oldest turns first and never the header, the last reply or the Request line")
    void headerSurvivesTrimming() {
        List<ChatMessage> turns = List.of(
                userText(0, "oldest request"), answer(1, "oldest answer"), userText(2, "newer request"),
                answer(3, "the reply to keep"), userText(4, "newest request"));
        ThreadContext.History history = history(turns);

        String whole = history.render(AGENT);
        String oneDropped = history.render(AGENT, 1);
        String allDropped = history.render(AGENT, history.droppable());

        assertThat(whole).contains("oldest request").contains("oldest answer");
        assertThat(oneDropped).doesNotContain("oldest request").contains("oldest answer");
        for (String trimmed : List.of(oneDropped, allDropped)) {
            assertThat(trimmed).startsWith(ThreadContext.HEADER);
            assertThat(trimmed).contains("Your last reply (verbatim):\nthe reply to keep");
            assertThat(trimmed).endsWith("\nRequest:\n");
        }
        assertThat(allDropped).doesNotContain("oldest").doesNotContain("newer request").doesNotContain("newest request");
        assertThat(history.droppable()).isEqualTo(4);
    }

    @Test
    @DisplayName("a thread with no answer in it keeps its newest turn however far it is trimmed")
    void newestTurnAlwaysKept() {
        ThreadContext.History history = history(List.of(userText(0, "first"), userText(1, "second")));

        String trimmed = history.render(AGENT, history.droppable());

        assertThat(history.droppable()).isEqualTo(1);
        assertThat(trimmed).startsWith(ThreadContext.HEADER).contains("second").doesNotContain("first");
        assertThat(trimmed).endsWith("\nRequest:\n");
    }

    @Test
    @DisplayName("another agent's last reply is named, not claimed as the reader's own")
    void anotherAgentsReplyIsNamed() {
        ThreadContext.History history = history(List.of(userText(0, "go"), answer(1, "the draft")));

        assertThat(history.render(OTHER_AGENT)).contains("Research's last reply (verbatim):\nthe draft");
        assertThat(history.render(null)).contains("Research's last reply (verbatim):\nthe draft");
        assertThat(history.render(AGENT)).contains("Your last reply (verbatim):\nthe draft");
    }

    @Test
    @DisplayName("a person's turn reads as their name when known, else as the requester or another person")
    void authorsAreLabelled() {
        ChatMessage mine = userText(0, "my message");
        ChatMessage theirs = ChatMessage.of(
                ORG, CONVERSATION, 1, "user", COLLEAGUE, null, "text", "a colleague's message", Map.of(), null);
        ChatMessage unknownAuthor =
                ChatMessage.of(ORG, CONVERSATION, 2, "user", null, null, "text", "nobody's message", Map.of(), null);
        List<ChatMessage> turns = List.of(mine, theirs, unknownAuthor);

        String anonymous = ThreadContext.of(turns, Map.of(), Map.of(), REQUESTER).render(AGENT);
        assertThat(anonymous).contains("The requester: my message");
        assertThat(anonymous).contains("Another person: a colleague's message");
        assertThat(anonymous).contains("Another person: nobody's message");

        String named = ThreadContext.of(turns, Map.of(), Map.of(COLLEAGUE, "Priya Shah"), REQUESTER)
                .render(AGENT);
        assertThat(named).contains("Priya Shah: a colleague's message");
        assertThat(named).contains("The requester: my message");
    }
}
