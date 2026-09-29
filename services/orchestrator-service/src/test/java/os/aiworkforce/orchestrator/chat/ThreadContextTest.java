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

    private static ChatMessage userText(int position, String content) {
        return ChatMessage.of(
                ORG, CONVERSATION, position, "user", UUID.randomUUID(), null, "text", content, Map.of(), null);
    }

    private static ChatMessage answer(int position, String content) {
        return ChatMessage.of(ORG, CONVERSATION, position, "agent", null, AGENT, "answer", content, Map.of(), null);
    }

    private static ChatMessage card(int position, String kind) {
        return ChatMessage.of(ORG, CONVERSATION, position, "coordinator", null, null, kind, "ignored", Map.of(), null);
    }

    @Test
    @DisplayName("no earlier turns produces an empty preamble")
    void emptyWhenNoTurns() {
        assertThat(ThreadContext.preamble(List.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("only user text and agent answers are kept - routing and other cards are skipped")
    void skipsCards() {
        List<ChatMessage> turns =
                List.of(userText(0, "please help"), card(1, "routing"), card(2, "progress"), answer(3, "here you go"));

        String preamble = ThreadContext.preamble(turns, Map.of(AGENT, "Research"));

        assertThat(preamble).contains("Person: please help");
        assertThat(preamble).contains("Research: here you go");
        assertThat(preamble).doesNotContain("ignored");
    }

    @Test
    @DisplayName("at most the last 6 turns are kept")
    void keepsLastSixTurns() {
        List<ChatMessage> turns = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            turns.add(userText(i, "turn number " + i));
        }

        String preamble = ThreadContext.preamble(turns, Map.of());

        assertThat(preamble).doesNotContain("turn number 3");
        for (int i = 4; i < 10; i++) {
            assertThat(preamble).contains("turn number " + i);
        }
    }

    @Test
    @DisplayName("each message is cut to 600 characters, at a word boundary")
    void cutsAtSixHundredCharacters() {
        String longMessage = "word ".repeat(200).strip();
        List<ChatMessage> turns = List.of(userText(0, longMessage));

        String preamble = ThreadContext.preamble(turns, Map.of());

        String line = preamble.lines()
                .filter(l -> l.startsWith("Person:"))
                .findFirst()
                .orElseThrow();
        assertThat(line.substring("Person: ".length())).hasSizeLessThanOrEqualTo(600);
        assertThat(line).doesNotEndWith(" ");
    }

    @Test
    @DisplayName("the whole block is capped at 3,000 characters, dropping the oldest turn first")
    void capsWholeBlockAtThreeThousand() {
        List<ChatMessage> turns = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            turns.add(userText(i, ("turn-" + i + " ") + "filler ".repeat(120)));
        }

        String preamble = ThreadContext.preamble(turns, Map.of());

        assertThat(preamble.length()).isLessThanOrEqualTo(3_000 + "\nRequest:\n".length());
        assertThat(preamble).doesNotContain("turn-0 ");
    }
}
