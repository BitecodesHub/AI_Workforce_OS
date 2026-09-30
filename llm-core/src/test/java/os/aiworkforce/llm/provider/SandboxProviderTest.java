package os.aiworkforce.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;

/** The sandbox asks a person something only when a prompt says so, and only once per run. */
class SandboxProviderTest {

    private static final ToolSpec DRAFT = new ToolSpec(
            "gmail.draft_message",
            "Drafts an email.",
            "{\"type\":\"object\",\"required\":[\"to\"],\"properties\":{\"to\":{\"type\":\"string\"}}}",
            ToolSpec.SideEffect.WRITE);
    private static final ToolSpec ASK = new ToolSpec(
            "person.ask_question",
            "Ask the person a question.",
            "{\"type\":\"object\",\"required\":[\"questions\"],\"properties\":{\"questions\":{\"type\":\"array\"}}}",
            ToolSpec.SideEffect.READ);

    private final ObjectMapper json = new ObjectMapper();
    private SandboxProvider sandbox;
    private ProviderDescriptor provider;
    private ModelSpec model;

    @BeforeEach
    void setUp() {
        sandbox = new SandboxProvider(json);
        provider = new ProviderDescriptor(
                "sandbox",
                "Sandbox",
                ProviderDescriptor.Kind.SANDBOX,
                null,
                null,
                true,
                Map.of(),
                List.of(),
                null,
                null,
                0);
        model = new ModelSpec(
                "sandbox",
                "sandbox-echo",
                "Sandbox",
                128_000,
                4_096,
                true,
                true,
                true,
                false,
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO,
                true,
                null);
    }

    @Test
    @DisplayName("the person's ask tool is never picked at random, whatever the conversation")
    void neverPicksPersonToolAtRandom() {
        List<String> called = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ChatResponse withBoth = complete(
                    List.of(DRAFT, ASK),
                    ChatMessage.system("You are a helper."),
                    ChatMessage.user("Write note number " + i + "."));
            withBoth.toolCalls().forEach(call -> called.add(call.name()));
            ChatResponse askOnly = complete(
                    List.of(ASK),
                    ChatMessage.system("You are a helper."),
                    ChatMessage.user("Write note number " + i + "."));
            askOnly.toolCalls().forEach(call -> called.add(call.name()));
        }

        // The ordinary tool is still exercised, so the branch is live rather than switched off.
        assertThat(called).contains("gmail.draft_message").doesNotContain("person.ask_question");
    }

    @Test
    @DisplayName("[[ask]] calls the ask tool with one question in the shape the parser accepts")
    void askMarkerProducesValidQuestion() throws Exception {
        ChatResponse response = complete(
                List.of(DRAFT, ASK),
                ChatMessage.system("You are a helper."),
                ChatMessage.user("Draft the update [[ask]]"));

        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().getFirst();
        assertThat(call.name()).isEqualTo("person.ask_question");
        JsonNode questions = json.readTree(call.argumentsJson()).path("questions");
        assertThat(questions.size()).isEqualTo(1);
        assertValidQuestion(questions.get(0));
        assertThat(questions.get(0).path("multiSelect").asBoolean()).isFalse();
        assertThat(questions.get(0).path("recommended").asInt()).isZero();

        ChatResponse multi = complete(
                List.of(ASK),
                ChatMessage.system("You are a helper."),
                ChatMessage.user("Draft the update [[ask:multi]]"));
        JsonNode both =
                json.readTree(multi.toolCalls().getFirst().argumentsJson()).path("questions");
        assertThat(both.size()).isEqualTo(2);
        both.forEach(SandboxProviderTest::assertValidQuestion);
        assertThat(both.get(1).path("multiSelect").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("once the person has answered, the sandbox answers rather than asking again")
    void noSecondAskAfterAnswer() {
        ToolCall ask = new ToolCall("t1_0_call_ask", "person.ask_question", "{}");
        ChatResponse response = complete(
                List.of(ASK),
                ChatMessage.system("You are a helper."),
                ChatMessage.user("Draft the update [[ask]]"),
                ChatMessage.assistantToolCalls(null, List.of(ask)),
                ChatMessage.toolResult(ask.id(), ask.name(), "{\"status\":\"answered\"}"));

        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.content()).isNotBlank();
    }

    private ChatResponse complete(List<ToolSpec> tools, ChatMessage... messages) {
        ChatRequest request =
                ChatRequest.builder().messages(List.of(messages)).tools(tools).build();
        return sandbox.complete(provider, model, request, null).block();
    }

    /** The same limits AskPersonTool.parse enforces, asserted on the JSON the sandbox sends. */
    private static void assertValidQuestion(JsonNode question) {
        assertThat(question.path("header").asText()).isNotBlank().hasSizeLessThanOrEqualTo(16);
        assertThat(question.path("question").asText())
                .isNotBlank()
                .endsWith("?")
                .hasSizeLessThanOrEqualTo(300);
        JsonNode options = question.path("options");
        assertThat(options.size()).isBetween(2, 4);
        List<String> labels = new ArrayList<>();
        options.forEach(option -> {
            String label = option.path("label").asText();
            assertThat(label).isNotBlank().hasSizeLessThanOrEqualTo(60).isNotEqualToIgnoringCase("other");
            assertThat(option.path("description").asText()).isNotBlank().hasSizeLessThanOrEqualTo(160);
            labels.add(label.toLowerCase(java.util.Locale.ROOT));
        });
        assertThat(labels).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("quotes the request itself, not the earlier-turns preamble chat puts before it")
    void quotesRequestAfterPreamble() {
        String message = "Earlier in this conversation, for context (the request itself comes after this):\n"
                + "Person: Draft a welcome email\nHR: Here is a draft.\n\nRequest:\nmake it shorter";
        assertThat(SandboxProvider.requestPart(message)).isEqualTo("make it shorter");
        assertThat(SandboxProvider.requestPart("What is java?")).isEqualTo("What is java?");
    }
}
