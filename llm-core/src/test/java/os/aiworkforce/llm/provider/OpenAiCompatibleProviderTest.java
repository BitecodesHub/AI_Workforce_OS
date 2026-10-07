package os.aiworkforce.llm.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
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
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.ToolSpec;

/**
 * The adapter's failure classification, against the response shapes the real services return.
 *
 * <p>This is the most consequential logic in the provider layer and the easiest to get quietly
 * wrong. The router decides whether to retry, to fail over, or to stop entirely from the
 * {@link ProviderFailure} this adapter produces, so a misclassification does not look like a bug -
 * it looks like the platform being slow, or expensive, or mysteriously giving up.
 *
 * <p>Each response body below is the shape the corresponding service actually sends. They are
 * asserted rather than described because the distinctions are subtle: a 429 can mean "slow down"
 * or "you are out of credit", and a 400 covers context overflow, a safety refusal and a genuinely
 * malformed request, each of which needs a different response.
 */
class OpenAiCompatibleProviderTest {

    private WireMockServer server;
    private OpenAiCompatibleProvider provider;
    private ProviderDescriptor descriptor;
    private ModelSpec model;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();

        provider = new OpenAiCompatibleProvider(WebClient.builder(), new ObjectMapper());
        descriptor = new ProviderDescriptor(
                "openrouter",
                "OpenRouter",
                ProviderDescriptor.Kind.OPENAI_COMPATIBLE,
                server.baseUrl(),
                "provider:openrouter",
                true,
                Map.of(),
                List.of(),
                null,
                null,
                0);
        model = new ModelSpec(
                "openrouter",
                "meta-llama/llama-3.3-70b-instruct",
                "Llama 3.3 70B",
                128_000,
                8_192,
                true,
                true,
                true,
                false,
                BigDecimal.ONE,
                null,
                BigDecimal.ONE,
                true,
                null);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ---- Successful responses ---------------------------------------------------------------

    @Test
    @DisplayName("parses a plain answer, its usage and its finish reason")
    void parsesAnswer() {
        stub(
                200,
                """
                {"id":"gen-1","choices":[{"message":{"role":"assistant","content":"Two business days."},
                "finish_reason":"stop"}],
                "usage":{"prompt_tokens":120,"completion_tokens":18,
                "prompt_tokens_details":{"cached_tokens":80}}}
                """);

        ChatResponse response = complete(request("How much notice?"));

        assertThat(response.content()).isEqualTo("Two business days.");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().promptTokens()).isEqualTo(120);
        assertThat(response.usage().completionTokens()).isEqualTo(18);
        // Cached tokens are priced differently, so they are tracked separately rather than
        // folded into the prompt total.
        assertThat(response.usage().cachedPromptTokens()).isEqualTo(80);
        assertThat(response.usage().freshPromptTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("parses tool calls and reports the finish reason as a tool call")
    void parsesToolCalls() {
        stub(
                200,
                """
                {"id":"gen-2","choices":[{"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_abc","type":"function",
                "function":{"name":"gmail.draft_message","arguments":"{\\"to\\":\\"a@b.com\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":200,"completion_tokens":30}}
                """);

        ChatResponse response = complete(withTools("Draft an email"));

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_abc");
            assertThat(call.name()).isEqualTo("gmail.draft_message");
            // Kept as raw text, because a model's arguments are malformed often enough that
            // parsing at the boundary would discard what a repair pass needs.
            assertThat(call.argumentsJson()).contains("a@b.com");
        });
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    @DisplayName("reports a truncated answer as truncated rather than complete")
    void reportsTruncation() {
        stub(
                200,
                """
                {"id":"gen-3","choices":[{"message":{"role":"assistant","content":"The policy is"},
                "finish_reason":"length"}],"usage":{"prompt_tokens":10,"completion_tokens":4}}
                """);

        ChatResponse response = complete(request("Explain the policy"));

        // A sentence cut in half must not be treated as a finished answer: that is how a
        // truncated email gets sent.
        assertThat(response.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(response.isTruncated()).isTrue();
    }

    // ---- Throttling, which is two different problems -----------------------------------------

    @Test
    @DisplayName("classifies a plain 429 as rate limited and honours Retry-After")
    void rateLimitedWithRetryAfter() {
        server.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "7")
                        .withBody("{\"error\":{\"message\":\"Rate limit reached\",\"code\":\"rate_limit\"}}")));

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.RATE_LIMITED);
        assertThat(failure.failure().retrySameCandidate()).isTrue();
        // Honouring the header rather than guessing is what stops a backoff that is either
        // pointlessly slow or fast enough to be throttled again immediately.
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("classifies an out-of-credit 429 as quota exhausted, which is not retryable")
    void quotaExhausted() {
        stub(
                429,
                """
                {"error":{"message":"You exceeded your current quota, please check your billing details",
                "code":"insufficient_quota"}}
                """);

        ProviderException failure = expectFailure(request("hello"));

        // The distinction that matters: a throttle heals in seconds, an empty account does not.
        // Retrying the second burns the retry budget on every request until somebody notices.
        assertThat(failure.failure()).isEqualTo(ProviderFailure.QUOTA_EXHAUSTED);
        assertThat(failure.failure().retrySameCandidate()).isFalse();
        assertThat(failure.failure().operatorActionRequired()).isTrue();
    }

    @Test
    @DisplayName("classifies a 402 as not enough credit for this model, without condemning the whole key")
    void insufficientCredit() {
        stub(
                402,
                """
                {"error":{"message":"This request requires more credits","code":402}}
                """);

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.INSUFFICIENT_CREDIT);
        assertThat(failure.failure().retrySameCandidate()).isFalse();
        assertThat(failure.failure().tryNextCandidate()).isTrue();
    }

    // ---- The three meanings of a 400 ---------------------------------------------------------

    @Test
    @DisplayName("classifies a context overflow reported as a 400")
    void contextLengthExceeded() {
        stub(
                400,
                """
                {"error":{"message":"This model's maximum context length is 128000 tokens. However, your
                messages resulted in 131204 tokens. Please reduce the length of the messages.",
                "code":"context_length_exceeded"}}
                """);

        ProviderException failure = expectFailure(request("a very long conversation"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.CONTEXT_LENGTH_EXCEEDED);
        // Not counted against the breaker: the request was too long, which is the platform's
        // doing, not the provider being unwell.
        assertThat(failure.failure().countsAgainstCircuit()).isFalse();
    }

    @Test
    @DisplayName("classifies a safety refusal and refuses to fail over")
    void contentFiltered() {
        stub(
                400,
                """
                {"error":{"message":"Your request was rejected as a result of our safety system",
                "code":"content_policy_violation"}}
                """);

        ProviderException failure = expectFailure(request("something blocked"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.CONTENT_FILTERED);
        // Asking another vendor the same blocked question is shopping for weaker safety. It is
        // the one failure the chain must not route around.
        assertThat(failure.failure().tryNextCandidate()).isFalse();
        assertThat(failure.failure().retrySameCandidate()).isFalse();
    }

    @Test
    @DisplayName("classifies a genuinely malformed request as the caller's fault")
    void invalidRequest() {
        stub(400, "{\"error\":{\"message\":\"Unsupported parameter: 'presence_penalty'\"}}");

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.INVALID_REQUEST);
        assertThat(failure.failure().tryNextCandidate()).isFalse();
    }

    // ---- Credentials and retired models -------------------------------------------------------

    @Test
    @DisplayName("classifies a rejected key without retrying it")
    void authenticationFailed() {
        stub(401, "{\"error\":{\"message\":\"Invalid API key provided\",\"code\":\"invalid_api_key\"}}");

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.AUTHENTICATION_FAILED);
        // A key that is wrong now will be wrong in a second. Retrying only adds latency to a
        // call that cannot succeed.
        assertThat(failure.failure().retrySameCandidate()).isFalse();
        assertThat(failure.failure().operatorActionRequired()).isTrue();
    }

    @Test
    @DisplayName("classifies a retired model so the registry can stop offering it")
    void modelNotFound() {
        stub(404, "{\"error\":{\"message\":\"The model 'gpt-4-vision-preview' does not exist\"}}");

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.MODEL_NOT_FOUND);
        assertThat(failure.failure().operatorActionRequired()).isTrue();
    }

    // ---- The 200 that is actually an error ----------------------------------------------------

    @Test
    @DisplayName("catches an error returned inside a 200, as OpenRouter does")
    void errorEnvelopeInsideSuccess() {
        // OpenRouter and several proxies wrap an upstream failure in their own envelope and
        // answer 200. Treating it as success hands the person an empty answer and no explanation.
        stub(
                200,
                """
                {"error":{"message":"Provider returned error","code":429},"user_id":"u-1"}
                """);

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isNotNull();
        assertThat(failure.getMessage()).contains("Provider returned error");
    }

    @Test
    @DisplayName("treats a response with no choices as malformed rather than empty")
    void noChoices() {
        stub(200, "{\"id\":\"gen-9\",\"choices\":[]}");

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.MALFORMED_RESPONSE);
        assertThat(failure.failure().retrySameCandidate()).isTrue();
    }

    // ---- Transport ------------------------------------------------------------------------------

    @Test
    @DisplayName("classifies a server fault as retryable")
    void serverError() {
        stub(503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}");

        ProviderException failure = expectFailure(request("hello"));

        assertThat(failure.failure()).isEqualTo(ProviderFailure.SERVER_ERROR);
        assertThat(failure.failure().retrySameCandidate()).isTrue();
        assertThat(failure.failure().tryNextCandidate()).isTrue();
    }

    @Test
    @DisplayName("classifies a deadline overrun as a timeout")
    void timeout() {
        server.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000).withBody("{}")));

        ChatRequest slow = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("hello")))
                .timeout(Duration.ofMillis(200))
                .build();

        ProviderException failure = expectFailure(slow);

        assertThat(failure.failure()).isEqualTo(ProviderFailure.TIMEOUT);
    }

    // ---- Request shaping --------------------------------------------------------------------------

    @Test
    @DisplayName("asks for usage on a streamed call, which these providers otherwise omit")
    void streamRequestsUsage() {
        server.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n")));

        provider.stream(descriptor, model, request("hello"), "key")
                .collectList()
                .block(Duration.ofSeconds(5));

        // Without stream_options.include_usage, most of this family report no usage at all on a
        // streamed call, and the attempt is recorded as having cost nothing.
        server.verify(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("include_usage")));
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private void stub(int status, String body) {
        server.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private ChatResponse complete(ChatRequest request) {
        return provider.complete(descriptor, model, request, "key").block(Duration.ofSeconds(10));
    }

    private ProviderException expectFailure(ChatRequest request) {
        return (ProviderException) org.assertj.core.api.Assertions.catchThrowable(() -> complete(request));
    }

    private static ChatRequest request(String text) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user(text)))
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private static ChatRequest withTools(String text) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user(text)))
                .tools(List.of(
                        new ToolSpec("gmail.draft_message", "Prepare an email", null, ToolSpec.SideEffect.WRITE)))
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("every classification is reachable and consistent")
    void failureTaxonomyIsConsistent() {
        for (ProviderFailure failure : ProviderFailure.values()) {
            // A failure that can neither be retried nor failed over is terminal, which is
            // legitimate - but it must then map to an error code the caller can act on.
            assertThat(failure.toErrorCode()).isNotNull();
            if (failure.retrySameCandidate()) {
                assertThat(failure.tryNextCandidate())
                        .as("%s is retryable, so another candidate should also be worth trying", failure)
                        .isTrue();
            }
        }
        assertThatThrownBy(() -> ProviderFailure.valueOf("NOT_A_FAILURE")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Pictures ----------------------------------------------------------------------------

    @Test
    @DisplayName("sends a user turn's pictures as image_url content parts after its text")
    void sendsImagesAsContentParts() {
        stub(
                200,
                """
                {"id":"gen-img","choices":[{"message":{"role":"assistant","content":"A bar chart."},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":900,"completion_tokens":4}}
                """);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("Be brief."),
                        ChatMessage.userWithImages(
                                "What does this chart show?",
                                List.of(new os.aiworkforce.llm.model.ImagePart("chart.png", "image/png", "iVBORw0KGgo=")))))
                .timeout(Duration.ofSeconds(5))
                .build();

        assertThat(provider.sendsImages()).isTrue();
        complete(request);

        server.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.messages[1].content[0].type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("text")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.messages[1].content[0].text",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("What does this chart show?")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.messages[1].content[1].type",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("image_url")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.messages[1].content[1].image_url.url",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("data:image/png;base64,iVBORw0KGgo=")))
                // The system turn keeps the plain string form.
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.messages[0].content", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Be brief."))));
    }

    @Test
    @DisplayName("a turn's pictures become a plain note for a model that cannot see them")
    void imagesBecomeNotes() {
        ChatMessage turn = ChatMessage.userWithImages(
                "Read this.", List.of(new os.aiworkforce.llm.model.ImagePart("scan.png", "image/png", "AAAA")));
        ChatMessage noted = turn.withImagesAsNotes("Llama 3.3 70B");

        assertThat(noted.hasImages()).isFalse();
        assertThat(noted.content())
                .startsWith("Read this.")
                .contains("\"scan.png\" could not be shown to you: Llama 3.3 70B cannot read images")
                .contains("vision-capable model in Model routing");
        assertThat(turn.approximateTokens()).isGreaterThan(noted.approximateTokens() - 200);
    }
}
