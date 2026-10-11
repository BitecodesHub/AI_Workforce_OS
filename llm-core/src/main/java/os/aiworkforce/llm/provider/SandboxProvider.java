// @find: model router, LLM, model providers, sandbox, offline model, demo mode, deterministic answers, no API key, free, keyword-only embeddings, asks a person once, SandboxProvider
// @what: Offline deterministic model with on-demand fault injection, sample ask-person and tool calls, and pseudo-embeddings, so the platform runs end to end with no external provider.
// @flow: Spring ChatProvider of kind SANDBOX; ModelRouter calls complete/stream/embed/healthCheck and uses it as the offline fallback; fault markers raise ProviderException to exercise router failover; also used by tests.
package os.aiworkforce.llm.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
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
import os.aiworkforce.llm.model.TokenEstimate;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolNames;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;

/**
 * A model that runs offline and answers the same way every time.
 *
 * <p>This is not a stub, and it is not test scaffolding. It is what makes the claim "nothing
 * static, nothing broken" true: the platform can be cloned, started and driven end to end - agents
 * running, tools being called, approvals being raised - with no API key anywhere. Going live is
 * changing a provider row, not writing the missing half of the system.
 *
 * <p>Determinism comes from seeding a generator with a digest of the request, so the same
 * conversation produces the same answer on every machine and in every test run. That is what makes
 * a failing test reproducible rather than a coin toss.
 *
 * <p>It also injects faults on demand. A prompt containing {@code [[fault:rate_limited]]} raises
 * exactly the failure the router would see from a real provider, which is how the fallover paths
 * are tested without waiting for a vendor to have a bad afternoon.
 *
 * <p>Beside the fault markers sit two question markers. A prompt containing {@code [[ask]]} makes
 * the sandbox call the person's ask-question tool with one sample question, and {@code
 * [[ask:multi]]} with two, once per run: after the answer comes back it answers normally. The
 * ask tool is never picked at random, so an offline demo only asks a person something when the
 * prompt says to.
 */
@Component
public class SandboxProvider implements ChatProvider {

    private static final String FAULT_PREFIX = "[[fault:";
    private static final String FAULT_SUFFIX = "]]";
    private static final String ASK_MARKER = "[[ask]]";
    private static final String ASK_MULTI_MARKER = "[[ask:multi]]";
    private static final int WORDS_PER_SENTENCE = 14;

    private final ObjectMapper json;

    /**
     * Creates the sandbox with the shared JSON mapper, used to build sample tool arguments and answers.
     * It has no network dependency and needs no credential.
     */
    public SandboxProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    /**
     * Identifies this adapter to the provider registry, which selects it for provider rows of kind
     * {@code SANDBOX}.
     */
    public ProviderDescriptor.Kind kind() {
        return ProviderDescriptor.Kind.SANDBOX;
    }

    // @find: sandbox answer
    @Override
    /**
     * Produces a deterministic answer with no external call.
     *
     * <p>Flow: called by the model router; first raises any fault requested by a {@code [[fault:NAME]]}
     * marker in the prompt, then builds the answer in {@link #answer}. A fixed 120 millisecond delay is
     * applied so that interfaces see realistic latency. The credential is ignored.
     */
    public Mono<ChatResponse> complete(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        return Mono.fromCallable(() -> {
                    injectFaultIfRequested(provider, model, request);
                    return answer(provider, model, request);
                })
                // A small delay keeps the interface honest: a streaming view that never waits
                // hides every race that a real provider's latency would expose.
                .delayElement(Duration.ofMillis(120));
    }

    // @find: sandbox streamed answer
    @Override
    /**
     * Streams the same deterministic answer word by word, 18 milliseconds apart, followed by any tool call
     * and a terminal chunk. A requested fault is raised as an error signal before any chunk is emitted.
     */
    public Flux<ChatChunk> stream(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
        return Mono.fromCallable(() -> {
                    injectFaultIfRequested(provider, model, request);
                    return answer(provider, model, request);
                })
                .flatMapMany(response -> {
                    List<ChatChunk> chunks = new ArrayList<>();
                    if (response.content() != null) {
                        for (String word : response.content().split("(?<= )")) {
                            chunks.add(ChatChunk.text(word));
                        }
                    }
                    if (response.hasToolCalls()) {
                        chunks.add(ChatChunk.tools(response.toolCalls()));
                    }
                    chunks.add(ChatChunk.terminal(response.finishReason(), response.usage()));
                    return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(18));
                });
    }

    // @find: sandbox embeddings, keyword only
    @Override
    /**
     * Returns deterministic 1536-dimension unit vectors derived from a digest of each text. The vectors
     * carry no meaning, so similarity search over them is not useful; the knowledge pipeline is still
     * exercised in full. Used by knowledge-service when no real embedding provider is configured.
     */
    public Mono<List<float[]>> embed(
            ProviderDescriptor provider, ModelSpec model, List<String> inputs, String credential) {
        // Deterministic pseudo-embeddings. Nearby text does not produce nearby vectors, so
        // retrieval quality is meaningless here - but the pipeline, the dimensions and the
        // storage are all exercised exactly as they will be with a real embedding model.
        return Mono.fromCallable(
                () -> inputs.stream().map(this::pseudoEmbedding).toList());
    }

    // @find: sandbox always healthy
    @Override
    /**
     * Always healthy, because nothing external is involved.
     */
    public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
        return Mono.just(true);
    }

    // ---- Answering -----------------------------------------------------------------------

    /**
     * Builds the answer for a request, seeded from a digest of the conversation.
     *
     * <p>Order of decisions: call the ask-person tool when an ask marker is present and no answer has been
     * received yet; otherwise, when tools are on offer, call one of them (never the ask or memory tools)
     * on about half of requests; otherwise return prose, or sample JSON in JSON mode. The finish reason is
     * {@code LENGTH} when the estimated size exceeds the output limit. Metadata marks the answer as
     * coming from the sandbox.
     */
    private ChatResponse answer(ProviderDescriptor provider, ModelSpec model, ChatRequest request) {
        Random random = new Random(seedOf(request));
        String lastUserText = lastUserMessage(request);

        // The person's ask tool is only ever called on request, never picked at random: an
        // offline demo that stopped to ask nonsense questions would be worse than one that never
        // asked at all.
        List<ToolSpec> pickable = request.tools().stream()
                .filter(tool -> !ToolNames.isPersonTool(tool.name()))
                // Practice runs do not write notes into an agent's real memory.
                .filter(tool -> !ToolNames.isMemoryTool(tool.name()))
                .toList();
        ToolSpec askSpec = request.tools().stream()
                .filter(tool -> ToolNames.isPersonTool(tool.name()))
                .findFirst()
                .orElse(null);
        String marker = askMarker(request);
        boolean alreadyAsked = request.messages().stream()
                .anyMatch(message -> message.role() == ChatMessage.Role.TOOL && ToolNames.isPersonTool(message.name()));
        if (askSpec != null && marker != null && !alreadyAsked && model.supportsTools()) {
            return toolCallResponse(
                    provider,
                    model,
                    request,
                    new ToolCall(
                            "call_ask_" + Long.toHexString(random.nextLong() & 0xFFFFFFFFL),
                            askSpec.name(),
                            sampleAsk(marker)));
        }

        // When tools are on offer, call one roughly half the time, so both branches of the
        // orchestrator - answer directly, or run a tool and continue - get exercised.
        if (request.usesTools() && model.supportsTools() && !pickable.isEmpty() && random.nextBoolean()) {
            ToolSpec tool = pickable.get(random.nextInt(pickable.size()));
            ToolCall call = new ToolCall(
                    "call_" + Long.toHexString(random.nextLong() & 0xFFFFFFFFL),
                    tool.name(),
                    sampleArguments(tool, lastUserText));
            return toolCallResponse(provider, model, request, call);
        }

        String content = request.jsonMode() ? sampleJson(lastUserText) : sampleProse(lastUserText, random);

        int completionTokens = TokenEstimate.forText(content);
        boolean truncated = request.maxOutputTokens() != null && completionTokens > request.maxOutputTokens();

        return new ChatResponse(
                content,
                List.of(),
                truncated ? FinishReason.LENGTH : FinishReason.STOP,
                usageFor(request, completionTokens),
                provider.id(),
                model.modelId(),
                Duration.ofMillis(120),
                List.of(),
                Map.of("sandbox", "true"));
    }

    // A response consisting of one tool call, with no text and a {@code TOOL_CALLS} finish.
    private ChatResponse toolCallResponse(
            ProviderDescriptor provider, ModelSpec model, ChatRequest request, ToolCall call) {
        return new ChatResponse(
                null,
                List.of(call),
                FinishReason.TOOL_CALLS,
                usageFor(request, 0),
                provider.id(),
                model.modelId(),
                Duration.ofMillis(120),
                List.of(),
                Map.of("sandbox", "true"));
    }

    /** {@code multi} for {@code [[ask:multi]]}, {@code one} for {@code [[ask]]}, otherwise null. */
    private static String askMarker(ChatRequest request) {
        boolean one = false;
        for (ChatMessage message : request.messages()) {
            String content = message.content();
            if (content == null) {
                continue;
            }
            if (content.contains(ASK_MULTI_MARKER)) {
                return "multi";
            }
            if (content.contains(ASK_MARKER)) {
                one = true;
            }
        }
        return one ? "one" : null;
    }

    /**
     * Arguments for the ask tool, in the shape a live model is asked to send: one question, or
     * two with the second allowing several answers.
     */
    private String sampleAsk(String marker) {
        List<Map<String, Object>> questions = new ArrayList<>();
        questions.add(sampleQuestion(
                "Audience",
                "Who is this for?",
                false,
                List.of(
                        option("The whole team", "Plain language, no assumed context."),
                        option("Managers", "Shorter, with the decisions first."),
                        option("A customer", "Formal, and nothing internal.")),
                0));
        if ("multi".equals(marker)) {
            questions.add(sampleQuestion(
                    "Include",
                    "What should it include?",
                    true,
                    List.of(
                            option("A summary", "Three lines at the top."),
                            option("Next steps", "Who does what, and by when."),
                            option("A table", "The figures side by side.")),
                    null));
        }
        try {
            return json.writeValueAsString(Map.of("questions", questions));
        } catch (Exception e) {
            return "{\"questions\":[]}";
        }
    }

    private static Map<String, Object> sampleQuestion(
            String header,
            String question,
            boolean multiSelect,
            List<Map<String, Object>> options,
            Integer recommended) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("header", header);
        entry.put("question", question);
        entry.put("multiSelect", multiSelect);
        entry.put("options", options);
        if (recommended != null) {
            entry.put("recommended", recommended);
        }
        return entry;
    }

    private static Map<String, Object> option(String label, String description) {
        Map<String, Object> option = new java.util.LinkedHashMap<>();
        option.put("label", label);
        option.put("description", description);
        return option;
    }

    /**
     * Prose that names what was asked, so a person reading the interface can tell the sandbox
     * answered rather than wondering why the model is vague.
     */
    private String sampleProse(String question, Random random) {
        String subject = question == null || question.isBlank()
                ? "the request"
                : requestPart(question).strip().lines().findFirst().orElse("the request");
        if (subject.length() > 120) {
            subject = subject.substring(0, 117) + "…";
        }
        StringBuilder text = new StringBuilder();
        text.append("This answer comes from the offline sandbox model, so it repeats the request ")
                .append("rather than reasoning about it. Asked: \"")
                .append(subject)
                .append("\". ");
        text.append("Configure a provider credential in Settings to route this agent to a live model. ");
        int extraSentences = random.nextInt(2);
        for (int i = 0; i < extraSentences; i++) {
            text.append("The run trace records which candidate answered and why any earlier one did not. ");
        }
        return text.toString().strip();
    }

    // JSON-mode answer: a fixed object that names the sandbox as its source, with a placeholder message.
    private String sampleJson(String question) {
        try {
            return json.writeValueAsString(Map.of(
                    "source",
                    "sandbox",
                    "question",
                    question == null ? "" : question,
                    "answer",
                    "Configure a live provider to replace this placeholder.",
                    "confidence",
                    0.0));
        } catch (Exception e) {
            return "{\"source\":\"sandbox\"}";
        }
    }

    /**
     * Plausible arguments derived from the tool's own schema.
     *
     * <p>Reading the declared properties rather than inventing names means the sandbox exercises
     * the real argument validation. A tool whose schema and handler disagree fails here, in
     * development, instead of the first time a live model calls it.
     */
    private String sampleArguments(ToolSpec tool, String context) {
        try {
            com.fasterxml.jackson.databind.JsonNode schema = json.readTree(tool.parametersJson());
            com.fasterxml.jackson.databind.node.ObjectNode arguments = json.createObjectNode();
            com.fasterxml.jackson.databind.JsonNode properties = schema.path("properties");
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(node -> required.add(node.asText()));

            properties.fields().forEachRemaining(entry -> {
                if (!required.isEmpty() && !required.contains(entry.getKey())) {
                    return;
                }
                String type = entry.getValue().path("type").asText("string");
                switch (type) {
                    case "integer" -> arguments.put(entry.getKey(), 1);
                    case "number" -> arguments.put(entry.getKey(), 1.0);
                    case "boolean" -> arguments.put(entry.getKey(), true);
                    case "array" -> arguments.putArray(entry.getKey());
                    case "object" -> arguments.putObject(entry.getKey());
                    default -> arguments.put(entry.getKey(), context == null ? "sandbox" : context.strip());
                }
            });
            return json.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    // Token usage from the request's own prompt estimate and the given completion size.
    private TokenUsage usageFor(ChatRequest request, int completionTokens) {
        return TokenUsage.of(request.estimatedPromptTokens(), completionTokens);
    }

    // ---- Fault injection -----------------------------------------------------------------

    /**
     * Raises a named failure when a prompt asks for one.
     *
     * <p>Every branch of the router - a throttle that retries, a credential that fails over, a
     * safety refusal that must not - needs a provider that fails on command. Waiting for a real
     * vendor to produce each failure is not a test strategy.
     */
    private void injectFaultIfRequested(ProviderDescriptor provider, ModelSpec model, ChatRequest request) {
        String prompt = request.messages().stream()
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + " " + b);
        int start = prompt.indexOf(FAULT_PREFIX);
        if (start < 0) {
            return;
        }
        int end = prompt.indexOf(FAULT_SUFFIX, start);
        if (end < 0) {
            return;
        }
        String name =
                prompt.substring(start + FAULT_PREFIX.length(), end).trim().toUpperCase(Locale.ROOT);
        ProviderFailure failure;
        try {
            failure = ProviderFailure.valueOf(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new ProviderException(
                failure,
                provider.id(),
                model.modelId(),
                "Sandbox injected " + failure.name().toLowerCase(Locale.ROOT),
                failure == ProviderFailure.RATE_LIMITED ? 429 : null,
                failure == ProviderFailure.RATE_LIMITED ? Duration.ofSeconds(1) : null,
                null,
                null);
    }

    // ---- Determinism ---------------------------------------------------------------------

    // Seed derived from every role and message text, so an identical conversation gives an identical answer.
    private long seedOf(ChatRequest request) {
        String material = request.messages().stream()
                .map(message -> message.role() + ":" + message.content())
                .reduce("", String::concat);
        return digest(material);
    }

    // Gaussian vector seeded from the text digest and normalised to unit length.
    private float[] pseudoEmbedding(String text) {
        Random random = new Random(digest(text));
        float[] vector = new float[1536];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        // Normalised, because cosine similarity on unnormalised vectors is a subtle wrong answer
        // rather than an obvious one.
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }

    // First eight bytes of a SHA-256 digest as a long; falls back to the hash code if SHA-256 is unavailable.
    private static long digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFFL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            return material.hashCode();
        }
    }

    /**
     * The request itself, without any earlier-turns preamble a caller placed before it. Chat
     * prefixes follow-ups with a block ending in a "Request:" line; quoting that block's heading
     * back would name the context, not what was asked.
     */
    static String requestPart(String message) {
        int marker = message.lastIndexOf(REQUEST_MARKER);
        if (marker < 0) {
            return message;
        }
        String rest = message.substring(marker + REQUEST_MARKER.length());
        return rest.isBlank() ? message : rest;
    }

    // Marker that chat places before the actual request when it prefixes earlier turns.
    private static final String REQUEST_MARKER = "\nRequest:\n";

    // The text of the most recent user message, or null when there is none.
    private static String lastUserMessage(ChatRequest request) {
        return request.messages().reversed().stream()
                .filter(message -> message.role() == ChatMessage.Role.USER)
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
