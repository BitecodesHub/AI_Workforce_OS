package os.aiworkforce.llm.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.TokenEstimate;
import os.aiworkforce.llm.model.ToolCall;

/**
 * What compaction keeps and what it cuts: the request after a chat preamble survives whole, a
 * tool result is shortened even among the newest turns, and the model's window sets how far.
 */
class TranscriptCompactorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TranscriptCompactor compactor = new TranscriptCompactor();

    private static ModelSpec model(int window) {
        return new ModelSpec(
                "vendor",
                "model-a",
                "Model A",
                window,
                4096,
                true,
                true,
                true,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null);
    }

    private static ChatRequest request(List<ChatMessage> messages) {
        return ChatRequest.builder().messages(messages).build();
    }

    /** A chat turn followed by `turns` more pairs of user and assistant messages of this length. */
    private static List<ChatMessage> longConversation(String firstUserMessage, int turns, int length) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a careful assistant."));
        messages.add(ChatMessage.user(firstUserMessage));
        messages.add(ChatMessage.assistant("Understood."));
        for (int i = 0; i < turns; i++) {
            messages.add(ChatMessage.user("question " + i + " " + "q".repeat(length)));
            messages.add(ChatMessage.assistant("answer " + i + " " + "a".repeat(length)));
        }
        return messages;
    }

    private static String summaryOf(ChatRequest compacted) {
        return compacted.messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content)
                .filter(text -> text.startsWith("Earlier in this conversation"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the text after the last 'Request:' is kept in full when the first message is summarised away")
    void keepsTheRequestAfterThePreamble() {
        String preamble = "Thread so far: " + "background ".repeat(300) + "\nDocuments: " + "passage ".repeat(300);
        String first = preamble + "\nRequest: Summarise the three biggest risks in the Q3 plan, and name an owner for each.";

        ChatRequest compacted = compactor.compact(request(longConversation(first, 8, 1_500)), model(12_000));

        assertThat(compacted).isNotNull();
        String summary = summaryOf(compacted);
        assertThat(summary).contains("Summarise the three biggest risks in the Q3 plan, and name an owner for each.");
        // The preamble is what the old cut kept instead of the request.
        assertThat(summary).doesNotContain("background background");
    }

    @Test
    @DisplayName("the last marker wins, so a quoted 'Request:' inside the context does not stand in for the real one")
    void usesTheLastMarker() {
        String first = "Request: an earlier line quoted in the thread\n" + "filler ".repeat(100) + "\nRequest: the real ask";

        assertThat(TranscriptCompactor.originalRequest(first)).isEqualTo("the real ask");
    }

    @Test
    @DisplayName("a request of about two thousand characters is kept whole, and a longer one is cut with an ellipsis")
    void requestLengthIsBounded() {
        String exact = "r".repeat(TranscriptCompactor.REQUEST_KEEP_CHARS);
        assertThat(TranscriptCompactor.originalRequest("context\nRequest: " + exact)).isEqualTo(exact);

        String longer = TranscriptCompactor.originalRequest("context\nRequest: " + "r".repeat(5_000));
        assertThat(longer).hasSize(TranscriptCompactor.REQUEST_KEEP_CHARS).endsWith("…");
    }

    @Test
    @DisplayName("with no marker the start of the message is kept, to the same bound")
    void noMarkerKeepsTheStart() {
        String message = "Please review the attached contract. " + "x".repeat(5_000);

        String kept = TranscriptCompactor.originalRequest(message);

        assertThat(kept).startsWith("Please review the attached contract.");
        assertThat(kept).hasSize(TranscriptCompactor.REQUEST_KEEP_CHARS);
    }

    @Test
    @DisplayName("an empty request after the marker falls back to the message itself")
    void emptyRequestAfterTheMarker() {
        assertThat(TranscriptCompactor.originalRequest("Do the thing.\nRequest:   ")).startsWith("Do the thing.");
    }

    @Test
    @DisplayName("an oversized tool result is shortened even when it is among the newest turns")
    void shortensAToolResultInTheProtectedTurns() {
        String big = "{\"count\":4000,\"items\":[" + "{\"id\":1,\"title\":\"an issue\"},".repeat(8_000) + "]}";
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("List the open issues."),
                ChatMessage.assistantToolCalls(null, List.of(new ToolCall("call-1", "github__list_issues", "{}"))),
                ChatMessage.toolResult("call-1", "github__list_issues", big));
        ModelSpec small = model(8_000);

        ChatRequest compacted = compactor.compact(request(messages), small);

        assertThat(compacted).isNotNull();
        ChatMessage tool = compacted.messages().get(3);
        assertThat(tool.role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(tool.content().length()).isLessThan(big.length() / 10);
        // The result is still valid JSON that says it was cut, not a document with an ellipsis on it.
        JsonNode parsed = parse(tool.content());
        assertThat(parsed.path("truncated").asBoolean()).isTrue();
        assertThat(parsed.path("originalCharacters").asInt()).isEqualTo(big.length());
        assertThat(parsed.path("note").asText()).isNotBlank();
        assertThat(parsed.path("text").asText()).startsWith("{\"count\":4000");
        // The call it answers is still there.
        assertThat(compacted.messages().get(2).toolCalls()).hasSize(1);
        assertThat(TokenEstimate.forRequest(compacted)).isLessThanOrEqualTo((int) (small.contextWindowTokens() * 0.55));
    }

    @Test
    @DisplayName("the window sets how far a tool result is cut: a larger window keeps more of it")
    void budgetSetsTheCut() {
        String big = "word ".repeat(60_000);
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("Read this."),
                ChatMessage.assistantToolCalls(null, List.of(new ToolCall("c", "docs__read", "{}"))),
                ChatMessage.toolResult("c", "docs__read", big));

        ChatRequest inSmall = compactor.compact(request(messages), model(8_000));
        ChatRequest inLarge = compactor.compact(request(messages), model(32_000));

        assertThat(inSmall).isNotNull();
        assertThat(inLarge).isNotNull();
        assertThat(inSmall.messages().get(3).content().length())
                .isLessThan(inLarge.messages().get(3).content().length());
    }

    @Test
    @DisplayName("a result already cut is cut again from the original text, never wrapped in a second wrapper")
    void secondCompactionDoesNotNest() {
        String big = "w".repeat(120_000);
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("Read this."),
                ChatMessage.assistantToolCalls(null, List.of(new ToolCall("c", "docs__read", "{}"))),
                ChatMessage.toolResult("c", "docs__read", big));

        ChatRequest once = compactor.compact(request(messages), model(32_000));
        ChatRequest twice = compactor.compact(once, model(8_000));

        assertThat(twice).isNotNull();
        JsonNode parsed = parse(twice.messages().get(3).content());
        assertThat(parsed.path("originalCharacters").asInt()).isEqualTo(big.length());
        assertThat(parsed.path("text").asText()).doesNotContain("truncated");
        assertThat(parsed.path("text").asText()).isEqualTo("w".repeat(parsed.path("shownCharacters").asInt()));
    }

    @Test
    @DisplayName("nothing is shortened when every tool result is already small")
    void smallResultsAreLeftAlone() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are an agent."),
                ChatMessage.user("List."),
                ChatMessage.assistantToolCalls(null, List.of(new ToolCall("c", "t", "{}"))),
                ChatMessage.toolResult("c", "t", "{\"count\":0,\"items\":[]}"));

        assertThat(compactor.compact(request(messages), model(8_000))).isNull();
    }

    @Test
    @DisplayName("the system prompt is never dropped, and the last six turns are kept")
    void keepsTheSystemPromptAndRecentTurns() {
        List<ChatMessage> original = longConversation("Do the Q3 report.", 10, 1_500);

        ChatRequest compacted = compactor.compact(request(original), model(12_000));

        assertThat(compacted).isNotNull();
        assertThat(compacted.messages().get(0).content()).isEqualTo("You are a careful assistant.");
        List<ChatMessage> tail = original.subList(original.size() - 6, original.size());
        assertThat(compacted.messages().subList(compacted.messages().size() - 6, compacted.messages().size()))
                .isEqualTo(tail);
        assertThat(TokenEstimate.forRequest(compacted)).isLessThan(TokenEstimate.forRequest(request(original)));
    }

    @Test
    @DisplayName("a tool result is never separated from the assistant turn that called for it")
    void keepsToolPairs() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are an agent."));
        messages.add(ChatMessage.user("Do the work."));
        for (int i = 0; i < 6; i++) {
            messages.add(ChatMessage.assistantToolCalls(null, List.of(new ToolCall("c" + i, "t", "{}"))));
            messages.add(ChatMessage.toolResult("c" + i, "t", "result " + i + " " + "z".repeat(3_000)));
        }
        messages.add(ChatMessage.assistant("Done."));

        ChatRequest compacted = compactor.compact(request(messages), model(12_000));

        assertThat(compacted).isNotNull();
        List<ChatMessage> kept = compacted.messages();
        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).role() == ChatMessage.Role.TOOL) {
                ChatMessage before = kept.get(i - 1);
                assertThat(before.role() == ChatMessage.Role.ASSISTANT || before.role() == ChatMessage.Role.TOOL)
                        .as("message %d follows its call", i)
                        .isTrue();
            }
        }
    }

    private static JsonNode parse(String content) {
        try {
            return JSON.readTree(content);
        } catch (Exception e) {
            throw new AssertionError("Not valid JSON: " + content.substring(0, Math.min(80, content.length())), e);
        }
    }
}
