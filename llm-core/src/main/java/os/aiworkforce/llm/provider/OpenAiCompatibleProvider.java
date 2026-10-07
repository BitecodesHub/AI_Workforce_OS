package os.aiworkforce.llm.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
import os.aiworkforce.llm.model.ImagePart;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolNames;
import os.aiworkforce.llm.model.ToolSpec;

/**
 * One adapter for every provider that speaks the OpenAI chat completions API.
 *
 * <p>That is OpenRouter, NVIDIA NIM, Groq, OpenAI itself, and any self-hosted server implementing
 * the same shape. They differ only in base URL, authentication header and a few attribution
 * headers, all of which come from the provider row. Writing four adapters for one protocol would
 * mean fixing every streaming and tool-accumulation bug four times.
 *
 * <p>The compatibility is close but not perfect, and the differences are handled here:
 *
 * <ul>
 *   <li>Groq rejects {@code presence_penalty} with tools and reports throttling in its own
 *       headers.
 *   <li>NVIDIA NIM namespaces models as {@code vendor/model} and returns a different shape for a
 *       missing model.
 *   <li>OpenRouter wraps upstream failures in its own envelope, so a 200 response can still
 *       contain an error - which is caught below, because treating it as success returns an empty
 *       answer to the person.
 * </ul>
 */
@Component
public class OpenAiCompatibleProvider implements os.aiworkforce.llm.spi.ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final int MAX_RAW_BODY_CHARS = 2_000;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    public OpenAiCompatibleProvider(WebClient.Builder webClientBuilder, ObjectMapper json) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.OPENAI_COMPATIBLE;
    }

    /** OpenAI, OpenRouter and the rest of the family take inline images as {@code image_url} parts. */
    @Override
    public boolean sendsImages() {
        return true;
    }

    @Override
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        Instant startedAt = Instant.now();
        ObjectNode body = buildBody(provider, model, request, false);

        return client(provider, credential)
                .post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout(request))
                .map(node -> parseResponse(provider, model, node, startedAt))
                .onErrorMap(error -> translate(provider, model, error));
    }

    @Override
    public Flux<os.aiworkforce.llm.spi.ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        ObjectNode body = buildBody(provider, model, request, true);
        ToolCallAccumulator accumulator = new ToolCallAccumulator();

        return client(provider, credential)
                .post()
                .uri("/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout(request))
                .concatMap(line -> handleStreamLine(line, accumulator))
                .concatWith(Flux.defer(() -> Flux.fromIterable(accumulator.drainTerminal())))
                .onErrorMap(error -> translate(provider, model, error));
    }

    @Override
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        // Listing models is the cheapest call that still proves the credential is accepted.
        return client(provider, credential)
                .get()
                .uri("/models")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(node -> true)
                .onErrorResume(error -> Mono.just(false));
    }

    // ---- Request building ----------------------------------------------------------------

    private ObjectNode buildBody(ProviderDescriptor provider, ModelSpec model, ChatRequest request, boolean streaming) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model.modelId());
        body.set("messages", buildMessages(request));

        if (request.maxOutputTokens() != null) {
            body.put("max_tokens", Math.min(request.maxOutputTokens(), model.maxOutputTokens()));
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = body.putArray("stop");
            request.stopSequences().forEach(stops::add);
        }
        if (streaming) {
            body.put("stream", true);
            // Without this, most of the family omit usage entirely on a streamed call, and the
            // attempt is recorded with no cost at all.
            body.set("stream_options", json.createObjectNode().put("include_usage", true));
        }
        if (request.usesTools() && model.supportsTools()) {
            body.set("tools", buildTools(request.tools()));
            body.put("tool_choice", "auto");
        }
        if (request.jsonMode() && model.supportsJsonMode()) {
            body.set("response_format", buildResponseFormat(request));
        }
        provider.defaultHeaders(); // headers are applied on the client, not the body
        return body;
    }

    private ArrayNode buildMessages(ChatRequest request) {
        ArrayNode messages = json.createArrayNode();
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put(
                    "role",
                    switch (message.role()) {
                        case SYSTEM -> "system";
                        case USER -> "user";
                        case ASSISTANT -> "assistant";
                        case TOOL -> "tool";
                    });
            if (message.hasImages() && message.role() == ChatMessage.Role.USER) {
                // The content-parts form: the text first, then each picture as a data URL.
                ArrayNode parts = node.putArray("content");
                if (message.content() != null && !message.content().isEmpty()) {
                    ObjectNode text = parts.addObject();
                    text.put("type", "text");
                    text.put("text", message.content());
                }
                for (ImagePart image : message.images()) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url").put("url", image.dataUrl());
                }
            } else if (message.content() != null) {
                node.put("content", message.content());
            } else if (!message.hasToolCalls()) {
                // A null content field is rejected by several of these providers; an empty
                // string is accepted by all of them.
                node.put("content", "");
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.name() != null && message.role() == ChatMessage.Role.TOOL) {
                node.put("name", ToolNames.toWire(message.name()));
            }
            if (message.hasToolCalls()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", ToolNames.toWire(call.name()));
                    function.put("arguments", call.argumentsJson());
                }
            }
        }
        return messages;
    }

    private ArrayNode buildTools(List<ToolSpec> tools) {
        ArrayNode array = json.createArrayNode();
        for (ToolSpec tool : tools) {
            ObjectNode node = array.addObject();
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", ToolNames.toWire(tool.name()));
            function.put("description", tool.description());
            try {
                function.set("parameters", json.readTree(tool.parametersJson()));
            } catch (Exception e) {
                // A malformed schema is our defect, not the provider's. Sending an empty object
                // keeps the tool callable rather than failing the whole request.
                log.warn("Tool {} has an unreadable parameter schema; sending an empty one", tool.name());
                function.set("parameters", json.createObjectNode().put("type", "object"));
            }
        }
        return array;
    }

    private ObjectNode buildResponseFormat(ChatRequest request) {
        ObjectNode format = json.createObjectNode();
        if (request.jsonSchema() == null) {
            format.put("type", "json_object");
            return format;
        }
        try {
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", "response");
            schema.put("strict", true);
            schema.set("schema", json.readTree(request.jsonSchema()));
        } catch (Exception e) {
            format.removeAll();
            format.put("type", "json_object");
        }
        return format;
    }

    // ---- Response parsing ----------------------------------------------------------------

    private ChatResponse parseResponse(ProviderDescriptor provider, ModelSpec model, JsonNode node, Instant startedAt) {
        // OpenRouter and some proxies answer 200 with an error envelope. Treating that as a
        // success hands the person an empty answer with no explanation.
        if (node.has("error") && !node.path("error").isNull()) {
            JsonNode error = node.path("error");
            throw classifyBody(
                    provider,
                    model,
                    error.path("message").asText("Provider returned an error"),
                    error.path("code").asText(null),
                    null);
        }

        JsonNode choice = node.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new ProviderException(
                    ProviderFailure.MALFORMED_RESPONSE,
                    provider.id(),
                    model.modelId(),
                    "The provider returned no choices.",
                    null,
                    null,
                    truncate(node.toString()),
                    null);
        }

        JsonNode message = choice.path("message");
        String content = message.path("content").isNull()
                ? null
                : message.path("content").asText(null);
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        FinishReason finishReason = mapFinishReason(choice.path("finish_reason").asText(null), !toolCalls.isEmpty());

        if (finishReason == FinishReason.CONTENT_FILTER) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED,
                    provider.id(),
                    model.modelId(),
                    "The provider's safety system declined this request.");
        }

        return new ChatResponse(
                content,
                toolCalls,
                finishReason,
                parseUsage(node.path("usage")),
                provider.id(),
                model.modelId(),
                Duration.between(startedAt, Instant.now()),
                List.of(),
                Map.of("id", node.path("id").asText("")));
    }

    private List<ToolCall> parseToolCalls(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            JsonNode function = node.path("function");
            calls.add(new ToolCall(
                    node.path("id").asText("call_" + calls.size()),
                    ToolNames.fromWire(function.path("name").asText("")),
                    function.path("arguments").asText("{}")));
        }
        return calls;
    }

    private TokenUsage parseUsage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.NONE;
        }
        int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        int reasoning =
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        return new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                cached,
                usage.path("completion_tokens").asInt(0),
                reasoning);
    }

    private FinishReason mapFinishReason(String raw, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        if (raw == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (raw) {
            case "stop", "end_turn" -> FinishReason.STOP;
            case "length", "max_tokens" -> FinishReason.LENGTH;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    // ---- Streaming -----------------------------------------------------------------------

    private Flux<os.aiworkforce.llm.spi.ChatChunk> handleStreamLine(String line, ToolCallAccumulator accumulator) {
        if (line == null || line.isBlank() || "[DONE]".equals(line.trim())) {
            return Flux.empty();
        }
        try {
            JsonNode node = json.readTree(line);
            JsonNode choice = node.path("choices").path(0);
            JsonNode delta = choice.path("delta");

            accumulator.absorb(delta.path("tool_calls"));

            String finish = choice.path("finish_reason").asText(null);
            if (finish != null) {
                accumulator.finish(mapFinishReason(finish, accumulator.hasCalls()), parseUsage(node.path("usage")));
            } else if (node.has("usage") && !node.path("usage").isNull()) {
                // Some providers send usage in a final chunk that carries no finish reason.
                accumulator.recordUsage(parseUsage(node.path("usage")));
            }

            String text = delta.path("content").asText(null);
            if (text != null && !text.isEmpty()) {
                return Flux.just(os.aiworkforce.llm.spi.ChatChunk.text(text));
            }
            return Flux.empty();
        } catch (Exception e) {
            // One unreadable frame must not discard an answer that is otherwise arriving fine.
            log.debug("Skipping an unreadable stream frame: {}", e.getMessage());
            return Flux.empty();
        }
    }

    /**
     * Reassembles tool calls that arrive in fragments.
     *
     * <p>These providers stream a tool call across many frames: an index and a name in one, then
     * the arguments a few characters at a time. Emitting fragments would hand every consumer the
     * same reassembly problem, and half of them would get it wrong.
     */
    private final class ToolCallAccumulator {
        private final Map<Integer, ToolCallBuilder> builders = new TreeMap<>();
        private FinishReason finishReason;
        private TokenUsage usage = TokenUsage.NONE;
        private boolean drained;

        void absorb(JsonNode toolCalls) {
            if (!toolCalls.isArray()) {
                return;
            }
            for (JsonNode call : toolCalls) {
                int index = call.path("index").asInt(0);
                ToolCallBuilder builder = builders.computeIfAbsent(index, i -> new ToolCallBuilder());
                if (call.hasNonNull("id")) {
                    builder.id = call.path("id").asText();
                }
                JsonNode function = call.path("function");
                if (function.hasNonNull("name")) {
                    builder.name = ToolNames.fromWire(function.path("name").asText());
                }
                if (function.hasNonNull("arguments")) {
                    builder.arguments.append(function.path("arguments").asText());
                }
            }
        }

        void finish(FinishReason reason, TokenUsage reported) {
            this.finishReason = reason;
            if (reported != null && reported.totalTokens() > 0) {
                this.usage = reported;
            }
        }

        void recordUsage(TokenUsage reported) {
            if (reported != null && reported.totalTokens() > 0) {
                this.usage = reported;
            }
        }

        boolean hasCalls() {
            return !builders.isEmpty();
        }

        List<os.aiworkforce.llm.spi.ChatChunk> drainTerminal() {
            if (drained) {
                return List.of();
            }
            drained = true;
            List<os.aiworkforce.llm.spi.ChatChunk> chunks = new ArrayList<>(2);
            if (!builders.isEmpty()) {
                chunks.add(os.aiworkforce.llm.spi.ChatChunk.tools(
                        builders.values().stream().map(ToolCallBuilder::build).toList()));
            }
            // A stream that stopped without a finish reason is incomplete, not complete. Saying
            // so lets the router retry non-streaming instead of returning half an answer.
            chunks.add(os.aiworkforce.llm.spi.ChatChunk.terminal(
                    finishReason != null ? finishReason : FinishReason.INCOMPLETE, usage));
            return chunks;
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            return new ToolCall(
                    id != null ? id : "call_" + Math.abs(String.valueOf(name).hashCode()),
                    name != null ? name : "unknown",
                    arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }

    // ---- Failure classification ----------------------------------------------------------

    private WebClient client(ProviderDescriptor provider, String credential) {
        WebClient.Builder builder = webClientBuilder.clone().baseUrl(provider.baseUrl());
        if (credential != null && !credential.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + credential);
        }
        provider.defaultHeaders().forEach(builder::defaultHeader);
        return builder.build();
    }

    private Duration timeout(ChatRequest request) {
        return request.timeout() != null ? request.timeout() : Duration.ofSeconds(120);
    }

    /**
     * Turns anything thrown into a classified provider failure.
     *
     * <p>This is the method that decides whether the router retries, moves on or stops, so every
     * branch is deliberate. Anything unrecognised becomes {@code UNKNOWN}, which is failed over
     * but never repeated - the cautious combination when we do not know what happened.
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
            return classifyHttp(provider, model, response);
        }
        return new ProviderException(
                ProviderFailure.UNKNOWN,
                provider.id(),
                model.modelId(),
                "The provider call failed: " + error.getClass().getSimpleName(),
                null,
                null,
                null,
                error);
    }

    private ProviderException classifyHttp(
            ProviderDescriptor provider, ModelSpec model, WebClientResponseException response) {
        int status = response.getStatusCode().value();
        String body = truncate(response.getResponseBodyAsString());
        String providerCode = extractCode(body);
        Duration retryAfter = parseRetryAfter(response);

        ProviderFailure failure =
                switch (status) {
                    case 400 -> classifyBadRequest(body);
                    case 401 -> ProviderFailure.AUTHENTICATION_FAILED;
                    case 402 -> ProviderFailure.INSUFFICIENT_CREDIT;
                    case 403 -> ProviderFailure.AUTHORISATION_FAILED;
                    case 404 -> ProviderFailure.MODEL_NOT_FOUND;
                    case 408 -> ProviderFailure.TIMEOUT;
                    case 413 -> ProviderFailure.CONTEXT_LENGTH_EXCEEDED;
                    case 422 -> classifyBadRequest(body);
                    case 429 -> classifyThrottle(body);
                    case 500, 502, 503 -> ProviderFailure.SERVER_ERROR;
                    case 504 -> ProviderFailure.TIMEOUT;
                    case 529 -> ProviderFailure.OVERLOADED;
                    default -> status >= 500 ? ProviderFailure.SERVER_ERROR : ProviderFailure.UNKNOWN;
                };

        return new ProviderException(
                failure,
                provider.id(),
                model.modelId(),
                "Provider responded " + status + (providerCode == null ? "" : " (" + providerCode + ")"),
                status,
                retryAfter,
                body,
                response);
    }

    /**
     * A 400 covers several very different problems.
     *
     * <p>Context overflow, a safety refusal and a genuinely malformed request all arrive as 400
     * from these providers, and the right response to each is different: compact and retry, stop
     * entirely, or fail the call. The message body is the only thing that distinguishes them.
     */
    private ProviderFailure classifyBadRequest(String body) {
        if (body == null) {
            return ProviderFailure.INVALID_REQUEST;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("context length")
                || lower.contains("context_length")
                || lower.contains("maximum context")
                || lower.contains("too many tokens")
                || lower.contains("reduce the length")) {
            return ProviderFailure.CONTEXT_LENGTH_EXCEEDED;
        }
        if (lower.contains("content_filter")
                || lower.contains("content policy")
                || lower.contains("safety")
                || lower.contains("blocked")) {
            return ProviderFailure.CONTENT_FILTERED;
        }
        if (lower.contains("model")
                && (lower.contains("not found")
                        || lower.contains("does not exist")
                        || lower.contains("decommissioned")
                        || lower.contains("deprecated"))) {
            return ProviderFailure.MODEL_NOT_FOUND;
        }
        return ProviderFailure.INVALID_REQUEST;
    }

    /**
     * A 429 can mean "slow down" or "you are out of money".
     *
     * <p>The first heals in seconds and is worth retrying; the second will not heal at all and
     * needs an operator. Retrying the second burns the retry budget on every request until
     * somebody notices the bill.
     */
    private ProviderFailure classifyThrottle(String body) {
        if (body == null) {
            return ProviderFailure.RATE_LIMITED;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("quota")
                || lower.contains("insufficient_quota")
                || lower.contains("credit")
                || lower.contains("billing")
                || lower.contains("exceeded your current")) {
            return ProviderFailure.QUOTA_EXHAUSTED;
        }
        return ProviderFailure.RATE_LIMITED;
    }

    private ProviderException classifyBody(
            ProviderDescriptor provider, ModelSpec model, String message, String code, Integer status) {
        ProviderFailure failure = classifyBadRequest(message);
        return new ProviderException(
                failure, provider.id(), model.modelId(), message, status, null, truncate(message), null);
    }

    private Duration parseRetryAfter(WebClientResponseException response) {
        String header = response.getHeaders().getFirst("Retry-After");
        if (header == null) {
            // Groq and NVIDIA use their own header names for the same thing.
            header = response.getHeaders().getFirst("x-ratelimit-reset-requests");
        }
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            if (header.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(header.substring(0, header.length() - 2).trim()));
            }
            if (header.endsWith("s")) {
                return Duration.ofMillis((long) (Double.parseDouble(
                                header.substring(0, header.length() - 1).trim())
                        * 1000));
            }
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            // The header can also be an HTTP date. A bad value is not worth failing over.
            return null;
        }
    }

    private String extractCode(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            JsonNode code = node.path("error").path("code");
            return code.isMissingNode() || code.isNull() ? null : code.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /* Provider bodies quote the prompt back. Truncating caps what a trace can ever hold. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RAW_BODY_CHARS ? value : value.substring(0, MAX_RAW_BODY_CHARS) + "…";
    }
}
