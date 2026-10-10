// @find: model router, LLM, model providers, Amazon Bedrock, AWS, Converse API, InvokeModel embeddings, Titan, Cohere embed, SigV4, API key, region, tool use, retries, error classification, BedrockProvider
// @what: Adapter for Amazon Bedrock chat (Converse) and embeddings (InvokeModel), signed or bearer-authenticated.
// @flow: Uses AwsSigV4 and BedrockCredentials; called by ModelRouter and knowledge-service embeddings.
package os.aiworkforce.llm.provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.bedrock.AwsDefaultCredentials;
import os.aiworkforce.llm.bedrock.AwsSigV4;
import os.aiworkforce.llm.bedrock.BedrockCredentials;
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
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolNames;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;

/**
 * Amazon Bedrock, through the Converse API ({@code POST /model/{modelId}/converse}) for chat and
 * {@code InvokeModel} for embeddings.
 *
 * <p>Converse rather than {@code InvokeModel} for chat: it gives one message and tool shape across
 * every model family on Bedrock (Claude, Nova, Llama, Mistral, Cohere, Jamba, DeepSeek), so this
 * adapter needs no branch per vendor. The model id may be a foundation model id, an inference
 * profile id ({@code us.}, {@code eu.}, {@code apac.}, {@code global.}) or an ARN; it is sent as one
 * encoded path segment either way.
 *
 * <p>Requests go out on WebClient like every other adapter, authenticated with either a bearer
 * token (a Bedrock API key) or an AWS SigV4 signature ({@link AwsSigV4}) over the exact bytes sent.
 * The credential is the JSON {@link BedrockCredentials} stores, and its region decides the
 * endpoint. A credential with no region (an older {@code key:secret} one) tries the provider's
 * region list in order, as before.
 *
 * <p>Failures are classified from the {@code x-amzn-ErrorType} header and the message:
 * {@code ThrottlingException} is a throttle and retried; "model access not enabled" and "needs an
 * inference profile" are a model this account cannot use, so the router moves on to the next
 * candidate; an expired or unrecognised token is a refused key. The exception's message is this
 * adapter's own plain sentence; what AWS said is kept, truncated, only for the trace.
 */
@Component
public class BedrockProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(BedrockProvider.class);
    private static final int MAX_RAW_BODY_CHARS = 2_000;
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** Cohere's embed models take at most this many texts a call. */
    static final int COHERE_BATCH = 96;

    /** Cohere v3 refuses a text longer than this many characters. */
    static final int COHERE_MAX_CHARS = 2_048;

    /** Titan embeds one text a call; this many calls run at once. */
    private static final int TITAN_CONCURRENCY = 4;

    // Plain sentences the console can show, chosen by what AWS said.
    public static final String ACCESS_NOT_ENABLED =
            "Model access is not turned on for this model in the Amazon Bedrock console, for this region.";
    public static final String NEEDS_PROFILE =
            "This model is only offered through an inference profile in this region (an id starting us., eu."
                    + " or apac.).";
    public static final String MODEL_UNKNOWN = "Bedrock does not offer this model id in this region.";
    public static final String NO_TOOLS = "This model does not take tools through Bedrock's Converse API.";
    public static final String IAM_DENIED =
            "AWS accepted the credentials, but they are not allowed to call Bedrock. Attach a policy that"
                    + " allows bedrock:InvokeModel.";
    public static final String KEY_REFUSED = "AWS did not accept these credentials.";
    public static final String KEY_EXPIRED = "These AWS credentials have expired.";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;
    private final Map<String, String> environment;
    private final Clock clock;

    /**
     * The AWS default credential chain (instance role, container role), used when a workspace has
     * stored no Bedrock credential. Null unless {@code AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS=true}.
     */
    private final AwsDefaultCredentials ambient;

    @Autowired
    public BedrockProvider(WebClient.Builder webClientBuilder, ObjectMapper json) {
        this(webClientBuilder, json, System.getenv(), Clock.systemUTC());
    }

    BedrockProvider(WebClient.Builder webClientBuilder, ObjectMapper json, Map<String, String> environment, Clock clock) {
        this(
                webClientBuilder,
                json,
                environment,
                clock,
                AwsDefaultCredentials.enabled(environment) ? AwsDefaultCredentials.fromEnvironment(environment, clock) : null);
    }

    BedrockProvider(
            WebClient.Builder webClientBuilder,
            ObjectMapper json,
            Map<String, String> environment,
            Clock clock,
            AwsDefaultCredentials ambient) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
        this.environment = environment;
        this.clock = clock;
        this.ambient = ambient;
    }

    /**
     * True when this server reaches Bedrock with its own AWS identity (an instance or container
     * role) where a workspace has stored no credential, so the router calls Bedrock rather than
     * skipping it for a missing key.
     */
    @Override
    public boolean hasAmbientCredential(ProviderDescriptor provider) {
        return ambient != null;
    }

    /**
     * The server's own AWS credentials, for listing Bedrock's models when the workspace has
     * stored none. Empty when the default chain is off or finds nothing.
     */
    public java.util.Optional<BedrockCredentials> ambientCredentials() {
        if (ambient == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(ambient.resolve());
        } catch (AwsDefaultCredentials.Unavailable e) {
            log.warn("No AWS credentials from the default chain: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    @Override
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.BEDROCK;
    }

    @Override
    public boolean sendsImages() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    // ---- Chat ---------------------------------------------------------------------------------

    // @find: call Bedrock chat, Converse
    @Override
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        BedrockCredentials credentials;
        try {
            credentials = credentials(credential);
        } catch (ProviderException e) {
            return Mono.error(e);
        }
        ToolNameMap names = new ToolNameMap();
        byte[] body;
        try {
            body = json.writeValueAsBytes(converseBody(model, request, names));
        } catch (Exception e) {
            return Mono.error(ProviderException.of(
                    ProviderFailure.INVALID_REQUEST, provider.id(), model.modelId(), "The request could not be built.", e));
        }
        List<String> regions = regions(provider, credentials);
        return attempt(provider, model, request, credentials, body, names, regions, 0, new ArrayList<>());
    }

    /**
     * One region, then the next only for failures that are about that region. A credential with a
     * region has exactly one; this walk is for the older credential shape that named none.
     */
    private Mono<ChatResponse> attempt(
            ProviderDescriptor provider,
            ModelSpec model,
            ChatRequest request,
            BedrockCredentials credentials,
            byte[] body,
            ToolNameMap names,
            List<String> regions,
            int index,
            List<String> failures) {
        String region = regions.get(index);
        Instant startedAt = clock.instant();
        URI uri = URI.create(runtimeBase(provider, region) + "/model/" + AwsSigV4.encode(model.modelId()) + "/converse");
        return post(uri, body, credentials, region)
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .map(node -> parse(provider, model, region, node, names, startedAt))
                .onErrorResume(error -> {
                    ProviderException classified = translate(provider, model, error);
                    failures.add(region + ": " + classified.failure().name());
                    boolean regional = classified.failure() == ProviderFailure.REGION_UNAVAILABLE
                            || classified.failure() == ProviderFailure.OVERLOADED
                            || classified.failure() == ProviderFailure.MODEL_NOT_FOUND
                            || classified.failure() == ProviderFailure.SERVER_ERROR;
                    if (regional && index + 1 < regions.size()) {
                        log.info("Bedrock {} failed in {}, trying the next region", model.modelId(), region);
                        return attempt(provider, model, request, credentials, body, names, regions, index + 1, failures);
                    }
                    return Mono.error(classified);
                });
    }

    // @find: stream Bedrock answer
    @Override
    public Flux<ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        // ConverseStream answers in AWS's binary event-stream framing, which gives the router
        // nothing it relies on. The whole answer is fetched and emitted as one terminal sequence,
        // which is honest about not streaming; supportsStreaming() says so.
        return complete(provider, model, request, credential).flatMapMany(response -> {
            List<ChatChunk> chunks = new ArrayList<>();
            if (response.content() != null) {
                chunks.add(ChatChunk.text(response.content()));
            }
            if (response.hasToolCalls()) {
                chunks.add(ChatChunk.tools(response.toolCalls()));
            }
            chunks.add(ChatChunk.terminal(response.finishReason(), response.usage()));
            return Flux.fromIterable(chunks);
        });
    }

    // @find: check Bedrock credentials work
    @Override
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        BedrockCredentials credentials;
        try {
            credentials = credentials(credential);
        } catch (ProviderException e) {
            return Mono.just(false);
        }
        String region = regions(provider, credentials).get(0);
        URI uri = URI.create(controlBase(provider, region) + "/foundation-models?byOutputModality=TEXT");
        Map<String, String> headers = credentials.authHeaders("GET", uri, new byte[0], null, clock.instant());
        return webClientBuilder.clone().build()
                .get()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .map(response -> true)
                .onErrorResume(error -> Mono.just(error instanceof WebClientResponseException response
                        && response.getStatusCode().value() != 401
                        && response.getStatusCode().value() != 403));
    }

    /** The Converse request body. Package-private so the mapping is tested without a network. */
    ObjectNode converseBody(ModelSpec model, ChatRequest request, ToolNameMap names) {
        ObjectNode body = json.createObjectNode();
        boolean tools = request.usesTools() && model.supportsTools();

        String system = request.systemPrompt();
        if (system != null && !system.isBlank()) {
            body.putArray("system").addObject().put("text", system);
        }

        List<Turn> turns = new ArrayList<>();
        for (ChatMessage message : request.conversation()) {
            if (message.role() == ChatMessage.Role.SYSTEM) {
                continue;
            }
            ArrayNode blocks = json.createArrayNode();
            String role;
            if (message.role() == ChatMessage.Role.TOOL) {
                role = "user";
                String result = message.content() == null || message.content().isBlank()
                        ? "(no output)"
                        : message.content();
                if (tools) {
                    ObjectNode toolResult = blocks.addObject().putObject("toolResult");
                    toolResult.put("toolUseId", message.toolCallId());
                    toolResult.putArray("content").addObject().put("text", result);
                } else {
                    // Converse refuses toolResult blocks without a toolConfig, so a history that
                    // used tools is told as text to a request that offers none.
                    String name = message.name() == null ? "a tool" : message.name();
                    blocks.addObject().put("text", "[Result from " + name + "]\n" + result);
                }
            } else {
                role = message.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
                if (message.content() != null && !message.content().isBlank()) {
                    blocks.addObject().put("text", message.content());
                }
                for (ImagePart image : message.images()) {
                    String format = imageFormat(image.mediaType());
                    if (format == null) {
                        blocks.addObject().put("text", image.unreadableNote(model.displayName()));
                        continue;
                    }
                    ObjectNode picture = blocks.addObject().putObject("image");
                    picture.put("format", format);
                    picture.putObject("source").put("bytes", image.base64Data());
                }
                for (ToolCall call : message.toolCalls()) {
                    if (tools) {
                        ObjectNode use = blocks.addObject().putObject("toolUse");
                        use.put("toolUseId", call.id());
                        use.put("name", names.wire(call.name()));
                        use.set("input", objectOf(call.argumentsJson()));
                    } else {
                        blocks.addObject()
                                .put("text", "[Used " + call.name() + " with " + call.argumentsJson() + "]");
                    }
                }
            }
            if (blocks.isEmpty()) {
                blocks.addObject().put("text", "(no content)");
            }
            // Converse wants turns to alternate: tool results that follow one assistant turn go in
            // one user turn, and two user turns in a row are joined.
            Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
            if (last != null && last.role.equals(role)) {
                last.blocks.addAll(blocks);
            } else {
                turns.add(new Turn(role, blocks));
            }
        }
        if (turns.isEmpty() || !turns.get(0).role.equals("user")) {
            ArrayNode opener = json.createArrayNode();
            opener.addObject().put("text", "(conversation continues)");
            turns.add(0, new Turn("user", opener));
        }
        ArrayNode messages = body.putArray("messages");
        for (Turn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.role);
            node.set("content", turn.blocks);
        }

        ObjectNode inference = body.putObject("inferenceConfig");
        int ceiling = Math.max(1, model.maxOutputTokens());
        inference.put(
                "maxTokens",
                Math.max(1, request.maxOutputTokens() != null
                        ? Math.min(request.maxOutputTokens(), ceiling)
                        : Math.min(4096, ceiling)));
        if (request.temperature() != null) {
            inference.put("temperature", request.temperature());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = inference.putArray("stopSequences");
            request.stopSequences().stream().limit(4).forEach(stops::add);
        }

        if (tools) {
            ArrayNode list = body.putObject("toolConfig").putArray("tools");
            for (ToolSpec tool : request.tools()) {
                ObjectNode spec = list.addObject().putObject("toolSpec");
                spec.put("name", names.wire(tool.name()));
                if (tool.description() != null && !tool.description().isBlank()) {
                    spec.put("description", tool.description());
                }
                JsonNode schema = objectOf(tool.parametersJson());
                if (!schema.has("type")) {
                    ((ObjectNode) schema).put("type", "object");
                }
                spec.putObject("inputSchema").set("json", schema);
            }
        }
        return body;
    }

    private record Turn(String role, ArrayNode blocks) {}

    ChatResponse parse(
            ProviderDescriptor provider,
            ModelSpec model,
            String region,
            JsonNode node,
            ToolNameMap names,
            Instant startedAt) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JsonNode content = node.path("output").path("message").path("content");
        if (!content.isArray()) {
            throw new ProviderException(
                    ProviderFailure.MALFORMED_RESPONSE,
                    provider.id(),
                    model.modelId(),
                    "Bedrock answered without a message.",
                    200,
                    null,
                    truncate(node.toString()),
                    null);
        }
        for (JsonNode block : content) {
            if (block.has("text")) {
                text.append(block.path("text").asText(""));
            } else if (block.has("toolUse")) {
                JsonNode use = block.path("toolUse");
                JsonNode input = use.path("input");
                calls.add(new ToolCall(
                        use.path("toolUseId").asText(""),
                        names.platform(use.path("name").asText("")),
                        input.isMissingNode() || input.isNull() ? "{}" : input.toString()));
            }
            // reasoningContent (DeepSeek R1, Claude thinking) is the model's working, not its answer.
        }

        String stopReason = node.path("stopReason").asText(null);
        if ("guardrail_intervened".equalsIgnoreCase(stopReason) || "content_filtered".equalsIgnoreCase(stopReason)) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED,
                    provider.id(),
                    model.modelId(),
                    "A Bedrock guardrail or content filter stopped this request.");
        }

        JsonNode usage = node.path("usage");
        int cacheRead = usage.path("cacheReadInputTokens").asInt(0);
        int cacheWrite = usage.path("cacheWriteInputTokens").asInt(0);
        TokenUsage tokens = usage.isMissingNode()
                ? TokenUsage.NONE
                : new TokenUsage(
                        usage.path("inputTokens").asInt(0) + cacheRead + cacheWrite,
                        cacheRead,
                        usage.path("outputTokens").asInt(0),
                        0);

        return new ChatResponse(
                text.isEmpty() ? null : text.toString(),
                calls,
                mapStopReason(stopReason, !calls.isEmpty()),
                tokens,
                provider.id(),
                model.modelId(),
                Duration.between(startedAt, clock.instant()),
                List.of(),
                Map.of("region", region));
    }

    static FinishReason mapStopReason(String raw, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        if (raw == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "end_turn" -> FinishReason.STOP;
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "stop_sequence" -> FinishReason.STOP_SEQUENCE;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "content_filtered", "guardrail_intervened" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    // ---- Embeddings ---------------------------------------------------------------------------

    // @find: Bedrock embeddings, knowledge base embeddings
    @Override
    public Mono<List<float[]>> embed(
            ProviderDescriptor provider, ModelSpec model, List<String> inputs, String credential) {
        return embed(provider, model, inputs, credential, EmbeddingPurpose.PASSAGE);
    }

    // @find: Bedrock embeddings with purpose, query vs passage
    /**
     * Titan Text Embeddings (v1, v2) take one text a call; Cohere Embed (v3, v4) takes up to 96 and
     * embeds questions and passages differently. Both are reached with InvokeModel.
     */
    @Override
    public Mono<List<float[]>> embed(
            ProviderDescriptor provider,
            ModelSpec model,
            List<String> inputs,
            String credential,
            EmbeddingPurpose purpose) {
        if (inputs.isEmpty()) {
            return Mono.just(List.of());
        }
        BedrockCredentials credentials;
        try {
            credentials = credentials(credential);
        } catch (ProviderException e) {
            return Mono.error(e);
        }
        String region = regions(provider, credentials).get(0);
        String id = model.modelId().toLowerCase(Locale.ROOT);
        URI uri = URI.create(runtimeBase(provider, region) + "/model/" + AwsSigV4.encode(model.modelId()) + "/invoke");

        Mono<List<float[]>> call;
        if (id.contains("cohere.embed")) {
            boolean v3 = !id.contains("embed-v4");
            List<List<String>> batches = new ArrayList<>();
            for (int start = 0; start < inputs.size(); start += COHERE_BATCH) {
                batches.add(inputs.subList(start, Math.min(inputs.size(), start + COHERE_BATCH)));
            }
            call = Flux.fromIterable(batches)
                    .concatMap(batch -> post(uri, bytes(cohereBody(batch, purpose, v3)), credentials, region)
                            .map(node -> cohereVectors(provider, model, node, batch.size())))
                    .collectList()
                    .map(parts -> parts.stream().flatMap(List::stream).toList());
        } else {
            boolean v2 = id.contains("titan-embed-text-v2");
            call = Flux.fromIterable(inputs)
                    .flatMapSequential(
                            text -> post(uri, bytes(titanBody(text, v2)), credentials, region)
                                    .map(node -> titanVector(provider, model, node)),
                            TITAN_CONCURRENCY)
                    .collectList();
        }
        return call.onErrorMap(error -> translate(provider, model, error));
    }

    ObjectNode titanBody(String text, boolean v2) {
        ObjectNode body = json.createObjectNode();
        body.put("inputText", text == null || text.isBlank() ? " " : text);
        if (v2) {
            body.put("normalize", true);
        }
        return body;
    }

    ObjectNode cohereBody(List<String> texts, EmbeddingPurpose purpose, boolean v3) {
        ObjectNode body = json.createObjectNode();
        ArrayNode list = body.putArray("texts");
        for (String text : texts) {
            String value = text == null || text.isBlank() ? " " : text;
            list.add(v3 && value.length() > COHERE_MAX_CHARS ? value.substring(0, COHERE_MAX_CHARS) : value);
        }
        body.put("input_type", purpose == EmbeddingPurpose.QUERY ? "search_query" : "search_document");
        body.put("truncate", "END");
        if (!v3) {
            body.putArray("embedding_types").add("float");
        }
        return body;
    }

    private float[] titanVector(ProviderDescriptor provider, ModelSpec model, JsonNode node) {
        JsonNode vector = node.path("embedding");
        if (!vector.isArray() || vector.isEmpty()) {
            throw malformed(provider, model, "Bedrock answered without an embedding.");
        }
        return floats(vector);
    }

    private List<float[]> cohereVectors(ProviderDescriptor provider, ModelSpec model, JsonNode node, int expected) {
        JsonNode embeddings = node.path("embeddings");
        // v3 answers {"embeddings": [[...]]}; with embedding_types, {"embeddings": {"float": [[...]]}}.
        JsonNode rows = embeddings.isObject() ? embeddings.path("float") : embeddings;
        if (!rows.isArray() || rows.size() != expected) {
            throw malformed(provider, model, "Bedrock returned an unexpected number of embeddings.");
        }
        List<float[]> out = new ArrayList<>(expected);
        rows.forEach(row -> out.add(floats(row)));
        return out;
    }

    private static float[] floats(JsonNode vector) {
        float[] out = new float[vector.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) vector.get(i).asDouble();
        }
        return out;
    }

    private static ProviderException malformed(ProviderDescriptor provider, ModelSpec model, String message) {
        return new ProviderException(
                ProviderFailure.MALFORMED_RESPONSE, provider.id(), model.modelId(), message, 200, null, null, null);
    }

    // ---- Transport ----------------------------------------------------------------------------

    private Mono<JsonNode> post(URI uri, byte[] body, BedrockCredentials credentials, String region) {
        BedrockCredentials inRegion = credentials.region() != null && credentials.region().equals(region)
                ? credentials
                : new BedrockCredentials(
                        credentials.accessKeyId(),
                        credentials.secretAccessKey(),
                        credentials.sessionToken(),
                        credentials.apiKey(),
                        region);
        return Mono.defer(() -> {
            Map<String, String> headers =
                    inRegion.authHeaders("POST", uri, body, MediaType.APPLICATION_JSON_VALUE, clock.instant());
            return webClientBuilder.clone().build()
                    .post()
                    .uri(uri)
                    .headers(h -> headers.forEach(h::set))
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class);
        });
    }

    private byte[] bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (Exception e) {
            return node.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private BedrockCredentials credentials(String credential) {
        if (credential == null || credential.isBlank()) {
            if (ambient != null) {
                try {
                    return ambient.resolve();
                } catch (AwsDefaultCredentials.Unavailable e) {
                    throw ProviderException.of(
                            e.isTransient() ? ProviderFailure.NETWORK_ERROR : ProviderFailure.AUTHENTICATION_FAILED,
                            "bedrock",
                            "",
                            e.getMessage());
                }
            }
            BedrockCredentials fromEnv = BedrockCredentials.fromEnvironment(environment);
            if (fromEnv != null) {
                return fromEnv;
            }
            throw ProviderException.of(
                    ProviderFailure.AUTHENTICATION_FAILED, "bedrock", "", "No AWS credentials are stored for Bedrock.");
        }
        try {
            return BedrockCredentials.parse(credential);
        } catch (BedrockCredentials.Invalid e) {
            throw ProviderException.of(ProviderFailure.AUTHENTICATION_FAILED, "bedrock", "", e.getMessage());
        }
    }

    private static List<String> regions(ProviderDescriptor provider, BedrockCredentials credentials) {
        if (credentials.region() != null) {
            return List.of(credentials.region());
        }
        return provider.regions().isEmpty() ? List.of(BedrockCredentials.DEFAULT_REGION) : provider.regions();
    }

    /** A base URL on the provider row overrides AWS's endpoint: a VPC endpoint, a proxy, a test stub. */
    private static String runtimeBase(ProviderDescriptor provider, String region) {
        String base = provider.baseUrl() == null ? "" : provider.baseUrl().strip();
        return base.isEmpty() ? BedrockCredentials.runtimeEndpoint(region) : stripSlash(base);
    }

    private static String controlBase(ProviderDescriptor provider, String region) {
        String base = provider.baseUrl() == null ? "" : provider.baseUrl().strip();
        return base.isEmpty() ? BedrockCredentials.controlEndpoint(region) : stripSlash(base);
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String imageFormat(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpeg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> null;
        };
    }

    private JsonNode objectOf(String jsonText) {
        try {
            JsonNode node = jsonText == null || jsonText.isBlank() ? null : json.readTree(jsonText);
            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    // ---- Failures -----------------------------------------------------------------------------

    ProviderException translate(ProviderDescriptor provider, ModelSpec model, Throwable error) {
        if (error instanceof ProviderException already) {
            return already;
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return new ProviderException(
                    ProviderFailure.TIMEOUT, provider.id(), model.modelId(),
                    "Bedrock did not answer inside the deadline.", null, null, null, error);
        }
        if (error instanceof WebClientRequestException) {
            return new ProviderException(
                    ProviderFailure.NETWORK_ERROR, provider.id(), model.modelId(),
                    "Bedrock could not be reached.", null, null, null, error);
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            String body = response.getResponseBodyAsString();
            String type = errorType(response.getHeaders().getFirst("x-amzn-ErrorType"), body);
            String message = errorMessage(body);
            Classified classified = classify(status, type, message);
            Duration retryAfter = null;
            String header = response.getHeaders().getFirst("retry-after");
            if (header != null) {
                try {
                    retryAfter = Duration.ofSeconds(Long.parseLong(header.trim()));
                } catch (NumberFormatException ignored) {
                    retryAfter = null;
                }
            }
            return new ProviderException(
                    classified.failure(),
                    provider.id(),
                    model.modelId(),
                    classified.sentence(),
                    status,
                    retryAfter,
                    truncate(type.isEmpty() ? body : type + ": " + body),
                    response);
        }
        return new ProviderException(
                ProviderFailure.UNKNOWN, provider.id(), model.modelId(), "The Bedrock call failed.", null, null, null,
                error);
    }

    /** A failure and the adapter's own sentence for it. */
    public record Classified(ProviderFailure failure, String sentence) {}

    // @find: classify Bedrock error, throttling, access denied, validation
    /**
     * What one Bedrock error means for the router.
     *
     * @param type the AWS error type, without its namespace ({@code ThrottlingException})
     * @param message what AWS said, used only to choose; never shown
     */
    public static Classified classify(int status, String type, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String kind = type == null ? "" : type;
        switch (kind) {
            case "ThrottlingException", "TooManyRequestsException" -> {
                return new Classified(ProviderFailure.RATE_LIMITED, "Bedrock is limiting requests right now.");
            }
            case "ServiceQuotaExceededException" -> {
                return new Classified(
                        ProviderFailure.RATE_LIMITED, "The account's Bedrock quota for this model is used up for now.");
            }
            case "ModelNotReadyException", "ServiceUnavailableException" -> {
                return new Classified(ProviderFailure.OVERLOADED, "The model is not ready on Bedrock yet.");
            }
            case "InternalServerException", "ModelErrorException", "ModelStreamErrorException" -> {
                return new Classified(ProviderFailure.SERVER_ERROR, "Bedrock had a fault answering.");
            }
            case "ModelTimeoutException" -> {
                return new Classified(ProviderFailure.TIMEOUT, "The model took too long to answer on Bedrock.");
            }
            case "ResourceNotFoundException" -> {
                return new Classified(ProviderFailure.MODEL_NOT_FOUND, MODEL_UNKNOWN);
            }
            case "ExpiredTokenException" -> {
                return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_EXPIRED);
            }
            case "UnrecognizedClientException", "InvalidSignatureException", "IncompleteSignatureException",
                    "InvalidClientTokenId", "MissingAuthenticationTokenException", "SignatureDoesNotMatch" -> {
                return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_REFUSED);
            }
            case "AccessDeniedException" -> {
                return accessDenied(lower);
            }
            case "ValidationException" -> {
                return validation(lower);
            }
            default -> {
                // No type: decide from the status, then the words.
            }
        }
        if (lower.contains("expired")) {
            return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_EXPIRED);
        }
        return switch (status) {
            case 400 -> validation(lower);
            case 401 -> new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_REFUSED);
            case 403 -> accessDenied(lower);
            case 404 -> new Classified(ProviderFailure.MODEL_NOT_FOUND, MODEL_UNKNOWN);
            case 408 -> new Classified(ProviderFailure.TIMEOUT, "The model took too long to answer on Bedrock.");
            case 424 -> new Classified(ProviderFailure.SERVER_ERROR, "Bedrock had a fault answering.");
            case 429 -> new Classified(ProviderFailure.RATE_LIMITED, "Bedrock is limiting requests right now.");
            case 503 -> new Classified(ProviderFailure.OVERLOADED, "Bedrock is busy right now.");
            default -> status >= 500
                    ? new Classified(ProviderFailure.SERVER_ERROR, "Bedrock had a fault answering.")
                    : new Classified(ProviderFailure.UNKNOWN, "The Bedrock call failed.");
        };
    }

    private static Classified accessDenied(String lower) {
        if (lower.contains("expired")) {
            return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_EXPIRED);
        }
        if (lower.contains("authentication failed") || lower.contains("api key") || lower.contains("bearer")
                || lower.contains("security token") || lower.contains("invalid")) {
            return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_REFUSED);
        }
        // "You don't have access to the model with the specified model ID." The credentials worked;
        // this one model is not enabled, so the next candidate is tried.
        if (lower.contains("access to the model") || lower.contains("model access")
                || lower.contains("not have access to the model") || lower.contains("aws marketplace")) {
            return new Classified(ProviderFailure.MODEL_NOT_FOUND, ACCESS_NOT_ENABLED);
        }
        return new Classified(ProviderFailure.AUTHORISATION_FAILED, IAM_DENIED);
    }

    private static Classified validation(String lower) {
        if (lower.contains("security token") && lower.contains("expired")) {
            return new Classified(ProviderFailure.AUTHENTICATION_FAILED, KEY_EXPIRED);
        }
        if (lower.contains("inference profile") || lower.contains("on-demand throughput")) {
            return new Classified(ProviderFailure.MODEL_NOT_FOUND, NEEDS_PROFILE);
        }
        if (lower.contains("model identifier is invalid") || lower.contains("end of its life")
                || lower.contains("model is not supported in this region") || lower.contains("legacy")) {
            return new Classified(ProviderFailure.MODEL_NOT_FOUND, MODEL_UNKNOWN);
        }
        if ((lower.contains("tool") && (lower.contains("not support") || lower.contains("doesn't support")))) {
            return new Classified(ProviderFailure.MODEL_NOT_FOUND, NO_TOOLS);
        }
        if (lower.contains("maxtokens") || lower.contains("max_tokens") || lower.contains("maximum tokens you requested")) {
            return new Classified(ProviderFailure.INVALID_REQUEST, "The answer length asked for is more than the model allows.");
        }
        if (lower.contains("too long") || lower.contains("too many tokens") || lower.contains("context window")
                || lower.contains("context length") || (lower.contains("exceed") && lower.contains("token"))) {
            return new Classified(ProviderFailure.CONTEXT_LENGTH_EXCEEDED, "The conversation is longer than the model's window.");
        }
        return new Classified(ProviderFailure.INVALID_REQUEST, "Bedrock refused the request as malformed.");
    }

    /** {@code ValidationException:http://internal.amazon.com/coral/...} or a body's {@code __type}, reduced to the name. */
    static String errorType(String header, String body) {
        String raw = header;
        if (raw == null || raw.isBlank()) {
            raw = "";
            try {
                JsonNode node = body == null || body.isBlank() ? null : new ObjectMapper().readTree(body);
                if (node != null) {
                    raw = node.path("__type").asText(node.path("code").asText(""));
                }
            } catch (Exception ignored) {
                raw = "";
            }
        }
        int colon = raw.indexOf(':');
        if (colon >= 0) {
            raw = raw.substring(0, colon);
        }
        int hash = raw.lastIndexOf('#');
        if (hash >= 0) {
            raw = raw.substring(hash + 1);
        }
        return raw.strip();
    }

    static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            String message = node.path("message").asText("");
            return message.isEmpty() ? node.path("Message").asText(body) : message;
        } catch (Exception e) {
            return body;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RAW_BODY_CHARS ? value : value.substring(0, MAX_RAW_BODY_CHARS) + "…";
    }

    // ---- Tool names ---------------------------------------------------------------------------

    /**
     * Tool names as Bedrock takes them: {@code [a-zA-Z0-9_-]{1,64}}. The platform's
     * {@code server.tool} is sent as {@code server__tool} ({@link ToolNames}); a name that is still
     * not valid (too long, or with other characters) is shortened with a hash, and the mapping is
     * kept for the request so a call comes back under the name the platform knows.
     */
    static final class ToolNameMap {
        private final Map<String, String> toPlatform = new HashMap<>();
        private final Map<String, String> toWire = new HashMap<>();

        String wire(String name) {
            String platform = name == null ? "" : name;
            return toWire.computeIfAbsent(platform, key -> {
                String wire = ToolNames.toWire(key);
                if (!TOOL_NAME.matcher(wire).matches()) {
                    String cleaned = wire.replaceAll("[^a-zA-Z0-9_-]", "_");
                    if (cleaned.isEmpty()) {
                        cleaned = "tool";
                    }
                    String hash = HexFormat.of()
                            .formatHex(java.util.Arrays.copyOf(sha(key), 4));
                    wire = (cleaned.length() > 55 ? cleaned.substring(0, 55) : cleaned) + "_" + hash;
                }
                toPlatform.put(wire, key);
                return wire;
            });
        }

        String platform(String wire) {
            String known = toPlatform.get(wire);
            return known != null ? known : ToolNames.fromWire(wire);
        }

        private static byte[] sha(String value) {
            try {
                return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new byte[4];
            }
        }
    }
}
