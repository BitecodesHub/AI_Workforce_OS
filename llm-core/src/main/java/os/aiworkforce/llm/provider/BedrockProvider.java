package os.aiworkforce.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

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
 * AWS Bedrock, through the Converse API.
 *
 * <p>Converse rather than {@code InvokeModel}: it presents one message and tool shape across
 * every model family on Bedrock, so this adapter does not need a branch per underlying vendor.
 *
 * <p>Bedrock is the only regional provider here, and that changes the failure model. A model can
 * be available in one region and not another, and a region can be throttled while its neighbour
 * is idle. So the descriptor carries an ordered region list, and a regional failure moves to the
 * next region before the router is told the provider failed at all - an inner fallback beneath
 * the outer one.
 *
 * <p>Credentials come from the platform's credential store when supplied as
 * {@code accessKeyId:secretAccessKey}, and otherwise from the default AWS chain, which is what a
 * deployment using instance roles or IRSA needs.
 */
@Component
public class BedrockProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(BedrockProvider.class);
    private static final String DEFAULT_REGION = "us-east-1";

    /* One client per region, reused: each holds a connection pool that is costly to rebuild. */
    private final Map<String, BedrockRuntimeAsyncClient> clients = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public BedrockProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.BEDROCK;
    }

    @Override
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        List<String> regions = provider.regions().isEmpty() ? List.of(DEFAULT_REGION) : provider.regions();
        return attemptRegions(provider, model, request, credential, regions, 0, new ArrayList<>());
    }

    /**
     * Tries each region in order, keeping the failures for the trace.
     *
     * <p>Only failures that are about <em>this region</em> move to the next one. A rejected
     * credential or a malformed request will fail identically everywhere, and walking four
     * regions to discover that wastes four round trips and four times the latency.
     */
    private Mono<ChatResponse> attemptRegions(
            ProviderDescriptor provider,
            ModelSpec model,
            ChatRequest request,
            String credential,
            List<String> regions,
            int index,
            List<String> failures) {
        if (index >= regions.size()) {
            return Mono.error(new ProviderException(
                    ProviderFailure.REGION_UNAVAILABLE, provider.id(), model.modelId(),
                    "No Bedrock region answered: " + String.join("; ", failures),
                    null, null, null, null));
        }
        String region = regions.get(index);
        Instant startedAt = Instant.now();

        return Mono.fromFuture(() -> client(region, credential).converse(buildRequest(model, request)))
                .timeout(request.timeout() != null ? request.timeout() : Duration.ofSeconds(120))
                .map(response -> parse(provider, model, region, response, startedAt))
                .onErrorResume(error -> {
                    ProviderException classified = classify(provider, model, region, error);
                    failures.add(region + ": " + classified.failure().name());
                    boolean regional = classified.failure() == ProviderFailure.REGION_UNAVAILABLE
                            || classified.failure() == ProviderFailure.MODEL_NOT_FOUND
                            || classified.failure() == ProviderFailure.RATE_LIMITED
                            || classified.failure() == ProviderFailure.SERVER_ERROR;
                    if (regional && index + 1 < regions.size()) {
                        log.info("Bedrock {} failed in {}, trying the next region", model.modelId(), region);
                        return attemptRegions(
                                provider, model, request, credential, regions, index + 1, failures);
                    }
                    return Mono.error(classified);
                });
    }

    @Override
    public Flux<ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        // ConverseStream needs a different response handler and gives no benefit the router
        // relies on; completing and emitting one terminal chunk keeps behaviour honest rather
        // than pretending to stream. The model capability row says so, and the router respects it.
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

    @Override
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        String region = provider.regions().isEmpty() ? DEFAULT_REGION : provider.regions().get(0);
        return Mono.fromCallable(() -> {
                    client(region, credential);
                    return true;
                })
                .onErrorReturn(false);
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    private ConverseRequest buildRequest(ModelSpec model, ChatRequest request) {
        ConverseRequest.Builder builder = ConverseRequest.builder().modelId(model.modelId());

        String system = request.systemPrompt();
        if (system != null) {
            builder.system(SystemContentBlock.fromText(system));
        }

        List<Message> messages = new ArrayList<>();
        for (ChatMessage message : request.conversation()) {
            if (message.role() == ChatMessage.Role.TOOL) {
                messages.add(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromToolResult(ToolResultBlock.builder()
                                .toolUseId(message.toolCallId())
                                .content(ToolResultContentBlock.fromText(
                                        message.content() == null ? "" : message.content()))
                                .build()))
                        .build());
                continue;
            }
            List<ContentBlock> blocks = new ArrayList<>();
            if (message.content() != null && !message.content().isBlank()) {
                blocks.add(ContentBlock.fromText(message.content()));
            }
            for (ToolCall call : message.toolCalls()) {
                blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                        .toolUseId(call.id())
                        .name(call.name())
                        .input(toDocument(call.argumentsJson()))
                        .build()));
            }
            if (blocks.isEmpty()) {
                blocks.add(ContentBlock.fromText(""));
            }
            messages.add(Message.builder()
                    .role(message.role() == ChatMessage.Role.ASSISTANT
                            ? ConversationRole.ASSISTANT
                            : ConversationRole.USER)
                    .content(blocks)
                    .build());
        }
        builder.messages(messages);

        InferenceConfiguration.Builder inference = InferenceConfiguration.builder();
        inference.maxTokens(request.maxOutputTokens() != null
                ? Math.min(request.maxOutputTokens(), model.maxOutputTokens())
                : Math.min(4096, model.maxOutputTokens()));
        if (request.temperature() != null) {
            inference.temperature(request.temperature().floatValue());
        }
        if (!request.stopSequences().isEmpty()) {
            inference.stopSequences(request.stopSequences());
        }
        builder.inferenceConfig(inference.build());

        if (request.usesTools() && model.supportsTools()) {
            List<Tool> tools = request.tools().stream()
                    .map(tool -> Tool.fromToolSpec(ToolSpecification.builder()
                            .name(tool.name())
                            .description(tool.description())
                            .inputSchema(ToolInputSchema.fromJson(toDocument(tool.parametersJson())))
                            .build()))
                    .toList();
            builder.toolConfig(ToolConfiguration.builder().tools(tools).build());
        }
        return builder.build();
    }

    private ChatResponse parse(
            ProviderDescriptor provider,
            ModelSpec model,
            String region,
            ConverseResponse response,
            Instant startedAt) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();

        if (response.output() != null && response.output().message() != null) {
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) {
                    text.append(block.text());
                }
                if (block.toolUse() != null) {
                    ToolUseBlock use = block.toolUse();
                    calls.add(new ToolCall(use.toolUseId(), use.name(), documentToJson(use.input())));
                }
            }
        }

        String stopReason = response.stopReasonAsString();
        if ("content_filtered".equalsIgnoreCase(stopReason) || "guardrail_intervened".equalsIgnoreCase(stopReason)) {
            throw ProviderException.of(
                    ProviderFailure.CONTENT_FILTERED, provider.id(), model.modelId(),
                    "A Bedrock guardrail stopped this request.");
        }

        TokenUsage usage = response.usage() == null
                ? TokenUsage.NONE
                : new TokenUsage(
                        orZero(response.usage().inputTokens()),
                        orZero(response.usage().cacheReadInputTokens()),
                        orZero(response.usage().outputTokens()),
                        0);

        return new ChatResponse(
                text.isEmpty() ? null : text.toString(),
                calls,
                mapStopReason(stopReason, !calls.isEmpty()),
                usage,
                provider.id(),
                model.modelId(),
                Duration.between(startedAt, Instant.now()),
                List.of(),
                Map.of("region", region));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private FinishReason mapStopReason(String raw, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        if (raw == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "end_turn" -> FinishReason.STOP;
            case "max_tokens" -> FinishReason.LENGTH;
            case "stop_sequence" -> FinishReason.STOP_SEQUENCE;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "content_filtered", "guardrail_intervened" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    private ProviderException classify(
            ProviderDescriptor provider, ModelSpec model, String region, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;

        ProviderFailure failure;
        if (cause instanceof ThrottlingException) {
            failure = ProviderFailure.RATE_LIMITED;
        } else if (cause instanceof ServiceQuotaExceededException) {
            failure = ProviderFailure.QUOTA_EXHAUSTED;
        } else if (cause instanceof AccessDeniedException) {
            failure = ProviderFailure.AUTHORISATION_FAILED;
        } else if (cause instanceof ResourceNotFoundException) {
            failure = ProviderFailure.MODEL_NOT_FOUND;
        } else if (cause instanceof ModelNotReadyException) {
            // A provisioned model still warming up; another region may already be warm.
            failure = ProviderFailure.REGION_UNAVAILABLE;
        } else if (cause instanceof ModelTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            failure = ProviderFailure.TIMEOUT;
        } else if (cause instanceof ValidationException validation) {
            String message = validation.getMessage() == null
                    ? ""
                    : validation.getMessage().toLowerCase(java.util.Locale.ROOT);
            failure = message.contains("too long") || message.contains("token")
                    ? ProviderFailure.CONTEXT_LENGTH_EXCEEDED
                    : ProviderFailure.INVALID_REQUEST;
        } else if (cause instanceof ModelErrorException) {
            failure = ProviderFailure.SERVER_ERROR;
        } else if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            failure = ProviderFailure.NETWORK_ERROR;
        } else if (cause instanceof ProviderException already) {
            return already;
        } else {
            failure = ProviderFailure.UNKNOWN;
        }

        return new ProviderException(
                failure, provider.id(), model.modelId(),
                "Bedrock " + region + ": " + cause.getClass().getSimpleName(),
                null, null, null, cause);
    }

    /**
     * Builds, and caches, a client per region.
     *
     * <p>An explicit {@code key:secret} credential comes from the platform's encrypted store.
     * Anything else falls through to the default AWS chain, which is how a deployment on EKS with
     * IRSA, or on EC2 with an instance role, authenticates without a stored secret at all.
     */
    private BedrockRuntimeAsyncClient client(String region, String credential) {
        String cacheKey = region + "|" + (credential == null ? "default" : Integer.toHexString(credential.hashCode()));
        return clients.computeIfAbsent(cacheKey, key -> {
            software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder builder =
                    BedrockRuntimeAsyncClient.builder().region(Region.of(region));
            if (credential != null && credential.contains(":")) {
                String[] parts = credential.split(":", 2);
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(parts[0], parts[1])));
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            }
            return builder.build();
        });
    }

    private Document toDocument(String jsonText) {
        try {
            return documentOf(json.readTree(jsonText));
        } catch (Exception e) {
            return Document.mapBuilder().build();
        }
    }

    private Document documentOf(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return Document.fromNull();
        }
        if (node.isObject()) {
            Document.MapBuilder builder = Document.mapBuilder();
            node.fields().forEachRemaining(entry -> builder.putDocument(entry.getKey(), documentOf(entry.getValue())));
            return builder.build();
        }
        if (node.isArray()) {
            List<Document> items = new ArrayList<>();
            node.forEach(child -> items.add(documentOf(child)));
            return Document.fromList(items);
        }
        if (node.isBoolean()) {
            return Document.fromBoolean(node.asBoolean());
        }
        if (node.isIntegralNumber()) {
            return Document.fromNumber(node.asLong());
        }
        if (node.isNumber()) {
            return Document.fromNumber(node.asDouble());
        }
        return Document.fromString(node.asText());
    }

    private String documentToJson(Document document) {
        if (document == null) {
            return "{}";
        }
        try {
            return json.writeValueAsString(document.unwrap());
        } catch (Exception e) {
            return "{}";
        }
    }

    static SdkBytes unusedMarker() {
        return SdkBytes.fromUtf8String("");
    }
}
