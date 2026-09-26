package os.aiworkforce.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;

/**
 * Anthropic's Messages API.
 *
 * <p>Three differences from the OpenAI family shape the translation here. The system prompt is a
 * top-level field rather than a message, so it is lifted out. Tool results are user-role messages
 * containing a {@code tool_result} block rather than a dedicated role. And {@code max_tokens} is
 * required rather than optional, so a default is supplied when the caller did not set one.
 */
@Component
public class AnthropicProvider implements ChatProvider {

    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_RAW_BODY_CHARS = 2_000;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    public AnthropicProvider(WebClient.Builder webClientBuilder, ObjectMapper json) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.ANTHROPIC;
    }

    @Override
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        Instant startedAt = Instant.now();
        return client(provider, credential)
                .post()
                .uri("/v1/messages")
                .bodyValue(buildBody(model, request, false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .map(node -> parse(provider, model, node, startedAt))
                .onErrorMap(error -> translate(provider, model, error));
    }

    @Override
    public Flux<ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        StreamState state = new StreamState();
        return client(provider, credential)
                .post()
                .uri("/v1/messages")
                .bodyValue(buildBody(model, request, true))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .concatMap(line -> handleEvent(line, state))
                .concatWith(Flux.defer(() -> Flux.fromIterable(state.drain())))
                .onErrorMap(error -> translate(provider, model, error));
    }

    @Override
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        // Anthropic offers no free probe endpoint, so the cheapest real call is used: one token.
        ObjectNode body = json.createObjectNode();
        body.put("model", "claude-haiku-4-5-20251001");
        body.put("max_tokens", 1);
        body.set("messages", json.createArrayNode().add(json.createObjectNode()
                .put("role", "user")
                .put("content", "ping")));
        return client(provider, credential)
                .post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(node -> true)
                .onErrorResume(error -> Mono.just(
                        // A 400 still proves the credential was accepted; only 401 and 403 do not.
                        error instanceof WebClientResponseException response
                                && response.getStatusCode().value() != 401
                                && response.getStatusCode().value() != 403));
    }

    private ObjectNode buildBody(ModelSpec model, ChatRequest request, boolean streaming) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model.modelId());
        body.put("max_tokens", request.maxOutputTokens() != null
                ? Math.min(request.maxOutputTokens(), model.maxOutputTokens())
                : Math.min(DEFAULT_MAX_TOKENS, model.maxOutputTokens()));

        String system = request.systemPrompt();
        if (system != null) {
            body.put("system", system);
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = body.putArray("stop_sequences");
            request.stopSequences().forEach(stops::add);
        }
        if (streaming) {
            body.put("stream", true);
        }

        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.conversation()) {
            if (message.role() == ChatMessage.Role.TOOL) {
                ObjectNode node = messages.addObject();
                node.put("role", "user");
                ObjectNode block = node.putArray("content").addObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", message.toolCallId());
                block.put("content", message.content() == null ? "" : message.content());
                continue;
            }
            ObjectNode node = messages.addObject();
            node.put("role", message.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user");
            if (message.hasToolCalls()) {
                ArrayNode content = node.putArray("content");
                if (message.content() != null && !message.content().isBlank()) {
                    content.addObject().put("type", "text").put("text", message.content());
                }
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode use = content.addObject();
                    use.put("type", "tool_use");
                    use.put("id", call.id());
                    use.put("name", call.name());
                    try {
                        use.set("input", json.readTree(call.argumentsJson()));
                    } catch (Exception e) {
                        use.set("input", json.createObjectNode());
                    }
                }
            } else {
                node.put("content", message.content() == null ? "" : message.content());
            }
        }

        if (request.usesTools() && model.supportsTools()) {
            ArrayNode tools = body.putArray("tools");
            request.tools().forEach(tool -> {
                ObjectNode node = tools.addObject();
                node.put("name", tool.name());
                node.put("description", tool.description());
                try {
                    node.set("input_schema", json.readTree(tool.parametersJson()));
                } catch (Exception e) {
                    node.set("input_schema", json.createObjectNode().put("type", "object"));
                }
            });
        }
        return body;
    }

    private ChatResponse parse(ProviderDescriptor provider, ModelSpec model, JsonNode node, Instant startedAt) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : node.path("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(
                        block.path("id").asText(""),
                        block.path("name").asText(""),
                        block.path("input").toString()));
            }
        }

        String stopReason = node.path("stop_reason").asText(null);
        FinishReason finish = mapStopReason(stopReason, !calls.isEmpty());
        if (finish == FinishReason.CONTENT_FILTER) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED, provider.id(), model.modelId(),
                    "The provider's safety system declined this request.");
        }

        JsonNode usage = node.path("usage");
        TokenUsage tokens = new TokenUsage(
                usage.path("input_tokens").asInt(0) + usage.path("cache_read_input_tokens").asInt(0),
                usage.path("cache_read_input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                0);

        return new ChatResponse(
                text.isEmpty() ? null : text.toString(),
                calls,
                finish,
                tokens,
                provider.id(),
                model.modelId(),
                Duration.between(startedAt, Instant.now()),
                List.of(),
                Map.of("id", node.path("id").asText("")));
    }

    private FinishReason mapStopReason(String raw, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        if (raw == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (raw) {
            case "end_turn" -> FinishReason.STOP;
            case "max_tokens" -> FinishReason.LENGTH;
            case "stop_sequence" -> FinishReason.STOP_SEQUENCE;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "refusal" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    /** Accumulates the partial JSON that tool inputs arrive as, across many delta events. */
    private static final class StreamState {
        private final Map<Integer, StringBuilder> partialInputs = new java.util.TreeMap<>();
        private final Map<Integer, String[]> toolIdentity = new java.util.TreeMap<>();
        private FinishReason finishReason;
        private TokenUsage usage = TokenUsage.NONE;
        private boolean drained;

        List<ChatChunk> drain() {
            if (drained) {
                return List.of();
            }
            drained = true;
            List<ChatChunk> chunks = new ArrayList<>(2);
            if (!toolIdentity.isEmpty()) {
                List<ToolCall> calls = new ArrayList<>();
                toolIdentity.forEach((index, identity) -> {
                    StringBuilder arguments = partialInputs.get(index);
                    calls.add(new ToolCall(
                            identity[0], identity[1],
                            arguments == null || arguments.isEmpty() ? "{}" : arguments.toString()));
                });
                chunks.add(ChatChunk.tools(calls));
            }
            chunks.add(ChatChunk.terminal(
                    finishReason != null ? finishReason : FinishReason.INCOMPLETE, usage));
            return chunks;
        }
    }

    private Flux<ChatChunk> handleEvent(String line, StreamState state) {
        if (line == null || line.isBlank()) {
            return Flux.empty();
        }
        try {
            JsonNode node = json.readTree(line);
            String type = node.path("type").asText("");
            switch (type) {
                case "content_block_start" -> {
                    JsonNode block = node.path("content_block");
                    if ("tool_use".equals(block.path("type").asText(""))) {
                        int index = node.path("index").asInt(0);
                        state.toolIdentity.put(index, new String[] {
                            block.path("id").asText(""), block.path("name").asText("")
                        });
                        state.partialInputs.put(index, new StringBuilder());
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("text_delta".equals(deltaType)) {
                        return Flux.just(ChatChunk.text(delta.path("text").asText("")));
                    }
                    if ("input_json_delta".equals(deltaType)) {
                        state.partialInputs
                                .computeIfAbsent(node.path("index").asInt(0), i -> new StringBuilder())
                                .append(delta.path("partial_json").asText(""));
                    }
                }
                case "message_delta" -> {
                    state.finishReason = mapStopReason(
                            node.path("delta").path("stop_reason").asText(null), !state.toolIdentity.isEmpty());
                    JsonNode usage = node.path("usage");
                    if (usage.has("output_tokens")) {
                        state.usage = new TokenUsage(
                                usage.path("input_tokens").asInt(state.usage.promptTokens()), 0,
                                usage.path("output_tokens").asInt(0), 0);
                    }
                }
                case "error" -> throw ProviderException.of(
                        ProviderFailure.SERVER_ERROR, "anthropic", "",
                        node.path("error").path("message").asText("Stream error"));
                default -> {
                    /* message_start, ping, content_block_stop and message_stop carry no output. */
                }
            }
            return Flux.empty();
        } catch (ProviderException e) {
            return Flux.error(e);
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    private WebClient client(ProviderDescriptor provider, String credential) {
        WebClient.Builder builder = webClientBuilder
                .clone()
                .baseUrl(provider.baseUrl())
                .defaultHeader("anthropic-version", API_VERSION);
        if (credential != null && !credential.isBlank()) {
            builder.defaultHeader("x-api-key", credential);
        }
        provider.defaultHeaders().forEach(builder::defaultHeader);
        return builder.build();
    }

    private Throwable translate(ProviderDescriptor provider, ModelSpec model, Throwable error) {
        if (error instanceof ProviderException) {
            return error;
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return new ProviderException(ProviderFailure.TIMEOUT, provider.id(), model.modelId(),
                    "The provider did not answer inside the deadline.", null, null, null, error);
        }
        if (error instanceof WebClientRequestException) {
            return new ProviderException(ProviderFailure.NETWORK_ERROR, provider.id(), model.modelId(),
                    "The provider could not be reached.", null, null, null, error);
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            String body = truncate(response.getResponseBodyAsString());
            String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
            ProviderFailure failure = switch (status) {
                case 400 -> lower.contains("prompt is too long") || lower.contains("max_tokens")
                        ? ProviderFailure.CONTEXT_LENGTH_EXCEEDED
                        : ProviderFailure.INVALID_REQUEST;
                case 401 -> ProviderFailure.AUTHENTICATION_FAILED;
                case 403 -> ProviderFailure.AUTHORISATION_FAILED;
                case 404 -> ProviderFailure.MODEL_NOT_FOUND;
                case 413 -> ProviderFailure.CONTEXT_LENGTH_EXCEEDED;
                case 429 -> lower.contains("credit") || lower.contains("quota")
                        ? ProviderFailure.QUOTA_EXHAUSTED
                        : ProviderFailure.RATE_LIMITED;
                // 529 is Anthropic's "overloaded", distinct from a 500 and worth retrying sooner.
                case 529 -> ProviderFailure.OVERLOADED;
                case 500, 502, 503 -> ProviderFailure.SERVER_ERROR;
                case 504 -> ProviderFailure.TIMEOUT;
                default -> status >= 500 ? ProviderFailure.SERVER_ERROR : ProviderFailure.UNKNOWN;
            };
            Duration retryAfter = null;
            String header = response.getHeaders().getFirst("retry-after");
            if (header != null) {
                try {
                    retryAfter = Duration.ofSeconds(Long.parseLong(header.trim()));
                } catch (NumberFormatException ignored) {
                    retryAfter = null;
                }
            }
            return new ProviderException(failure, provider.id(), model.modelId(),
                    "Provider responded " + status, status, retryAfter, body, response);
        }
        return new ProviderException(ProviderFailure.UNKNOWN, provider.id(), model.modelId(),
                "The provider call failed.", null, null, null, error);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RAW_BODY_CHARS ? value : value.substring(0, MAX_RAW_BODY_CHARS) + "…";
    }
}
