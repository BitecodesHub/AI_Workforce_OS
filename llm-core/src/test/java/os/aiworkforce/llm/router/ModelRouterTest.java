package os.aiworkforce.llm.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.provider.SandboxProvider;
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * The router's contract, stated as tests.
 *
 * <p>These are the behaviours the platform's resilience claims rest on, so each one is asserted
 * rather than assumed: that a failing provider is replaced without the caller noticing, that a
 * safety refusal is not routed around, that a candidate which cannot do the job is rejected before
 * it costs anything, and that every decision is visible afterwards in the trace.
 */
class ModelRouterTest {

    private static final String ORG = "org-1";
    private static final ModelRouter.CallContext CONTEXT = new ModelRouter.CallContext(ORG, "agent-1", "run-1");

    private StubRegistry registry;
    private ResiliencePresets resilience;
    private List<AttemptRecord> recorded;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        resilience = new ResiliencePresets(defaultProperties());
        recorded = new ArrayList<>();
    }

    @Test
    @DisplayName("falls over to the second candidate when the first is rate limited")
    void failsOverOnRateLimit() {
        registry.add("fast", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("slow", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.RATE_LIMITED),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        ChatResponse response = router.route(request("hello"), policy("fast/model-a", "slow/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("slow");
        assertThat(response.usedFallback()).isTrue();
        // The failure must remain visible: a silent recovery leaves nobody knowing which vendor
        // to chase when the bill or the latency is questioned.
        assertThat(response.attempts()).anySatisfy(attempt -> {
            assertThat(attempt.provider()).isEqualTo("fast");
            assertThat(attempt.outcome()).isEqualTo(AttemptRecord.Outcome.FAILED);
            assertThat(attempt.failure()).isEqualTo(ProviderFailure.RATE_LIMITED);
        });
    }

    @Test
    @DisplayName("does not route around a safety refusal")
    void contentFilterStopsTheChain() {
        registry.add("first", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("second", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        AtomicInteger secondCalls = new AtomicInteger();
        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.CONTENT_FILTERED),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null, secondCalls));

        assertThatThrownBy(() -> router.route(request("blocked"), policy("first/model-a", "second/model-b"), CONTEXT))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CONTENT_FILTERED);

        // Asking a second vendor the same blocked question is evasion, not resilience.
        assertThat(secondCalls.get()).isZero();
    }

    @Test
    @DisplayName("skips a candidate that cannot call tools, without spending a request")
    void skipsCandidateLackingToolSupport() {
        registry.add("noTools", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", false, 128_000);
        registry.add("withTools", ProviderDescriptor.Kind.ANTHROPIC, "model-b", true, 128_000);

        AtomicInteger firstCalls = new AtomicInteger();
        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null, firstCalls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        ChatRequest withTools = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("use a tool")))
                .tools(List.of(new os.aiworkforce.llm.model.ToolSpec(
                        "send_email", "Sends an email", null, os.aiworkforce.llm.model.ToolSpec.SideEffect.OUTBOUND)))
                .build();

        ChatResponse response = router.route(withTools, policy("noTools/model-a", "withTools/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("withTools");
        assertThat(firstCalls.get()).isZero();
        assertThat(response.attempts()).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.TOOLS_UNSUPPORTED));
    }

    @Test
    @DisplayName("skips a model whose context window is too small")
    void skipsCandidateWithTooSmallWindow() {
        registry.add("tiny", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 100);
        registry.add("large", ProviderDescriptor.Kind.ANTHROPIC, "model-b", true, 200_000);

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        ChatResponse response =
                router.route(request("x".repeat(4_000)), policy("tiny/model-a", "large/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("large");
        assertThat(response.attempts()).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.CONTEXT_TOO_SMALL));
    }

    @Test
    @DisplayName("skips a provider with no stored credential")
    void skipsCandidateWithoutCredential() {
        registry.add("unconfigured", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("configured", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.withoutCredential("unconfigured");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        ChatResponse response =
                router.route(request("hello"), policy("unconfigured/model-a", "configured/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("configured");
        assertThat(response.attempts()).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.CREDENTIAL_MISSING));
    }

    @Test
    @DisplayName("marks a model unavailable after the provider reports it does not exist")
    void marksRetiredModelUnavailable() {
        registry.add("vendor", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "retired-model");
        registry.add("vendor2", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.MODEL_NOT_FOUND),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        router.route(request("hello"), policy("vendor/retired-model", "vendor2/model-b"), CONTEXT);

        // Every later request must skip it for free rather than repeating the doomed round trip.
        assertThat(registry.unavailable).containsKey("vendor/retired-model");
    }

    @Test
    @DisplayName("refuses to answer when every candidate fails and the policy is fail-closed")
    void failsClosedWhenChainExhausted() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.SERVER_ERROR),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, ProviderFailure.SERVER_ERROR));

        assertThatThrownBy(() -> router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
    }

    @Test
    @DisplayName("records every attempt, including the failures, for the spend report")
    void recordsFailedAttemptsToo() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.TIMEOUT),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0).outcome()).isEqualTo(AttemptRecord.Outcome.FAILED);
        assertThat(recorded.get(1).outcome()).isEqualTo(AttemptRecord.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("stops a candidate when the workspace budget is spent")
    void respectsBudget() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");

        ModelRouter router = new ModelRouter(
                List.of(new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null)),
                registry,
                registry,
                new BudgetGuard() {
                    @Override
                    public Decision check(String orgId, String agentId, BigDecimal estimatedCost) {
                        return Decision.deny("Monthly cap reached.", BigDecimal.ZERO);
                    }

                    @Override
                    public void record(String orgId, String agentId, BigDecimal actualCost) {
                        /* Nothing spent, because nothing was allowed. */
                    }
                },
                (orgId, agentId, runId, attempt, cost) -> recorded.add(attempt),
                resilience,
                new TranscriptCompactor());

        assertThatThrownBy(() -> router.route(request("hello"), policy("a/model-a"), CONTEXT))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the sandbox provider answers identically for identical input")
    void sandboxIsDeterministic() {
        SandboxProvider sandbox = new SandboxProvider(new ObjectMapper());
        ProviderDescriptor descriptor = new ProviderDescriptor(
                "sandbox",
                "Sandbox",
                ProviderDescriptor.Kind.SANDBOX,
                "",
                null,
                true,
                Map.of(),
                List.of(),
                null,
                null,
                0);
        ModelSpec model = spec("sandbox", "sandbox-1", true, 128_000);

        ChatResponse first = sandbox.complete(descriptor, model, request("same question"), null)
                .block();
        ChatResponse second = sandbox.complete(descriptor, model, request("same question"), null)
                .block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.content()).isEqualTo(second.content());
    }

    // ---- Fixtures --------------------------------------------------------------------------

    private ModelRouter router(ChatProvider... providers) {
        return new ModelRouter(
                List.of(providers),
                registry,
                registry,
                BudgetGuard.UNLIMITED,
                (orgId, agentId, runId, attempt, cost) -> recorded.add(attempt),
                resilience,
                new TranscriptCompactor());
    }

    private static ChatRequest request(String text) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user(text)))
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private static RoutingPolicy policy(String... candidates) {
        List<RoutingPolicy.Candidate> list = new ArrayList<>();
        for (String candidate : candidates) {
            String[] parts = candidate.split("/", 2);
            list.add(RoutingPolicy.Candidate.of(parts[0], parts[1]));
        }
        // One attempt per candidate keeps the tests fast; retry behaviour is asserted separately.
        return new RoutingPolicy(list, RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, 1, Duration.ofSeconds(20), true);
    }

    private static ModelSpec spec(String providerId, String modelId, boolean tools, int window) {
        return new ModelSpec(
                providerId,
                modelId,
                modelId,
                window,
                4096,
                tools,
                true,
                true,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null);
    }

    private static PlatformProperties defaultProperties() {
        return new PlatformProperties(
                PlatformProperties.Environment.TEST,
                "test",
                "0.0.1",
                new PlatformProperties.Security(
                        "aiwos",
                        "aiwos-api",
                        "http://localhost/jwks",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        null,
                        null,
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        "test-secret",
                        "test-internal-secret",
                        new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                        new PlatformProperties.Encryption(null, "test", "AES/GCM/NoPadding", 12, 128),
                        12,
                        8,
                        Duration.ofMinutes(15),
                        false),
                new PlatformProperties.Http(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        100,
                        20,
                        10_485_760,
                        List.of("http://localhost"),
                        true,
                        Duration.ofMinutes(30)),
                new PlatformProperties.RateLimit(true, 600, 60, 30, 10, true),
                new PlatformProperties.Events(
                        false,
                        "aiwos",
                        3,
                        (short) 1,
                        Duration.ofSeconds(30),
                        4,
                        List.of(Duration.ofSeconds(1)),
                        true,
                        Duration.ofDays(7)),
                new PlatformProperties.Resilience(
                        50,
                        10,
                        5,
                        Duration.ofSeconds(30),
                        3,
                        3,
                        Duration.ofMillis(1),
                        Duration.ofMillis(5),
                        2.0,
                        false,
                        25,
                        Duration.ofSeconds(60),
                        Map.of()),
                new PlatformProperties.Observability(
                        "INFO", "console", false, 1.0, "http://localhost", false, List.of("password"), false),
                new PlatformProperties.RuntimeConfig(false, Duration.ofSeconds(60), "channel", true),
                new PlatformProperties.Services(
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost"));
    }

    /** A registry and credential resolver in one, so a test wires two dependencies with one object. */
    private static final class StubRegistry implements ProviderRegistry, CredentialResolver {
        private final Map<String, ProviderDescriptor> providers = new java.util.LinkedHashMap<>();
        private final Map<String, ModelSpec> models = new java.util.LinkedHashMap<>();
        private final java.util.Set<String> withoutCredential = new java.util.HashSet<>();
        private final Map<String, String> unavailable = new java.util.LinkedHashMap<>();

        void add(String providerId, ProviderDescriptor.Kind kind, String modelId) {
            add(providerId, kind, modelId, true, 128_000);
        }

        void add(String providerId, ProviderDescriptor.Kind kind, String modelId, boolean tools, int window) {
            providers.put(
                    providerId,
                    new ProviderDescriptor(
                            providerId,
                            providerId,
                            kind,
                            "http://localhost",
                            "ref-" + providerId,
                            true,
                            Map.of(),
                            List.of(),
                            null,
                            null,
                            0));
            models.put(providerId + "/" + modelId, spec(providerId, modelId, tools, window));
        }

        void withoutCredential(String providerId) {
            withoutCredential.add(providerId);
        }

        @Override
        public List<ProviderDescriptor> providers(String orgId) {
            return List.copyOf(providers.values());
        }

        @Override
        public Optional<ProviderDescriptor> provider(String orgId, String providerId) {
            return Optional.ofNullable(providers.get(providerId));
        }

        @Override
        public Optional<ModelSpec> model(String orgId, String providerId, String modelId) {
            if (unavailable.containsKey(providerId + "/" + modelId)) {
                return Optional.of(models.get(providerId + "/" + modelId).markUnavailableFor(Duration.ofHours(1)));
            }
            return Optional.ofNullable(models.get(providerId + "/" + modelId));
        }

        @Override
        public List<ModelSpec> models(String orgId) {
            return List.copyOf(models.values());
        }

        @Override
        public void markModelUnavailable(
                String orgId, String providerId, String modelId, Duration duration, String reason) {
            unavailable.put(providerId + "/" + modelId, reason);
        }

        @Override
        public void markCredentialInvalid(String orgId, String providerId, String reason) {
            withoutCredential.add(providerId);
        }

        @Override
        public Optional<String> resolve(String orgId, String credentialRef) {
            String providerId = credentialRef == null ? "" : credentialRef.replace("ref-", "");
            return withoutCredential.contains(providerId) ? Optional.empty() : Optional.of("secret");
        }
    }

    /** A provider that either fails with a named failure or answers successfully. */
    private static final class ScriptedProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;
        private final ProviderFailure failure;
        private final AtomicInteger calls;

        ScriptedProvider(ProviderDescriptor.Kind kind, ProviderFailure failure) {
            this(kind, failure, new AtomicInteger());
        }

        ScriptedProvider(ProviderDescriptor.Kind kind, ProviderFailure failure, AtomicInteger calls) {
            this.kind = kind;
            this.failure = failure;
            this.calls = calls;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            calls.incrementAndGet();
            if (failure != null) {
                return Mono.error(ProviderException.of(failure, provider.id(), model.modelId(), "scripted"));
            }
            return Mono.just(new ChatResponse(
                    "answer from " + provider.id(),
                    List.of(),
                    FinishReason.STOP,
                    TokenUsage.of(10, 5),
                    provider.id(),
                    model.modelId(),
                    Duration.ofMillis(1),
                    List.of(),
                    Map.of()));
        }

        @Override
        public Flux<ChatChunk> stream(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            return Flux.just(ChatChunk.terminal(FinishReason.STOP, TokenUsage.NONE));
        }

        @Override
        public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
            return Mono.just(failure == null);
        }
    }
}
