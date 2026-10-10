// @find: tests for Bedrock provider, Converse API, tool use, images, SigV4 or bearer auth, error classification, embeddings
// @what: Checks what is sent to and read back from Bedrock and how each AWS error is classified.
package os.aiworkforce.llm.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.EmbeddingPurpose;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ImagePart;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;

/**
 * The Bedrock adapter against a stub server speaking the Converse and InvokeModel shapes: what is
 * sent (system prompt, alternating turns, tool use and results, images, signed or bearer auth),
 * what is read back (text, tool calls, usage), and how each AWS error is classified for the
 * router. No AWS account is involved; the shapes are those in the Bedrock API reference.
 */
class BedrockProviderTest {

    private static final String KEYS =
            "{\"type\":\"access_key\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\","
                    + "\"secretAccessKey\":\"wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY\",\"region\":\"eu-west-1\"}";
    private static final String API_KEY = "{\"type\":\"api_key\",\"apiKey\":\"ABSKexampleexampleexample1234\",\"region\":\"us-east-1\"}";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer server;
    private BedrockProvider provider;
    private ProviderDescriptor descriptor;
    private ModelSpec claude;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new BedrockProvider(
                WebClient.builder(), json, Map.of(), Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC));
        descriptor = new ProviderDescriptor(
                "bedrock", "AWS Bedrock", ProviderDescriptor.Kind.BEDROCK, server.baseUrl(), "provider:bedrock", true,
                Map.of(), List.of("us-east-1", "us-west-2"), null, null, 35);
        claude = model("eu.anthropic.claude-sonnet-4-20250514-v1:0", true);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static ModelSpec model(String id, boolean tools) {
        return new ModelSpec(
                "bedrock", id, "Claude Sonnet 4", 200_000, 64_000, tools, true, false, true,
                new BigDecimal("3"), null, new BigDecimal("15"), true, null);
    }

    private void stubConverse(int status, String body, String errorType) {
        var response = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
        if (errorType != null) {
            response.withHeader("x-amzn-ErrorType", errorType + ":http://internal.amazon.com/coral/com.amazon.bedrock/");
        }
        server.stubFor(post(urlPathMatching("/model/.*/converse")).willReturn(response));
    }

    private ChatResponse complete(ChatRequest request, String credential) {
        return provider.complete(descriptor, claude, request, credential).block(Duration.ofSeconds(10));
    }

    private JsonNode sentBody() throws Exception {
        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlPathMatching("/model/.*")));
        return json.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }

    private static final ToolSpec DRAFT = new ToolSpec(
            "gmail.draft_message",
            "Draft an email",
            "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}},\"required\":[\"to\"]}",
            ToolSpec.SideEffect.WRITE);

    private static final String TOOL_ANSWER = """
            {"output":{"message":{"role":"assistant","content":[
              {"text":"I will draft it."},
              {"toolUse":{"toolUseId":"tooluse_1","name":"gmail__draft_message","input":{"to":"a@b.com"}}}]}},
             "stopReason":"tool_use",
             "usage":{"inputTokens":120,"outputTokens":30,"totalTokens":150,"cacheReadInputTokens":40}}
            """;

    @Test
    @DisplayName("sends a signed Converse request with the system prompt, the turns and the tools")
    void buildsTheConverseRequest() throws Exception {
        stubConverse(200, TOOL_ANSWER, null);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.system("You are the finance agent."), ChatMessage.user("Email Ana.")))
                .tools(List.of(DRAFT))
                .maxOutputTokens(500)
                .temperature(0.2)
                .build();
        ChatResponse response = complete(request, KEYS);

        // The model id is one path segment: the colon is encoded, as AWS's SDK sends it.
        LoggedRequest sent = server.findAll(postRequestedFor(urlPathMatching("/model/.*"))).get(0);
        assertThat(sent.getUrl()).isEqualTo("/model/eu.anthropic.claude-sonnet-4-20250514-v1%3A0/converse");
        assertThat(sent.getHeader("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261008/eu-west-1/bedrock/aws4_request")
                .contains("SignedHeaders=content-type;host;x-amz-date");
        assertThat(sent.getHeader("X-Amz-Date")).isEqualTo("20261008T000000Z");

        JsonNode body = sentBody();
        assertThat(body.at("/system/0/text").asText()).isEqualTo("You are the finance agent.");
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("Email Ana.");
        assertThat(body.at("/inferenceConfig/maxTokens").asInt()).isEqualTo(500);
        assertThat(body.at("/inferenceConfig/temperature").asDouble()).isEqualTo(0.2);
        JsonNode spec = body.at("/toolConfig/tools/0/toolSpec");
        assertThat(spec.path("name").asText()).isEqualTo("gmail__draft_message");
        assertThat(spec.path("description").asText()).isEqualTo("Draft an email");
        assertThat(spec.at("/inputSchema/json/required/0").asText()).isEqualTo("to");

        assertThat(response.content()).isEqualTo("I will draft it.");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("tooluse_1");
            assertThat(call.name()).isEqualTo("gmail.draft_message");
            assertThat(json.readTree(call.argumentsJson()).path("to").asText()).isEqualTo("a@b.com");
        });
        assertThat(response.usage().promptTokens()).isEqualTo(160);
        assertThat(response.usage().cachedPromptTokens()).isEqualTo(40);
        assertThat(response.usage().completionTokens()).isEqualTo(30);
        assertThat(response.providerMetadata()).containsEntry("region", "eu-west-1");
    }

    @Test
    @DisplayName("sends the tool round trip back as toolUse and one user turn of toolResults")
    void toolRoundTrip() throws Exception {
        stubConverse(200, """
                {"output":{"message":{"role":"assistant","content":[{"text":"Drafted both."}]}},
                 "stopReason":"end_turn","usage":{"inputTokens":300,"outputTokens":5}}
                """, null);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("Email Ana and Ben."),
                        ChatMessage.assistantToolCalls(null, List.of(
                                new ToolCall("t1", "gmail.draft_message", "{\"to\":\"ana@x.com\"}"),
                                new ToolCall("t2", "gmail.draft_message", "{\"to\":\"ben@x.com\"}"))),
                        ChatMessage.toolResult("t1", "gmail.draft_message", "{\"draftId\":\"d1\"}"),
                        ChatMessage.toolResult("t2", "gmail.draft_message", "")))
                .tools(List.of(DRAFT))
                .build();
        ChatResponse response = complete(request, KEYS);

        JsonNode messages = sentBody().path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.at("/1/role").asText()).isEqualTo("assistant");
        assertThat(messages.at("/1/content/0/toolUse/toolUseId").asText()).isEqualTo("t1");
        assertThat(messages.at("/1/content/0/toolUse/name").asText()).isEqualTo("gmail__draft_message");
        assertThat(messages.at("/1/content/1/toolUse/input/to").asText()).isEqualTo("ben@x.com");
        // Both results in one user turn, so the turns alternate as Converse requires.
        assertThat(messages.at("/2/role").asText()).isEqualTo("user");
        assertThat(messages.at("/2/content/0/toolResult/toolUseId").asText()).isEqualTo("t1");
        assertThat(messages.at("/2/content/0/toolResult/content/0/text").asText()).isEqualTo("{\"draftId\":\"d1\"}");
        // An empty result is never sent blank, which Converse refuses.
        assertThat(messages.at("/2/content/1/toolResult/content/0/text").asText()).isEqualTo("(no output)");

        assertThat(response.content()).isEqualTo("Drafted both.");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    @DisplayName("a history that used tools is told as text to a request without tools, which Converse would refuse otherwise")
    void toolHistoryWithoutTools() throws Exception {
        stubConverse(200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"ok\"}]}},\"stopReason\":\"end_turn\"}", null);

        complete(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user("Hi"),
                        ChatMessage.assistantToolCalls(null, List.of(new ToolCall("t1", "crm.find", "{}"))),
                        ChatMessage.toolResult("t1", "crm.find", "nothing"),
                        ChatMessage.user("Summarise.")))
                .build(), KEYS);

        JsonNode body = sentBody();
        assertThat(body.has("toolConfig")).isFalse();
        assertThat(body.toString()).doesNotContain("toolUse").doesNotContain("toolResult");
        assertThat(body.at("/messages/2/content/0/text").asText()).contains("Result from crm.find").contains("nothing");
        assertThat(body.at("/messages/2/content/1/text").asText()).isEqualTo("Summarise.");
    }

    @Test
    @DisplayName("sends a picture as an image block for a vision model")
    void imageBlocks() throws Exception {
        stubConverse(200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"A cat.\"}]}},\"stopReason\":\"end_turn\"}", null);
        assertThat(provider.sendsImages()).isTrue();

        complete(ChatRequest.builder()
                .messages(List.of(ChatMessage.userWithImages(
                        "What is this?", List.of(new ImagePart("cat.png", "image/png", "iVBORw0KGgo=")))))
                .build(), KEYS);

        JsonNode content = sentBody().at("/messages/0/content");
        assertThat(content.at("/0/text").asText()).isEqualTo("What is this?");
        assertThat(content.at("/1/image/format").asText()).isEqualTo("png");
        assertThat(content.at("/1/image/source/bytes").asText()).isEqualTo("iVBORw0KGgo=");
    }

    @Test
    @DisplayName("a Bedrock API key is sent as a bearer token, to its own region")
    void bearerApiKey() {
        stubConverse(200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"ok\"}]}},\"stopReason\":\"end_turn\"}", null);

        ChatResponse response = complete(ChatRequest.builder().messages(List.of(ChatMessage.user("ping"))).build(), API_KEY);

        server.verify(postRequestedFor(urlPathMatching("/model/.*/converse"))
                .withHeader("Authorization", equalTo("Bearer ABSKexampleexampleexample1234")));
        assertThat(response.providerMetadata()).containsEntry("region", "us-east-1");
    }

    @Test
    @DisplayName("tool names Bedrock would refuse are shortened, and calls come back under the platform's name")
    void longToolNames() {
        String longName = "very_long_connector_server_name.an_extremely_long_tool_name_that_goes_past_sixty_four";
        BedrockProvider.ToolNameMap names = new BedrockProvider.ToolNameMap();
        String wire = names.wire(longName);

        assertThat(wire).matches("^[a-zA-Z0-9_-]{1,64}$");
        assertThat(names.platform(wire)).isEqualTo(longName);
        assertThat(names.wire("gmail.draft_message")).isEqualTo("gmail__draft_message");
        assertThat(names.platform("gmail__draft_message")).isEqualTo("gmail.draft_message");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "403|AccessDeniedException|You don't have access to the model with the specified model ID.|MODEL_NOT_FOUND",
        "403|AccessDeniedException|User: arn:aws:iam::123:user/x is not authorized to perform: bedrock:InvokeModel on resource|AUTHORISATION_FAILED",
        "403|AccessDeniedException|Authentication failed: Please make sure your API Key is valid.|AUTHENTICATION_FAILED",
        "403|UnrecognizedClientException|The security token included in the request is invalid.|AUTHENTICATION_FAILED",
        "403|ExpiredTokenException|The security token included in the request is expired|AUTHENTICATION_FAILED",
        "403|InvalidSignatureException|The request signature we calculated does not match|AUTHENTICATION_FAILED",
        "429|ThrottlingException|Too many requests, please wait before trying again.|RATE_LIMITED",
        "400|ServiceQuotaExceededException|Too many tokens per minute.|RATE_LIMITED",
        "429|ModelNotReadyException|Model is not ready to serve inference requests.|OVERLOADED",
        "503|ServiceUnavailableException|Service is unavailable.|OVERLOADED",
        "500|InternalServerException|Internal error.|SERVER_ERROR",
        "424|ModelErrorException|The model failed.|SERVER_ERROR",
        "408|ModelTimeoutException|Model timed out.|TIMEOUT",
        "404|ResourceNotFoundException|Could not resolve the foundation model.|MODEL_NOT_FOUND",
        "400|ValidationException|Invocation of model ID anthropic.claude-sonnet-4 with on-demand throughput isn't supported. Retry your request with the ID or ARN of an inference profile that contains this model.|MODEL_NOT_FOUND",
        "400|ValidationException|The provided model identifier is invalid.|MODEL_NOT_FOUND",
        "400|ValidationException|This model doesn't support tool use.|MODEL_NOT_FOUND",
        "400|ValidationException|Input is too long for requested model.|CONTEXT_LENGTH_EXCEEDED",
        "400|ValidationException|The maximum tokens you requested exceeds the model limit of 4096.|INVALID_REQUEST",
        "400|ValidationException|messages.0.content: field required|INVALID_REQUEST",
    })
    @DisplayName("classifies each Bedrock error for the router")
    void classifiesErrors(int status, String type, String message, ProviderFailure expected) {
        stubConverse(status, json.createObjectNode().put("message", message).toString(), type);

        assertThatThrownBy(() -> complete(ChatRequest.builder().messages(List.of(ChatMessage.user("hi"))).build(), KEYS))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.failure()).isEqualTo(expected);
                    assertThat(e.httpStatus()).isEqualTo(status);
                    // The message is the adapter's own sentence, never what AWS said.
                    assertThat(e.getMessage()).doesNotContain(message);
                });
    }

    @Test
    @DisplayName("a throttle is retried on the same model, and model access not enabled moves to the next model")
    void reliabilityRules() {
        assertThat(BedrockProvider.classify(429, "ThrottlingException", "").failure().retrySameCandidate()).isTrue();
        BedrockProvider.Classified access =
                BedrockProvider.classify(403, "AccessDeniedException", "You don't have access to the model");
        assertThat(access.failure().tryNextCandidate()).isTrue();
        assertThat(access.sentence()).isEqualTo(BedrockProvider.ACCESS_NOT_ENABLED);
        // A content filter is never shopped around.
        assertThat(ProviderFailure.CONTENT_FILTERED.tryNextCandidate()).isFalse();
    }

    @Test
    @DisplayName("a guardrail stop is a content filter, not an answer")
    void guardrail() {
        stubConverse(200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"Sorry\"}]}},\"stopReason\":\"guardrail_intervened\"}", null);

        assertThatThrownBy(() -> complete(ChatRequest.builder().messages(List.of(ChatMessage.user("hi"))).build(), KEYS))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.failure()).isEqualTo(ProviderFailure.CONTENT_FILTERED));
    }

    @Test
    @DisplayName("an older credential with no region walks the provider's regions on a regional failure")
    void legacyCredentialWalksRegions() {
        server.stubFor(post(urlPathMatching("/model/.*/converse"))
                .withHeader("Authorization", containing("/us-east-1/bedrock/"))
                .willReturn(aResponse().withStatus(503).withHeader("x-amzn-ErrorType", "ServiceUnavailableException")
                        .withBody("{\"message\":\"busy\"}")));
        server.stubFor(post(urlPathMatching("/model/.*/converse"))
                .withHeader("Authorization", containing("/us-west-2/bedrock/"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"output\":{\"message\":{\"content\":[{\"text\":\"west\"}]}},\"stopReason\":\"end_turn\"}")));

        ChatResponse response = complete(
                ChatRequest.builder().messages(List.of(ChatMessage.user("hi"))).build(),
                "AKIAIOSFODNN7EXAMPLE:wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");

        assertThat(response.content()).isEqualTo("west");
        assertThat(response.providerMetadata()).containsEntry("region", "us-west-2");
    }

    @Test
    @DisplayName("no stored credential and none in the environment is a refused key, not a crash")
    void noCredential() {
        assertThatThrownBy(() -> complete(ChatRequest.builder().messages(List.of(ChatMessage.user("hi"))).build(), null))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.failure()).isEqualTo(ProviderFailure.AUTHENTICATION_FAILED));
    }

    @Test
    @DisplayName("Titan Text Embeddings v2 is called once per text, in order")
    void titanEmbeddings() throws Exception {
        server.stubFor(post(urlEqualTo("/model/amazon.titan-embed-text-v2%3A0/invoke"))
                .withRequestBody(matching(".*first.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"embedding\":[0.1,0.2,0.3],\"inputTextTokenCount\":2}")));
        server.stubFor(post(urlEqualTo("/model/amazon.titan-embed-text-v2%3A0/invoke"))
                .withRequestBody(matching(".*second.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"embedding\":[0.4,0.5,0.6],\"inputTextTokenCount\":2}")));

        List<float[]> vectors = provider.embed(
                        descriptor, model("amazon.titan-embed-text-v2:0", false), List.of("first", "second"), KEYS)
                .block(Duration.ofSeconds(10));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
        JsonNode body = sentBody();
        assertThat(body.has("inputText")).isTrue();
        assertThat(body.path("normalize").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Cohere Embed takes the batch at once and embeds a question differently from a passage")
    void cohereEmbeddings() throws Exception {
        server.stubFor(post(urlEqualTo("/model/cohere.embed-english-v3/invoke"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"embeddings\":[[1,2],[3,4]],\"id\":\"x\",\"texts\":[]}")));

        List<float[]> vectors = provider.embed(
                        descriptor, model("cohere.embed-english-v3", false), List.of("a", "b"), KEYS,
                        EmbeddingPurpose.QUERY)
                .block(Duration.ofSeconds(10));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(1)).containsExactly(3f, 4f);
        JsonNode body = sentBody();
        assertThat(body.path("input_type").asText()).isEqualTo("search_query");
        assertThat(body.path("texts")).hasSize(2);
    }

    @Test
    @DisplayName("an embedding refused for model access is classified like chat")
    void embeddingErrors() {
        server.stubFor(post(urlPathMatching("/model/.*/invoke"))
                .willReturn(aResponse().withStatus(403).withHeader("x-amzn-ErrorType", "AccessDeniedException")
                        .withBody("{\"message\":\"You don't have access to the model with the specified model ID.\"}")));

        assertThatThrownBy(() -> provider.embed(
                                descriptor, model("amazon.titan-embed-text-v2:0", false), List.of("x"), KEYS)
                        .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.failure()).isEqualTo(ProviderFailure.MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("with no stored credential and the default chain on, signs with the instance role in AIWOS_BEDROCK_REGION")
    void usesInstanceRoleWhenNothingIsStored() {
        stubConverse(200, TOOL_ANSWER, null);
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.put(urlEqualTo("/latest/api/token"))
                .willReturn(aResponse().withStatus(200).withBody("imds-token")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlEqualTo("/latest/meta-data/iam/security-credentials/"))
                .willReturn(aResponse().withStatus(200).withBody("aiwos-role")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlEqualTo("/latest/meta-data/iam/security-credentials/aiwos-role"))
                .withHeader("X-aws-ec2-metadata-token", equalTo("imds-token"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"Code":"Success","AccessKeyId":"ASIAINSTANCEROLE0001",
                         "SecretAccessKey":"instanceRoleSecretValue1234567890","Token":"role-session-token",
                         "Expiration":"2026-10-08T06:00:00Z"}
                        """)));
        Map<String, String> env = Map.of(
                "AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS", "true",
                "AIWOS_BEDROCK_REGION", "ap-southeast-2",
                "AWS_EC2_METADATA_SERVICE_ENDPOINT", server.baseUrl());
        BedrockProvider withRole = new BedrockProvider(
                WebClient.builder(), json, env, Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC));

        assertThat(withRole.hasAmbientCredential(descriptor)).isTrue();
        assertThat(provider.hasAmbientCredential(descriptor)).isFalse();

        ChatResponse response = withRole.complete(descriptor, claude, ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("Hello")))
                        .build(), null)
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        LoggedRequest sent = server.findAll(postRequestedFor(urlPathMatching("/model/.*"))).get(0);
        assertThat(sent.getHeader("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=ASIAINSTANCEROLE0001/20261008/ap-southeast-2/bedrock/aws4_request");
        assertThat(sent.getHeader("X-Amz-Security-Token")).isEqualTo("role-session-token");
        assertThat(withRole.ambientCredentials()).isPresent();
    }

    @Test
    @DisplayName("with no stored credential and the default chain off, fails as a missing key without calling AWS")
    void noAmbientWithoutTheSwitch() {
        assertThatThrownBy(() -> provider.complete(descriptor, claude, ChatRequest.builder()
                                .messages(List.of(ChatMessage.user("Hello")))
                                .build(), null)
                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).failure())
                        .isEqualTo(ProviderFailure.AUTHENTICATION_FAILED));
        assertThat(provider.ambientCredentials()).isEmpty();
    }
}
