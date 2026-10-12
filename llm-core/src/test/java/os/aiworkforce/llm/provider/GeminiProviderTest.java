// @find: tests for Gemini provider, thought signature, Gemini 3 function calling, invalid request after tool call, skip_thought_signature_validator
// @what: Checks that Gemini 3 thought signatures are sent back with their function calls, so a second turn is not refused.
package os.aiworkforce.llm.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ToolCall;

class GeminiProviderTest {

    private static final String ANSWER_WITH_CALL = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"browser__open_page","args":{"url":"https://example.com"}},
               "thoughtSignature":"sig-123"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}
            """;

    private static final String PLAIN_ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Done."}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":2}}
            """;

    private WireMockServer server;
    private GeminiProvider provider;
    private ProviderDescriptor descriptor;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new GeminiProvider(WebClient.builder(), new ObjectMapper());
        descriptor = new ProviderDescriptor(
                "gemini", "Google Gemini", ProviderDescriptor.Kind.GEMINI, server.baseUrl(),
                "provider:gemini", true, Map.of(), List.of(), null, null, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static ModelSpec model(String id) {
        return new ModelSpec("gemini", id, id, 1_000_000, 8_192, true, true, true, false,
                BigDecimal.ONE, null, BigDecimal.ONE, true, null);
    }

    private static ChatRequest followUp(ToolCall call) {
        return ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("Open example.com"),
                        ChatMessage.assistantToolCalls("", List.of(call)),
                        ChatMessage.toolResult(call.id(), call.name(), "{\"url\":\"https://example.com/\"}")))
                .build();
    }

    @Test
    @DisplayName("the signature Gemini 3 sends with a function call goes back with that call on the next turn")
    void signatureIsSentBack() {
        ModelSpec model = model("gemini-3.5-flash");
        server.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3.5-flash:generateContent"))
                .willReturn(okJson(ANSWER_WITH_CALL)));

        ChatResponse first = provider.complete(descriptor, model,
                ChatRequest.builder().messages(List.of(ChatMessage.user("Open example.com"))).build(), "key").block();
        assertThat(first).isNotNull();
        ToolCall call = first.toolCalls().getFirst();

        server.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3.5-flash:generateContent"))
                .willReturn(okJson(PLAIN_ANSWER)));
        provider.complete(descriptor, model, followUp(call), "key").block();

        server.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/gemini-3.5-flash:generateContent"))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].thoughtSignature", equalTo("sig-123"))));
    }

    @Test
    @DisplayName("a call Gemini 3 never saw gets the documented skip value; Gemini 2.5 gets no signature at all")
    void unknownCallGetsSkipValueOnGemini3Only() {
        server.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3.5-flash:generateContent"))
                .willReturn(okJson(PLAIN_ANSWER)));
        server.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-2.5-flash-lite:generateContent"))
                .willReturn(okJson(PLAIN_ANSWER)));
        ToolCall fromAnotherModel = new ToolCall("call_x", "browser.read_page", "{\"from\":3500}");

        provider.complete(descriptor, model("gemini-3.5-flash"), followUp(fromAnotherModel), "key").block();
        provider.complete(descriptor, model("gemini-2.5-flash-lite"), followUp(fromAnotherModel), "key").block();

        server.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/gemini-3.5-flash:generateContent"))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].thoughtSignature",
                        equalTo(GeminiProvider.SKIP_SIGNATURE_CHECK))));
        server.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/gemini-2.5-flash-lite:generateContent"))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].functionCall.name", equalTo("browser__read_page")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*thoughtSignature.*")));
    }
}
