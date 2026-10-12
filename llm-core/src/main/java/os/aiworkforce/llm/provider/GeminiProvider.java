// @find: model router, LLM, model providers, Google Gemini, Generative Language API, roles, function calls, embeddings, GeminiProvider
// @what: Adapter for Google's Gemini Generative Language API: contents and parts, function calls, safety block detection, streaming, batch embeddings and failure classification.
// @flow: Spring ChatProvider of kind GEMINI; ModelRouter calls complete/stream/healthCheck and knowledge-service calls embed; WebClient posts to :generateContent, :streamGenerateContent and :batchEmbedContents with the key in a header; errors are translated to a classified ProviderException for the router.
package os.aiworkforce.llm.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolNames;
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;

/**
 * Google's Generative Language API.
 *
 * <p>Gemini diverges from the others in ways that matter to the translation. Roles are
 * {@code user} and {@code model} rather than user and assistant. Content is a list of parts, and
 * a function call is a part rather than a sibling field. The system prompt is
 * {@code systemInstruction}. Tool arguments arrive as a parsed object rather than a JSON string.
 *
 * <p>Most importantly, Gemini can return a 200 with no candidate at all when its safety filters
 * block a prompt - the refusal is in {@code promptFeedback.blockReason}. Reading only the
 * candidate list would surface that as an empty answer with no explanation, so it is checked
 * first and raised as {@code CONTENT_FILTERED}.
 */
@Component
public class GeminiProvider implements ChatProvider {

    private static final int MAX_RAW_BODY_CHARS = 2_000;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    /**
     * Creates the adapter with the shared reactive client builder and JSON mapper supplied by Spring.
     * No network activity happens here; a client is built per call.
     */
    public GeminiProvider(WebClient.Builder webClientBuilder, ObjectMapper json) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override
    /**
     * Identifies this adapter to the provider registry, which selects it for provider rows of kind
     * {@code GEMINI}. Image parts are not sent by this adapter.
     */
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.GEMINI;
    }

    // @find: call Gemini, chat completion
    @Override
    /**
     * Sends one request to {@code :generateContent}.
     *
     * <p>Flow: called by the model router; builds the body, applies the request deadline (120 seconds by
     * default), parses the first candidate into text and function calls, and maps any failure to a
     * classified {@link ProviderException}. One HTTP call; retries and failover belong to the router.
     */
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        Instant startedAt = Instant.now();
        return client(provider, credential)
                .post()
                .uri("/v1beta/models/{model}:generateContent", model.modelId())
                .bodyValue(buildBody(model, request))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .map(node -> parse(provider, model, node, startedAt))
                .onErrorMap(error -> translate(provider, model, error));
    }

    // @find: stream Gemini answer
    @Override
    /**
     * Streams from {@code :streamGenerateContent} with server-sent events ({@code alt=sse}).
     *
     * <p>Text parts are emitted as they arrive; function calls and the finish reason are held in a
     * {@code StreamState} and emitted, with a terminal chunk, when the stream ends. A stream with no
     * finish reason ends as {@code INCOMPLETE}, which lets the router retry without streaming. Unlike
     * {@link #complete}, a blocked prompt is not detected in the stream and yields an empty answer.
     */
    public Flux<ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        StreamState state = new StreamState();
        return client(provider, credential)
                .post()
                .uri(builder -> builder.path("/v1beta/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .build(model.modelId()))
                .bodyValue(buildBody(model, request))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .concatMap(line -> handleChunk(line, state))
                .concatWith(Flux.defer(() -> Flux.fromIterable(state.drain())))
                .onErrorMap(error -> translate(provider, model, error));
    }

    // @find: Gemini embeddings, knowledge base embeddings
    @Override
    /**
     * Embeds the texts with one {@code :batchEmbedContents} call.
     *
     * <p>Vectors are returned in response order. No purpose is sent, so queries and passages are embedded
     * alike, and no explicit deadline is set. The number of vectors is not checked against the number of
     * inputs. Used by knowledge-service. Failures are classified by {@link #translate}.
     */
    public Mono<List<float[]>> embed(
            ProviderDescriptor provider, ModelSpec model, List<String> inputs, String credential) {
        ObjectNode body = json.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        inputs.forEach(text -> {
            ObjectNode entry = requests.addObject();
            entry.put("model", "models/" + model.modelId());
            entry.putObject("content").putArray("parts").addObject().put("text", text);
        });
        return client(provider, credential)
                .post()
                .uri("/v1beta/models/{model}:batchEmbedContents", model.modelId())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(node -> {
                    List<float[]> vectors = new ArrayList<>();
                    for (JsonNode embedding : node.path("embeddings")) {
                        JsonNode values = embedding.path("values");
                        float[] vector = new float[values.size()];
                        for (int i = 0; i < values.size(); i++) {
                            vector[i] = (float) values.get(i).asDouble();
                        }
                        vectors.add(vector);
                    }
                    return vectors;
                })
                .onErrorMap(error -> translate(provider, model, error));
    }

    // @find: check Gemini key works
    @Override
    /**
     * Checks the key with {@code GET /v1beta/models} and a ten second deadline. Never fails: any error
     * reports {@code false}.
     */
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        return client(provider, credential)
                .get()
                .uri("/v1beta/models")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(node -> true)
                .onErrorResume(error -> Mono.just(false));
    }

    /**
     * Builds the request body shared by chat and streaming.
     *
     * <p>The system prompt becomes {@code systemInstruction}; tool results become user-role
     * {@code functionResponse} parts; assistant tool calls become {@code functionCall} parts. Output
     * limit, temperature, stop sequences, JSON mode and tool declarations are sent only when the request
     * and the model allow them. An unreadable schema or argument text is replaced by an empty one rather
     * than failing the request.
     */
    private ObjectNode buildBody(ModelSpec model, ChatRequest request) {
        ObjectNode body = json.createObjectNode();

        String system = request.systemPrompt();
        if (system != null) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }

        ArrayNode contents = body.putArray("contents");
        for (ChatMessage message : request.conversation()) {
            ObjectNode node = contents.addObject();
            if (message.role() == ChatMessage.Role.TOOL) {
                // A tool result is a user-role part carrying functionResponse, not its own role.
                node.put("role", "user");
                ObjectNode part = node.putArray("parts").addObject();
                ObjectNode response = part.putObject("functionResponse");
                response.put("name", message.name() == null ? "tool" : ToolNames.toWire(message.name()));
                ObjectNode responseBody = response.putObject("response");
                try {
                    responseBody.set("result", json.readTree(message.content()));
                } catch (Exception e) {
                    responseBody.put("result", message.content() == null ? "" : message.content());
                }
                continue;
            }
            node.put("role", message.role() == ChatMessage.Role.ASSISTANT ? "model" : "user");
            ArrayNode parts = node.putArray("parts");
            if (message.content() != null && !message.content().isBlank()) {
                parts.addObject().put("text", message.content());
            }
            boolean first = true;
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callPart = parts.addObject();
                ObjectNode functionCall = callPart.putObject("functionCall");
                functionCall.put("name", ToolNames.toWire(call.name()));
                // Gemini 3 checks that each turn's first function call carries the signature it sent
                // with it, and refuses the request otherwise. The real one is sent back when it is
                // known; a call it never saw (made by another model, or before a restart) gets the
                // value Google documents for that case, which skips the check.
                String signature = signatures.get(signatureKey(call.name(), call.argumentsJson()));
                if (signature == null && first && model.modelId().startsWith("gemini-3")) {
                    signature = SKIP_SIGNATURE_CHECK;
                }
                if (signature != null) {
                    callPart.put("thoughtSignature", signature);
                }
                first = false;
                try {
                    functionCall.set("args", json.readTree(call.argumentsJson()));
                } catch (Exception e) {
                    functionCall.set("args", json.createObjectNode());
                }
            }
            if (parts.isEmpty()) {
                parts.addObject().put("text", "");
            }
        }

        ObjectNode generation = body.putObject("generationConfig");
        if (request.maxOutputTokens() != null) {
            generation.put("maxOutputTokens", Math.min(request.maxOutputTokens(), model.maxOutputTokens()));
        }
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = generation.putArray("stopSequences");
            request.stopSequences().forEach(stops::add);
        }
        if (request.jsonMode() && model.supportsJsonMode()) {
            generation.put("responseMimeType", "application/json");
            if (request.jsonSchema() != null) {
                try {
                    generation.set("responseSchema", json.readTree(request.jsonSchema()));
                } catch (Exception e) {
                    generation.remove("responseSchema");
                }
            }
        }

        if (request.usesTools() && model.supportsTools()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            request.tools().forEach(tool -> {
                ObjectNode node = declarations.addObject();
                node.put("name", ToolNames.toWire(tool.name()));
                node.put("description", tool.description());
                try {
                    node.set("parameters", json.readTree(tool.parametersJson()));
                } catch (Exception e) {
                    node.set("parameters", json.createObjectNode().put("type", "OBJECT"));
                }
            });
        }
        return body;
    }

    /**
     * Converts a response into a {@link ChatResponse}.
     *
     * <p>Raises {@code CONTENT_FILTERED} for a blocked prompt (checked first) or a safety finish, and
     * {@code MALFORMED_RESPONSE} when no candidate exists. Gemini returns no tool call identifiers, so
     * they are generated as {@code call_N}.
     */
    private ChatResponse parse(ProviderDescriptor provider, ModelSpec model, JsonNode node, Instant startedAt) {
        // Checked before the candidates: a blocked prompt yields a 200 with none at all.
        String blockReason = node.path("promptFeedback").path("blockReason").asText(null);
        if (blockReason != null && !blockReason.isBlank()) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED,
                    provider.id(),
                    model.modelId(),
                    "The provider's safety system declined this request (" + blockReason + ").");
        }

        JsonNode candidate = node.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            throw new ProviderException(
                    ProviderFailure.MALFORMED_RESPONSE,
                    provider.id(),
                    model.modelId(),
                    "The provider returned no candidates.",
                    null,
                    null,
                    truncate(node.toString()),
                    null);
        }

        String finishReasonRaw = candidate.path("finishReason").asText(null);
        if ("SAFETY".equals(finishReasonRaw) || "PROHIBITED_CONTENT".equals(finishReasonRaw)) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED,
                    provider.id(),
                    model.modelId(),
                    "The provider's safety system stopped the answer.");
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        int callIndex = 0;
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text")) {
                text.append(part.path("text").asText(""));
            }
            if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String name = ToolNames.fromWire(call.path("name").asText(""));
                rememberSignature(part, name, call.path("args").toString());
                calls.add(new ToolCall("call_" + callIndex++, name, call.path("args").toString()));
            }
        }

        JsonNode usage = node.path("usageMetadata");
        TokenUsage tokens = new TokenUsage(
                usage.path("promptTokenCount").asInt(0),
                usage.path("cachedContentTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("thoughtsTokenCount").asInt(0));

        return new ChatResponse(
                text.isEmpty() ? null : text.toString(),
                calls,
                mapFinishReason(finishReasonRaw, !calls.isEmpty()),
                tokens,
                provider.id(),
                model.modelId(),
                Duration.between(startedAt, Instant.now()),
                List.of(),
                Map.of());
    }

    // Maps Gemini finish reasons to {@link FinishReason}; the presence of tool calls always wins.
    private FinishReason mapFinishReason(String raw, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        if (raw == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (raw) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.LENGTH;
            case "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    /**
     * Accumulates function calls, the finish reason and usage across the chunks of one streamed answer.
     */
    private static final class StreamState {
        private final List<ToolCall> calls = new ArrayList<>();
        private FinishReason finishReason;
        private TokenUsage usage = TokenUsage.NONE;
        private boolean drained;

        // Emits, once only, the collected tool calls followed by the terminal chunk.
        List<ChatChunk> drain() {
            if (drained) {
                return List.of();
            }
            drained = true;
            List<ChatChunk> chunks = new ArrayList<>(2);
            if (!calls.isEmpty()) {
                chunks.add(ChatChunk.tools(List.copyOf(calls)));
            }
            chunks.add(ChatChunk.terminal(finishReason != null ? finishReason : FinishReason.INCOMPLETE, usage));
            return chunks;
        }
    }

    /**
     * Interprets one streamed chunk: emits its text, records function calls, the finish reason and usage
     * (when it carries a candidate token count). An unreadable chunk is skipped so that the rest of the
     * answer survives.
     */
    private Flux<ChatChunk> handleChunk(String line, StreamState state) {
        if (line == null || line.isBlank()) {
            return Flux.empty();
        }
        try {
            JsonNode node = json.readTree(line);
            JsonNode candidate = node.path("candidates").path(0);
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.has("text")) {
                    text.append(part.path("text").asText(""));
                }
                if (part.has("functionCall")) {
                    JsonNode call = part.path("functionCall");
                    String name = ToolNames.fromWire(call.path("name").asText(""));
                    rememberSignature(part, name, call.path("args").toString());
                    state.calls.add(new ToolCall("call_" + state.calls.size(), name, call.path("args").toString()));
                }
            }
            String finish = candidate.path("finishReason").asText(null);
            if (finish != null && !finish.isBlank()) {
                state.finishReason = mapFinishReason(finish, !state.calls.isEmpty());
            }
            JsonNode usage = node.path("usageMetadata");
            if (usage.has("candidatesTokenCount")) {
                state.usage = new TokenUsage(
                        usage.path("promptTokenCount").asInt(0),
                        usage.path("cachedContentTokenCount").asInt(0),
                        usage.path("candidatesTokenCount").asInt(0),
                        usage.path("thoughtsTokenCount").asInt(0));
            }
            return text.isEmpty() ? Flux.empty() : Flux.just(ChatChunk.text(text.toString()));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    /**
     * Google takes the key as a header rather than a bearer token.
     *
     * <p>It also accepts {@code ?key=}, which is deliberately not used: a key in a query string
     * is logged by every proxy and access log between here and Google.
     */
    private WebClient client(ProviderDescriptor provider, String credential) {
        WebClient.Builder builder = webClientBuilder.clone().baseUrl(provider.baseUrl());
        if (credential != null && !credential.isBlank()) {
            builder.defaultHeader("x-goog-api-key", credential);
        }
        provider.defaultHeaders().forEach(builder::defaultHeader);
        return builder.build();
    }

    /**
     * Turns anything thrown into a classified {@link ProviderException}, which the router uses to retry,
     * fail over or stop.
     *
     * <p>Timeouts and connection errors are distinguished; HTTP statuses map to failure classes, with
     * body text used to tell an invalid key, an oversized prompt and a billing or quota problem from
     * ordinary rejection or throttling. No Retry-After value is read. Anything else becomes {@code UNKNOWN}.
     */
    private Throwable translate(ProviderDescriptor provider, ModelSpec model, Throwable error) {
        if (error instanceof ProviderException) {
            return error;
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return new ProviderException(
                    ProviderFailure.TIMEOUT,
                    provider.id(),
                    model.modelId(),
                    "The provider did not answer inside the deadline.",
                    null,
                    null,
                    null,
                    error);
        }
        if (error instanceof WebClientRequestException) {
            return new ProviderException(
                    ProviderFailure.NETWORK_ERROR,
                    provider.id(),
                    model.modelId(),
                    "The provider could not be reached.",
                    null,
                    null,
                    null,
                    error);
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            String body = truncate(response.getResponseBodyAsString());
            String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
            ProviderFailure failure =
                    switch (status) {
                        case 400 ->
                            lower.contains("api key not valid")
                                    ? ProviderFailure.AUTHENTICATION_FAILED
                                    : lower.contains("token count") || lower.contains("too large")
                                            ? ProviderFailure.CONTEXT_LENGTH_EXCEEDED
                                            : ProviderFailure.INVALID_REQUEST;
                        case 401 -> ProviderFailure.AUTHENTICATION_FAILED;
                        // Google reports both "not entitled" and "billing disabled" as 403.
                        case 403 ->
                            lower.contains("billing") || lower.contains("quota")
                                    ? ProviderFailure.QUOTA_EXHAUSTED
                                    : ProviderFailure.AUTHORISATION_FAILED;
                        case 404 -> ProviderFailure.MODEL_NOT_FOUND;
                        case 429 ->
                            lower.contains("quota") && lower.contains("exceeded") && lower.contains("billing")
                                    ? ProviderFailure.QUOTA_EXHAUSTED
                                    : ProviderFailure.RATE_LIMITED;
                        case 500, 502 -> ProviderFailure.SERVER_ERROR;
                        case 503 -> ProviderFailure.OVERLOADED;
                        case 504 -> ProviderFailure.TIMEOUT;
                        default -> status >= 500 ? ProviderFailure.SERVER_ERROR : ProviderFailure.UNKNOWN;
                    };
            return new ProviderException(
                    failure,
                    provider.id(),
                    model.modelId(),
                    "Provider responded " + status,
                    status,
                    null,
                    body,
                    response);
        }
        return new ProviderException(
                ProviderFailure.UNKNOWN,
                provider.id(),
                model.modelId(),
                "The provider call failed.",
                null,
                null,
                null,
                error);
    }

    // Caps the raw provider body kept in a failure, because bodies can quote the prompt.
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RAW_BODY_CHARS ? value : value.substring(0, MAX_RAW_BODY_CHARS) + "…";
    }

    // ---- Gemini 3 thought signatures ---------------------------------------------------------

    /**
     * The value Google documents for a function call whose original signature is not available
     * (one written by another model, or carried over a restart): it tells Gemini 3 to skip the
     * signature check for that turn instead of refusing the request.
     */
    static final String SKIP_SIGNATURE_CHECK = "skip_thought_signature_validator";

    /** How many signatures are kept; the oldest are forgotten first. */
    private static final int SIGNATURES_KEPT = 4_000;

    /*
     * The signatures Gemini sent with its function calls, keyed by tool name and arguments, so the
     * next turn can send each one back with its call. In memory only: a signature is only needed by
     * the turns that follow it, and one lost to a restart is replaced by SKIP_SIGNATURE_CHECK.
     */
    private final java.util.Map<String, String> signatures = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > SIGNATURES_KEPT;
                }
            });

    /** Keeps the signature a response part carries beside its function call, if it carries one. */
    private void rememberSignature(JsonNode part, String toolName, String argumentsJson) {
        String signature = part.path("thoughtSignature").asText("");
        if (!signature.isBlank()) {
            signatures.put(signatureKey(toolName, argumentsJson), signature);
        }
    }

    /** The key a call's signature is kept under: its tool name and its arguments, as sent. */
    private String signatureKey(String toolName, String argumentsJson) {
        String arguments;
        try {
            // Normalised, so whitespace differences between storing and sending do not matter.
            arguments = json.readTree(argumentsJson == null ? "{}" : argumentsJson).toString();
        } catch (Exception e) {
            arguments = argumentsJson == null ? "" : argumentsJson;
        }
        return toolName + "\u0000" + arguments;
    }
}
