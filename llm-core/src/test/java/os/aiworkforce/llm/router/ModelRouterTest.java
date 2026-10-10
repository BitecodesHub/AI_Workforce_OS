// @find: tests for model router, routing, failover, retries, cool off, circuit breaker, budget, rate limit, provider failure, compaction, health
// @what: States the router's resilience behaviours as tests.
package os.aiworkforce.llm.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import os.aiworkforce.llm.usage.UsageRecorder;
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
    @DisplayName("a model that cannot read images is sent a plain note in place of each picture")
    void imagesBecomeNotesForATextOnlyModel() {
        registry.add("openrouter", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "llama");
        CapturingProvider capture = new CapturingProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        ModelRouter router = router(capture);

        ChatResponse response = router.route(imageRequest(), policy("openrouter/llama"), CONTEXT);

        assertThat(response.content()).isEqualTo("seen");
        ChatMessage sent = capture.seen.getFirst().messages().getFirst();
        assertThat(sent.hasImages()).isFalse();
        assertThat(sent.content()).contains("\"chart.png\" could not be shown to you: llama cannot read images");
        // A picture turned into a note is not a shortened conversation for the caller to adopt.
        assertThat(response.wasCompacted()).isFalse();
    }

    @Test
    @DisplayName("a vision model on an adapter that sends images is given the picture itself")
    void imagesReachAVisionModel() {
        registry.add("openrouter", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "vision");
        registry.models.put("openrouter/vision", visionSpec("openrouter", "vision"));
        CapturingProvider capture = new CapturingProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        ModelRouter router = router(capture);

        router.route(imageRequest(), policy("openrouter/vision"), CONTEXT);

        ChatMessage sent = capture.seen.getFirst().messages().getFirst();
        assertThat(sent.images()).singleElement().satisfies(image -> assertThat(image.name()).isEqualTo("chart.png"));
    }

    private static ChatRequest imageRequest() {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.userWithImages(
                        "What is in the chart?",
                        List.of(new os.aiworkforce.llm.model.ImagePart("chart.png", "image/png", "iVBORw0KGgo=")))))
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private static ModelSpec visionSpec(String providerId, String modelId) {
        return new ModelSpec(
                providerId, modelId, modelId, 128_000, 4096, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, null);
    }

    /** Answers every call, records what it was sent, and says it sends images like the OpenAI family. */
    private static final class CapturingProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;
        final List<ChatRequest> seen = new ArrayList<>();

        CapturingProvider(ProviderDescriptor.Kind kind) {
            this.kind = kind;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public boolean sendsImages() {
            return true;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            seen.add(request);
            return Mono.just(new ChatResponse(
                    "seen", List.of(), FinishReason.STOP, TokenUsage.of(10, 5), provider.id(), model.modelId(),
                    Duration.ofMillis(1), List.of(), Map.of()));
        }

        @Override
        public Flux<ChatChunk> stream(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            return Flux.just(ChatChunk.terminal(FinishReason.STOP, TokenUsage.NONE));
        }

        @Override
        public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
            return Mono.just(true);
        }
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
    @DisplayName("a model the provider called unknown is not set aside: the next call asks it again")
    void retiredModelIsAskedAgainNextTime() {
        registry.add("vendor", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "retired-model");
        registry.add("vendor2", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        AtomicInteger calls = new AtomicInteger();

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.MODEL_NOT_FOUND, calls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        router.route(request("hello"), policy("vendor/retired-model", "vendor2/model-b"), CONTEXT);
        router.route(request("hello"), policy("vendor/retired-model", "vendor2/model-b"), CONTEXT);

        // Not retried within a call (a 404 does not pass on a repeat), but asked again on the next.
        assertThat(calls.get()).isEqualTo(2);
        assertThat(registry.unavailable).isEmpty();
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
    @DisplayName("records every attempt, including the failures and retries, for the spend report")
    void recordsFailedAttemptsToo() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");

        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.TIMEOUT),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        // One try and two retries of the timed-out model, then the answer from the next.
        assertThat(recorded).hasSize(4);
        assertThat(recorded.subList(0, 3)).allMatch(r -> r.outcome() == AttemptRecord.Outcome.FAILED);
        assertThat(recorded.get(3).outcome()).isEqualTo(AttemptRecord.Outcome.SUCCEEDED);
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
                    public Decision check(ModelRouter.CallContext context, BigDecimal estimatedCost) {
                        return Decision.deny("Monthly cap reached.", BigDecimal.ZERO);
                    }

                    @Override
                    public void record(ModelRouter.CallContext context, BigDecimal actualCost) {
                        /* Nothing spent, because nothing was allowed. */
                    }
                },
                (orgId, agentId, runId, attempt, cost) -> recorded.add(attempt),
                resilience,
                new TranscriptCompactor());

        assertThatThrownBy(() -> router.route(request("hello"), policy("a/model-a"), CONTEXT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED));
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

    // ---- Every call tries again: retries, fall-through and the plain summary --------------

    private static ProviderException failure(ProviderFailure kind, String provider, Integer status, Duration retryAfter) {
        return new ProviderException(kind, provider, "m", "scripted", status, retryAfter, null, null);
    }

    @Test
    @DisplayName("a transient failure is retried twice with back-off, then the next candidate is asked")
    void transientFailuresAreRetriedTwice() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        SequenceProvider a = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        a.failWith(
                failure(ProviderFailure.SERVER_ERROR, "a", 500, null),
                failure(ProviderFailure.TIMEOUT, "a", null, null),
                failure(ProviderFailure.NETWORK_ERROR, "a", null, null));
        SequenceProvider b = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        List<Duration> waits = new ArrayList<>();
        ModelRouter router = router(a, b);
        router.useSleeper(waits::add);

        ChatResponse response = router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        assertThat(a.seen).hasSize(3);
        assertThat(waits).hasSize(2);
        assertThat(response.provider()).isEqualTo("b");
    }

    @Test
    @DisplayName("a retry that succeeds answers from the same candidate")
    void retryCanSucceed() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        SequenceProvider a = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        a.failWith(failure(ProviderFailure.RATE_LIMITED, "a", 429, Duration.ofSeconds(2)));
        SequenceProvider b = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        List<Duration> waits = new ArrayList<>();
        ModelRouter router = router(a, b);
        router.useSleeper(waits::add);

        ChatResponse response = router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("a");
        // A short Retry-After is honoured as the wait.
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
        assertThat(b.seen).isEmpty();
    }

    @Test
    @DisplayName("credit, key and request failures fall through at once, in order, without retries")
    void nonRetryableFailuresFallThroughImmediately() {
        registry.add("credit", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("key", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.add("ok", ProviderDescriptor.Kind.GEMINI, "model-c");
        SequenceProvider credit = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        credit.failWith(failure(ProviderFailure.INSUFFICIENT_CREDIT, "credit", 402, null));
        SequenceProvider key = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        key.failWith(failure(ProviderFailure.AUTHENTICATION_FAILED, "key", 401, null));
        SequenceProvider ok = new SequenceProvider(ProviderDescriptor.Kind.GEMINI);
        List<Duration> waits = new ArrayList<>();
        ModelRouter router = router(credit, key, ok);
        router.useSleeper(waits::add);

        ChatResponse response =
                router.route(request("hello"), policy("credit/model-a", "key/model-b", "ok/model-c"), CONTEXT);

        assertThat(response.provider()).isEqualTo("ok");
        assertThat(credit.seen).hasSize(1);
        assertThat(key.seen).hasSize(1);
        assertThat(waits).isEmpty();
        assertThat(response.attempts())
                .extracting(AttemptRecord::provider)
                .containsExactly("credit", "key", "ok");
    }

    @Test
    @DisplayName("a 429 with a long Retry-After is not waited out: the next candidate is asked")
    void longRetryAfterFallsThrough() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        SequenceProvider a = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        a.failWith(failure(ProviderFailure.RATE_LIMITED, "a", 429, Duration.ofSeconds(60)));
        SequenceProvider b = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        List<Duration> waits = new ArrayList<>();
        ModelRouter router = router(a, b);
        router.useSleeper(waits::add);

        assertThat(router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT).provider())
                .isEqualTo("b");
        assertThat(a.seen).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("an overloaded model is asked after the others for a moment, never dropped")
    void overloadedModelIsDeferredNotDropped() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        SequenceProvider a = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        a.failWith(failure(ProviderFailure.RATE_LIMITED, "a", 429, Duration.ofSeconds(60)));
        SequenceProvider b = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        ModelRouter router = router(a, b);
        router.useSleeper(wait -> {});
        RoutingPolicy chain = policy("a/model-a", "b/model-b");

        router.route(request("hello"), chain, CONTEXT);
        assertThat(router.coolingOff(ORG, "a", "model-a")).isTrue();

        // Within the cool-off, the next call goes to b first.
        assertThat(router.route(request("again"), chain, CONTEXT).provider()).isEqualTo("b");
        assertThat(a.seen).hasSize(1);

        // When b fails too, a is still asked: the cool-off only reorders.
        b.failWith(failure(ProviderFailure.MODEL_NOT_FOUND, "b", 404, null));
        assertThat(router.route(request("third"), chain, CONTEXT).provider()).isEqualTo("a");
        assertThat(a.seen).hasSize(2);

        // Forgetting it (Test now) clears it.
        router.forgetCoolOff(ORG, "a", "model-a");
        assertThat(router.coolingOff(ORG, "a", "model-a")).isFalse();
    }

    @Test
    @DisplayName("the only candidate is always attempted, even while it is cooling off")
    void lastCandidateIsAlwaysAttempted() {
        registry.add("only", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        SequenceProvider only = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        // A Retry-After over ten seconds is not waited out, so one failure ends the first call.
        only.failWith(failure(ProviderFailure.OVERLOADED, "only", 503, Duration.ofSeconds(20)));
        ModelRouter router = router(only);
        router.useSleeper(wait -> {});

        assertThatThrownBy(() -> router.route(request("hello"), policy("only/model-a"), CONTEXT))
                .isInstanceOf(ApiException.class);
        assertThat(router.coolingOff(ORG, "only", "model-a")).isTrue();

        ChatResponse answer = router.route(request("hello"), policy("only/model-a"), CONTEXT);
        assertThat(answer.provider()).isEqualTo("only");
        assertThat(only.seen).hasSize(2);
    }

    @Test
    @DisplayName("a policy asking for one attempt per model moves on after the first timeout")
    void policyAttemptsPerCandidateAreHonoured() {
        // Seen live: a chat's routing step waited three 20-second timeouts on NVIDIA before
        // OpenRouter, next in the policy, was asked at all.
        registry.add("nvidia", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "slow");
        registry.add("openrouter", ProviderDescriptor.Kind.ANTHROPIC, "fast");
        SequenceProvider nvidia = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        nvidia.failWith(
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null),
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null),
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null));
        SequenceProvider openrouter = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        ModelRouter router = router(nvidia, openrouter);
        router.useSleeper(wait -> {});
        RoutingPolicy once = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("nvidia", "slow"), RoutingPolicy.Candidate.of("openrouter", "fast")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                1,
                Duration.ofSeconds(20),
                true);

        ChatResponse answer = router.route(request("hello"), once, CONTEXT);

        assertThat(answer.provider()).isEqualTo("openrouter");
        assertThat(nvidia.seen).hasSize(1);
        assertThat(ModelRouter.attemptsFor(once)).isEqualTo(1);
        assertThat(ModelRouter.attemptsFor(new RoutingPolicy(List.of(), null, 10, null, true))).isEqualTo(3);
    }

    @Test
    @DisplayName("a model that never answers is given its budget, retries included, then the next is asked")
    void silentModelIsPassedOverAfterItsBudget() {
        // Seen live on 8 Oct 2026: a step waited two minutes on a model that never answered
        // before the working one next in the list was asked.
        registry.add("nvidia", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "silent");
        registry.add("openrouter", ProviderDescriptor.Kind.ANTHROPIC, "fast");
        HangingProvider nvidia = new HangingProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        SequenceProvider openrouter = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        ModelRouter router = router(nvidia, openrouter);
        router.useSleeper(wait -> {});
        router.useCandidateBudget(Duration.ofMillis(300));
        ChatRequest slowRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("hello")))
                .timeout(Duration.ofMinutes(2))
                .build();

        long started = System.nanoTime();
        ChatResponse answer = router.route(slowRequest, policy("nvidia/silent", "openrouter/fast"), CONTEXT);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(answer.provider()).isEqualTo("openrouter");
        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertThat(answer.attempts().getFirst().failure()).isEqualTo(ProviderFailure.TIMEOUT);
        assertThat(ModelRouter.CANDIDATE_BUDGET).isLessThanOrEqualTo(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("the last candidate is still asked once after the chain's own deadline has passed")
    void lastCandidateIsAskedPastTheDeadline() {
        registry.add("nvidia", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "silent");
        registry.add("openrouter", ProviderDescriptor.Kind.ANTHROPIC, "fast");
        HangingProvider nvidia = new HangingProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        SequenceProvider openrouter = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        ModelRouter router = router(nvidia, openrouter);
        router.useSleeper(wait -> {});
        router.useCandidateBudget(Duration.ofMillis(300));
        RoutingPolicy tight = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("nvidia", "silent"), RoutingPolicy.Candidate.of("openrouter", "fast")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                3,
                Duration.ofMillis(100),
                true);

        ChatResponse answer = router.route(request("hello"), tight, CONTEXT);

        assertThat(answer.provider()).isEqualTo("openrouter");
        assertThat(openrouter.seen).hasSize(1);
    }

    @Test
    @DisplayName("a blocking read that ran out of time counts as a timeout")
    void blockingTimeoutIsRecognised() {
        assertThat(ModelRouter.isBlockingTimeout(
                        new IllegalStateException("Timeout on blocking read for 300000000 NANOSECONDS")))
                .isTrue();
        assertThat(ModelRouter.isBlockingTimeout(new IllegalStateException("something else"))).isFalse();
    }

    @Test
    @DisplayName("when every model fails, the error names each one with a plain reason")
    void errorSummaryNamesEachCandidate() {
        registry.add("openrouter", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "llama");
        registry.add("nvidia", ProviderDescriptor.Kind.ANTHROPIC, "nemo");
        registry.add("keyless", ProviderDescriptor.Kind.GEMINI, "gem");
        registry.withoutCredential("keyless");
        SequenceProvider openrouter = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        openrouter.failWith(failure(ProviderFailure.INSUFFICIENT_CREDIT, "openrouter", 402, null));
        SequenceProvider nvidia = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        nvidia.failWith(
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null),
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null),
                failure(ProviderFailure.TIMEOUT, "nvidia", null, null));
        ModelRouter router = router(openrouter, nvidia, new ScriptedProvider(ProviderDescriptor.Kind.GEMINI, null));
        router.useSleeper(wait -> {});

        assertThatThrownBy(() -> router.route(
                        request("hello"), policy("openrouter/llama", "nvidia/nemo", "keyless/gem"), CONTEXT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
                    assertThat(e.getMessage())
                            .isEqualTo("No model could answer. openrouter \u00b7 llama: out of credit; nvidia \u00b7"
                                    + " nemo: timed out (3 tries); keyless \u00b7 gem: no key is stored. Every model"
                                    + " will be tried again on the next run.");
                });

        // And the next call tries all of them again.
        openrouter.failWith();
        assertThat(router.route(request("hello"), policy("openrouter/llama", "nvidia/nemo"), CONTEXT).provider())
                .isEqualTo("openrouter");
        assertThat(openrouter.seen).hasSize(2);
    }

    @Test
    @DisplayName("an empty chain fails with the plain 'no AI model is set' sentence")
    void emptyChainSaysNoModelIsSet() {
        ModelRouter router = router();
        assertThatThrownBy(() -> router.route(request("hello"), RoutingPolicy.of(List.of()), CONTEXT))
                .isInstanceOf(ApiException.class)
                .hasMessage(ModelRouter.NO_MODEL_SET);
    }

    // ---- One workspace's failures stay in that workspace ----------------------------------

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    @Test
    @DisplayName("repeated refused keys in one workspace never pause the provider for another")
    void refusedKeysInOneWorkspaceLeaveAnotherWorkspacesBreakerClosed() {
        registry.add("vendor", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        AtomicInteger calls = new AtomicInteger();
        ModelRouter router = router(new KeyedProvider(
                ProviderDescriptor.Kind.OPENAI_COMPATIBLE,
                "secret-" + ORG_A,
                ProviderFailure.AUTHENTICATION_FAILED,
                calls));

        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> router.route(request("hello"), policy("vendor/model-a"), context(ORG_A)))
                    .isInstanceOf(ApiException.class);
        }

        // Every refusal reached the provider: a refused key is the workspace's to fix, not a sign
        // the provider is unwell, so it does not count against any breaker - A's included.
        assertThat(calls.get()).isEqualTo(12);
        assertThat(breakerState(ORG_A, "vendor")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakerState(ORG_B, "vendor")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(router.providerHealth(ORG_B)).containsEntry("vendor", "CLOSED");

        ChatResponse answer = router.route(request("hello"), policy("vendor/model-a"), context(ORG_B));
        assertThat(answer.provider()).isEqualTo("vendor");
        // The rejection is written against A's key alone.
        assertThat(registry.rejected).containsExactly(ORG_A + "/vendor");
    }

    @Test
    @DisplayName("reading provider health for a workspace creates no breakers")
    void providerHealthCreatesNoBreakers() {
        registry.add("vendor", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        ModelRouter router = router(new KeyedProvider(
                ProviderDescriptor.Kind.OPENAI_COMPATIBLE,
                "secret-" + ORG_A,
                ProviderFailure.SERVER_ERROR,
                new AtomicInteger()));
        long before = resilience.registry().getAllCircuitBreakers().size();

        assertThat(router.providerHealth(ORG_B)).containsEntry("vendor", "CLOSED");

        assertThat(resilience.registry().getAllCircuitBreakers()).hasSize((int) before);
    }

    @Test
    @DisplayName("repeated failures never pause a provider: every later call still reaches it")
    void repeatedFailuresNeverPauseAProvider() {
        registry.add("vendor", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        AtomicInteger calls = new AtomicInteger();
        ModelRouter router = router(new KeyedProvider(
                ProviderDescriptor.Kind.OPENAI_COMPATIBLE,
                "secret-" + ORG_A,
                ProviderFailure.SERVER_ERROR,
                calls));
        router.useSleeper(wait -> {});

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> router.route(request("hello"), policy("vendor/model-a"), context(ORG_A)))
                    .isInstanceOf(ApiException.class);
        }

        // Ten calls, each with two retries, all reached the provider.
        assertThat(calls.get()).isEqualTo(30);
        assertThat(router.providerHealth(ORG_A)).containsEntry("vendor", "CLOSED");
        assertThat(router.route(request("hello"), policy("vendor/model-a"), context(ORG_B)).provider())
                .isEqualTo("vendor");
    }

    @Test
    @DisplayName("credit, quota and authorisation failures are not counted as the provider being unwell")
    void accountFailuresAreNotProviderFaults() {
        for (ProviderFailure failure : List.of(
                ProviderFailure.AUTHENTICATION_FAILED,
                ProviderFailure.AUTHORISATION_FAILED,
                ProviderFailure.INSUFFICIENT_CREDIT,
                ProviderFailure.QUOTA_EXHAUSTED)) {
            assertThat(ModelRouter.isProviderFault(failure)).as(failure.name()).isFalse();
        }
        assertThat(ModelRouter.isProviderFault(ProviderFailure.SERVER_ERROR)).isTrue();
    }

    @Test
    @DisplayName("an account out of credit or quota is not set aside: the next call asks it again")
    void accountFailuresAreNotRemembered() {
        registry.add("credit", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("quota", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.add("fallback", ProviderDescriptor.Kind.GEMINI, "model-c");
        AtomicInteger creditCalls = new AtomicInteger();

        ModelRouter router = router(
                new ScriptedProvider(
                        ProviderDescriptor.Kind.OPENAI_COMPATIBLE, ProviderFailure.INSUFFICIENT_CREDIT, creditCalls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, ProviderFailure.QUOTA_EXHAUSTED),
                new ScriptedProvider(ProviderDescriptor.Kind.GEMINI, null));

        RoutingPolicy chain = policy("credit/model-a", "quota/model-b", "fallback/model-c");
        router.route(request("hello"), chain, context(ORG_A));
        router.route(request("hello"), chain, context(ORG_A));

        // A 402 is not retried within a call, and is asked again on the next call.
        assertThat(creditCalls.get()).isEqualTo(2);
        assertThat(registry.causes).isEmpty();
        // An empty account is not a refused key: the key itself is left alone.
        assertThat(registry.rejected).isEmpty();
    }

    // ---- Spending caps ---------------------------------------------------------------------

    @Test
    @DisplayName("when the budget refuses every candidate the call fails with a 402 that is not retried")
    void budgetRefusalIsABudgetError() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        AtomicInteger calls = new AtomicInteger();
        ModelRouter router = routerWith(
                refusingBudget("This run reached its spending limit of $0.10.", false),
                (orgId, agentId, runId, attempt, cost) -> recorded.add(attempt),
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null, calls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null, calls));

        ApiException refused = catchApiException(
                () -> router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(refused.status()).isEqualTo(402);
        assertThat(refused.retryable()).isFalse();
        // The guard's own words reach the person, not a generic "no model available".
        assertThat(refused.getMessage()).isEqualTo("This run reached its spending limit of $0.10.");
        assertThat(calls.get()).isZero();
        // The skip is still written down, for the spend report.
        assertThat(recorded).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.BUDGET_EXHAUSTED));
    }

    @Test
    @DisplayName("a cap that stops one candidate does not let the chain reach a cheaper one")
    void capDoesNotQuietlyDowngrade() {
        registry.add("pricey", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("cheap", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        AtomicInteger cheapCalls = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        BudgetGuard firstRefusedOnly = new BudgetGuard() {
            @Override
            public Decision check(ModelRouter.CallContext context, BigDecimal estimatedCost) {
                return checks.getAndIncrement() == 0
                        ? Decision.deny("The workspace has reached its monthly model budget.", BigDecimal.ZERO)
                        : Decision.allow(null);
            }

            @Override
            public void record(ModelRouter.CallContext context, BigDecimal actualCost) {}
        };
        ModelRouter router = routerWith(
                firstRefusedOnly,
                (orgId, agentId, runId, attempt, cost) -> recorded.add(attempt),
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null, cheapCalls));

        ApiException refused = catchApiException(
                () -> router.route(request("hello"), policy("pricey/model-a", "cheap/model-b"), CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(cheapCalls.get()).isZero();
    }

    @Test
    @DisplayName("a fail-closed policy that degrades to the sandbox on failure still stops at a cap")
    void sandboxFallbackPolicyDoesNotBypassACap() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        ModelRouter router = routerWith(
                refusingBudget("The workspace has reached its monthly model budget.", false),
                UsageRecorder.NONE,
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new SandboxProvider(new ObjectMapper()));
        RoutingPolicy degrading = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("a", "model-a")),
                RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX,
                1,
                Duration.ofSeconds(20),
                true);

        ApiException refused = catchApiException(() -> router.route(request("hello"), degrading, CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("the router answers with the offline model at a cap only when the budget says to")
    void sandboxOnlyWhenTheBudgetChoosesIt() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        AtomicInteger realCalls = new AtomicInteger();
        ModelRouter router = routerWith(
                refusingBudget("The workspace has reached its monthly model budget.", true),
                UsageRecorder.NONE,
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null, realCalls),
                new SandboxProvider(new ObjectMapper()));

        ChatResponse response = router.route(request("hello"), policy("a/model-a"), CONTEXT);

        assertThat(response.provider()).isEqualTo("sandbox");
        assertThat(realCalls.get()).isZero();
    }

    // ---- Bookkeeping never fails a paid call ----------------------------------------------

    @Test
    @DisplayName("a usage row that cannot be written does not throw away the answer or pay a second candidate")
    void accountingFailureKeepsTheAnswer() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.models.put("a/model-a", priced("a", "model-a"));
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        BudgetGuard failingToRecord = new BudgetGuard() {
            @Override
            public Decision check(ModelRouter.CallContext context, BigDecimal estimatedCost) {
                return Decision.allow(null);
            }

            @Override
            public void record(ModelRouter.CallContext context, BigDecimal actualCost) {
                throw new IllegalStateException("optimistic lock failure");
            }
        };
        ModelRouter router = routerWith(
                failingToRecord,
                (orgId, agentId, runId, attempt, cost) -> {
                    throw new IllegalStateException("database is down");
                },
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null, firstCalls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null, secondCalls));

        ChatResponse response = router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("a");
        assertThat(firstCalls.get()).isEqualTo(1);
        assertThat(secondCalls.get()).isZero();
        assertThat(response.usedFallback()).isFalse();
    }

    // ---- A credential store that does not answer -------------------------------------------

    @Test
    @DisplayName("an unreachable credential store is a retryable dependency error, not 'no model available'")
    void unreachableCredentialStore() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.unreachableStore("a");
        registry.unreachableStore("b");
        AtomicInteger calls = new AtomicInteger();
        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null, calls),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null, calls));

        ApiException refused =
                catchApiException(() -> router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(refused.status()).isEqualTo(503);
        assertThat(refused.retryable()).isTrue();
        assertThat(calls.get()).isZero();
        assertThat(recorded).extracting(AttemptRecord::skipReason)
                .containsOnly(AttemptRecord.SkipReason.CREDENTIAL_UNAVAILABLE);
        assertThat(recorded.get(0).message()).isEqualTo("Could not reach the credential store.");
    }

    @Test
    @DisplayName("when the store is down for some candidates and the others have no key, it is still the store")
    void unreachableAndMissingTogether() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.unreachableStore("a");
        registry.withoutCredential("b");
        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        assertThat(catchApiException(() -> router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT))
                        .code())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("keys that are really missing are still 'no model available'")
    void missingKeysAreStillNoModel() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.withoutCredential("a");
        ModelRouter router = router(new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null));

        assertThat(catchApiException(() -> router.route(request("hello"), policy("a/model-a"), CONTEXT)).code())
                .isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
    }

    @Test
    @DisplayName("a store that is down for one candidate does not stop another from answering")
    void unreachableStoreForOneCandidateStillFailsOver() {
        registry.add("a", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a");
        registry.add("b", ProviderDescriptor.Kind.ANTHROPIC, "model-b");
        registry.unreachableStore("a");
        ModelRouter router = router(
                new ScriptedProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, null),
                new ScriptedProvider(ProviderDescriptor.Kind.ANTHROPIC, null));

        ChatResponse response = router.route(request("hello"), policy("a/model-a", "b/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("b");
        assertThat(response.attempts()).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.CREDENTIAL_UNAVAILABLE));
    }

    // ---- Compaction ------------------------------------------------------------------------

    private static List<ChatMessage> longConversation() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a careful assistant."));
        messages.add(ChatMessage.user("Thread: " + "context ".repeat(200) + "\nRequest: Draft the Q3 risk summary."));
        messages.add(ChatMessage.assistant("Understood."));
        for (int i = 0; i < 8; i++) {
            messages.add(ChatMessage.user("question " + i + " " + "q".repeat(1_200)));
            messages.add(ChatMessage.assistant("answer " + i + " " + "a".repeat(1_200)));
        }
        return messages;
    }

    private static ChatRequest conversationRequest() {
        return ChatRequest.builder().messages(longConversation()).timeout(Duration.ofSeconds(5)).build();
    }

    @Test
    @DisplayName("a candidate whose window is too small is compacted to fit, and not skipped")
    void compactsBeforeSkippingForWindow() {
        registry.add("small", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 6_000);
        SequenceProvider provider = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        ModelRouter router = router(provider);
        ChatRequest original = conversationRequest();

        ChatResponse response = router.route(original, policy("small/model-a"), CONTEXT);

        assertThat(response.provider()).isEqualTo("small");
        assertThat(provider.seen).hasSize(1);
        ChatRequest sent = provider.seen.get(0);
        assertThat(sent.messages().size()).isLessThan(original.messages().size());
        assertThat(response.attempts()).noneSatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.CONTEXT_TOO_SMALL));
        // What was sent is handed back, for a caller that keeps the conversation to adopt.
        assertThat(response.compactedConversation()).isEqualTo(sent.messages());
    }

    @Test
    @DisplayName("with compaction switched off the same candidate is skipped for its window")
    void noCompactionWhenSwitchedOff() {
        registry.add("small", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 6_000);
        SequenceProvider provider = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        ModelRouter router = router(provider);
        RoutingPolicy off = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("small", "model-a")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                1,
                Duration.ofSeconds(20),
                false);

        ApiException refused = catchApiException(() -> router.route(conversationRequest(), off, CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
        assertThat(provider.seen).isEmpty();
        assertThat(recorded).extracting(AttemptRecord::skipReason).contains(AttemptRecord.SkipReason.CONTEXT_TOO_SMALL);
    }

    @Test
    @DisplayName("a conversation shortened for a small candidate is not what a larger one is sent")
    void largerCandidateSeesTheWholeConversation() {
        registry.add("small", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 6_000);
        registry.add("large", ProviderDescriptor.Kind.ANTHROPIC, "model-b", true, 200_000);
        SequenceProvider small = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        small.failWith(ProviderException.of(ProviderFailure.MODEL_NOT_FOUND, "small", "model-a", "boom"));
        SequenceProvider large = new SequenceProvider(ProviderDescriptor.Kind.ANTHROPIC);
        ModelRouter router = router(small, large);
        ChatRequest original = conversationRequest();

        ChatResponse response = router.route(original, policy("small/model-a", "large/model-b"), CONTEXT);

        assertThat(response.provider()).isEqualTo("large");
        assertThat(small.seen.get(0).messages().size()).isLessThan(original.messages().size());
        assertThat(large.seen.get(0).messages()).isEqualTo(original.messages());
        assertThat(response.compactedConversation()).isNull();
    }

    @Test
    @DisplayName("after the provider reports an overflow, the conversation is shortened and the same candidate is tried again")
    void overflowCompactsAndRetriesTheSameCandidate() {
        registry.add("only", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 1_000_000);
        SequenceProvider provider = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        provider.failWith(ProviderException.of(
                ProviderFailure.CONTEXT_LENGTH_EXCEEDED, "only", "model-a", "maximum context length exceeded"));
        ModelRouter router = router(provider);
        ChatRequest original = conversationRequest();

        ChatResponse response = router.route(original, policy("only/model-a"), CONTEXT);

        assertThat(response.provider()).isEqualTo("only");
        assertThat(provider.seen).hasSize(2);
        assertThat(provider.seen.get(0).messages()).isEqualTo(original.messages());
        assertThat(provider.seen.get(1).messages().size()).isLessThan(original.messages().size());
        assertThat(response.compactedConversation()).isEqualTo(provider.seen.get(1).messages());
        assertThat(response.attempts()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.FAILED, AttemptRecord.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("an overflow that compaction cannot fix is not retried on the same candidate again and again")
    void overflowThatCannotBeFixedMovesOn() {
        registry.add("only", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 1_000_000);
        SequenceProvider provider = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        provider.failWith(
                ProviderException.of(ProviderFailure.CONTEXT_LENGTH_EXCEEDED, "only", "model-a", "too long"),
                ProviderException.of(ProviderFailure.CONTEXT_LENGTH_EXCEEDED, "only", "model-a", "still too long"),
                ProviderException.of(ProviderFailure.CONTEXT_LENGTH_EXCEEDED, "only", "model-a", "still too long"));
        ModelRouter router = router(provider);

        ApiException refused = catchApiException(() -> router.route(conversationRequest(), policy("only/model-a"), CONTEXT));

        assertThat(refused.code()).isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
        // One call, one shortened retry, and then the chain moves on: never a loop.
        assertThat(provider.seen).hasSize(2);
    }

    // ---- Tokens-per-minute 413s ------------------------------------------------------------

    private static ProviderException payloadTooLarge(String body, Duration retryAfter) {
        return new ProviderException(
                ProviderFailure.CONTEXT_LENGTH_EXCEEDED,
                "groq",
                "model-a",
                "Request too large",
                413,
                retryAfter,
                body,
                null);
    }

    @Test
    @DisplayName("a 413 that names tokens per minute is a rate limit, and keeps the provider's retry-after")
    void tokensPerMinute413IsRateLimited() {
        ProviderException throttled = ModelRouter.reclassified(payloadTooLarge(
                "{\"error\":{\"message\":\"Request too large for model on tokens per minute (TPM): Limit 6000, Requested 9000\"}}",
                Duration.ofSeconds(7)));

        assertThat(throttled.failure()).isEqualTo(ProviderFailure.RATE_LIMITED);
        assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(throttled.httpStatus()).isEqualTo(413);
    }

    @Test
    @DisplayName("a 413 that is only about length stays a context overflow, and so does another status")
    void plain413StaysAnOverflow() {
        assertThat(ModelRouter.reclassified(payloadTooLarge("{\"error\":\"prompt is too long\"}", null)).failure())
                .isEqualTo(ProviderFailure.CONTEXT_LENGTH_EXCEEDED);
        ProviderException badRequest = new ProviderException(
                ProviderFailure.CONTEXT_LENGTH_EXCEEDED,
                "groq",
                "model-a",
                "tokens per minute",
                400,
                null,
                "tokens per minute",
                null);
        assertThat(ModelRouter.reclassified(badRequest).failure()).isEqualTo(ProviderFailure.CONTEXT_LENGTH_EXCEEDED);
    }

    @Test
    @DisplayName("a tokens-per-minute 413 is waited out and retried as it was sent, not compacted")
    void tokensPerMinute413IsRetriedWithoutCompaction() {
        registry.add("groq", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "model-a", true, 1_000_000);
        SequenceProvider provider = new SequenceProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        provider.failWith(payloadTooLarge("Limit 6000 tokens per minute (TPM), Requested 9000", Duration.ofMillis(1)));
        ModelRouter router = router(provider);
        ChatRequest original = conversationRequest();
        RoutingPolicy twoTries = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("groq", "model-a")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                2,
                Duration.ofSeconds(20),
                true);

        ChatResponse response = router.route(original, twoTries, CONTEXT);

        assertThat(provider.seen).hasSize(2);
        assertThat(provider.seen.get(1).messages()).isEqualTo(original.messages());
        assertThat(response.compactedConversation()).isNull();
        assertThat(response.attempts().get(0).failure()).isEqualTo(ProviderFailure.RATE_LIMITED);
    }

    @Test
    @DisplayName("a provider's own quota running out is a provider error, never a workspace budget error")
    void quotaIsNotABudget() {
        assertThat(ProviderFailure.QUOTA_EXHAUSTED.toErrorCode()).isEqualTo(ErrorCode.PROVIDER_QUOTA_EXHAUSTED);
        assertThat(ProviderFailure.QUOTA_EXHAUSTED.toErrorCode()).isNotEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(ErrorCode.PROVIDER_QUOTA_EXHAUSTED.retryable()).isFalse();
        assertThat(ProviderFailure.INSUFFICIENT_CREDIT.toErrorCode()).isNotEqualTo(ErrorCode.BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("tokens per minute is recognised however the provider spells it, and 'tpm' inside a word is not")
    void tokensPerMinuteDetection() {
        assertThat(ProviderFailure.isTokensPerMinuteLimit("on tokens per minute (TPM): Limit 6000")).isTrue();
        assertThat(ProviderFailure.isTokensPerMinuteLimit("Rate limit hit: TPM")).isTrue();
        assertThat(ProviderFailure.isTokensPerMinuteLimit("{\"code\":\"rate_limit_exceeded\"}")).isTrue();
        assertThat(ProviderFailure.isTokensPerMinuteLimit("an adaptpmanager was mentioned")).isFalse();
        assertThat(ProviderFailure.isTokensPerMinuteLimit("maximum context length is 8192 tokens")).isFalse();
        assertThat(ProviderFailure.isTokensPerMinuteLimit(null)).isFalse();
        assertThat(ProviderFailure.forPayloadTooLarge("TPM exceeded")).isEqualTo(ProviderFailure.RATE_LIMITED);
        assertThat(ProviderFailure.forPayloadTooLarge("too many tokens"))
                .isEqualTo(ProviderFailure.CONTEXT_LENGTH_EXCEEDED);
    }

    @Test
    @DisplayName("the default credential lookup reports a blank or absent key as not found and never as unavailable")
    void defaultLookup() {
        CredentialResolver resolver = (orgId, ref) -> ref.equals("blank") ? Optional.of("  ") : Optional.empty();

        assertThat(resolver.lookup(ORG, "blank")).isSameAs(CredentialResolver.NotFound.INSTANCE);
        assertThat(resolver.lookup(ORG, "missing")).isSameAs(CredentialResolver.NotFound.INSTANCE);
        assertThat(new CredentialResolver.Found("secret").toString()).doesNotContain("secret");
    }

    // ---- Fixtures --------------------------------------------------------------------------

    private static ModelRouter.CallContext context(String orgId) {
        return new ModelRouter.CallContext(orgId, "agent-1", "run-1");
    }

    private CircuitBreaker.State breakerState(String orgId, String providerId) {
        return resilience.circuitBreaker(ModelRouter.breakerName(orgId, providerId)).getState();
    }

    @Test
    @DisplayName("calls an adapter with an ambient credential (an instance role) when no key is stored")
    void usesAmbientCredentialWhenNothingIsStored() {
        registry.add("bedrock", ProviderDescriptor.Kind.BEDROCK, "apac.amazon.nova-lite-v1:0");
        registry.withoutCredential("bedrock");
        List<String> seen = new ArrayList<>();
        ModelRouter router = router(new CredentialRecordingProvider(ProviderDescriptor.Kind.BEDROCK, true, seen));

        ChatResponse response = router.route(request("hello"), policy("bedrock/apac.amazon.nova-lite-v1:0"), CONTEXT);

        assertThat(response.provider()).isEqualTo("bedrock");
        assertThat(seen).containsExactly((String) null);
    }

    @Test
    @DisplayName("still skips a provider with no stored key when its adapter has no ambient credential")
    void skipsMissingKeyWithoutAmbientCredential() {
        registry.add("bedrock", ProviderDescriptor.Kind.BEDROCK, "apac.amazon.nova-lite-v1:0");
        registry.add("ollama", ProviderDescriptor.Kind.OPENAI_COMPATIBLE, "qwen2.5:1.5b-instruct");
        registry.keyless("ollama");
        registry.withoutCredential("bedrock");
        List<String> bedrockSeen = new ArrayList<>();
        List<String> ollamaSeen = new ArrayList<>();
        ModelRouter router = router(
                new CredentialRecordingProvider(ProviderDescriptor.Kind.BEDROCK, false, bedrockSeen),
                new CredentialRecordingProvider(ProviderDescriptor.Kind.OPENAI_COMPATIBLE, false, ollamaSeen));

        ChatResponse response = router.route(
                request("hello"),
                policy("bedrock/apac.amazon.nova-lite-v1:0", "ollama/qwen2.5:1.5b-instruct"),
                CONTEXT);

        // Bedrock is skipped for the missing key; the keyless local model answers with no key at all.
        assertThat(bedrockSeen).isEmpty();
        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(ollamaSeen).containsExactly((String) null);
        assertThat(response.attempts()).anySatisfy(attempt -> assertThat(attempt.skipReason())
                .isEqualTo(AttemptRecord.SkipReason.CREDENTIAL_MISSING));
    }

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

    private ModelRouter routerWith(BudgetGuard guard, UsageRecorder recorder, ChatProvider... providers) {
        return new ModelRouter(
                List.of(providers), registry, registry, guard, recorder, resilience, new TranscriptCompactor());
    }

    /** A budget that refuses every call, optionally saying the workspace chose the offline model. */
    private static BudgetGuard refusingBudget(String reason, boolean toSandbox) {
        return new BudgetGuard() {
            @Override
            public Decision check(ModelRouter.CallContext context, BigDecimal estimatedCost) {
                return toSandbox
                        ? Decision.denyToSandbox(reason, BigDecimal.ZERO)
                        : Decision.deny(reason, BigDecimal.ZERO);
            }

            @Override
            public void record(ModelRouter.CallContext context, BigDecimal actualCost) {}
        };
    }

    private static ApiException catchApiException(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class, call);
    }

    /** A model that costs a dollar per million tokens in and out, so an answer has a cost to record. */
    private static ModelSpec priced(String providerId, String modelId) {
        return new ModelSpec(
                providerId,
                modelId,
                modelId,
                128_000,
                4096,
                true,
                true,
                true,
                false,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                true,
                null);
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
        // The router's full retry allowance; a policy asking for fewer is asserted separately.
        return new RoutingPolicy(list, RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, 3, Duration.ofSeconds(20), true);
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
                        1.0, List.of("password"), false),
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
        private final java.util.Set<String> unreachableStore = new java.util.HashSet<>();
        private final Map<String, String> unavailable = new java.util.LinkedHashMap<>();
        private final Map<String, ProviderFailure> causes = new java.util.LinkedHashMap<>();
        /** Workspace/provider pairs whose key was refused, as the registry was told. */
        private final java.util.Set<String> rejected = new java.util.LinkedHashSet<>();

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

        /** The provider row names no credential at all, as a local keyless endpoint's does. */
        void keyless(String providerId) {
            ProviderDescriptor d = providers.get(providerId);
            providers.put(providerId, new ProviderDescriptor(
                    d.id(), d.displayName(), d.kind(), d.baseUrl(), null, d.enabled(), d.defaultHeaders(),
                    d.regions(), d.requestsPerMinute(), d.maxConcurrentRequests(), d.priority()));
        }

        /** The credential store does not answer for this provider's key. */
        void unreachableStore(String providerId) {
            unreachableStore.add(providerId);
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
                String orgId,
                String providerId,
                String modelId,
                ProviderFailure cause,
                Duration duration,
                String reason) {
            unavailable.put(providerId + "/" + modelId, reason);
            causes.put(orgId + "/" + providerId + "/" + modelId, cause);
        }

        @Override
        public void markCredentialInvalid(String orgId, String providerId, String reason) {
            rejected.add(orgId + "/" + providerId);
        }

        @Override
        public Lookup lookup(String orgId, String credentialRef) {
            String providerId = credentialRef == null ? "" : credentialRef.replace("ref-", "");
            if (unreachableStore.contains(providerId)) {
                return new Unavailable("HTTP 503 from the credential store");
            }
            return CredentialResolver.super.lookup(orgId, credentialRef);
        }

        /** The stored key is named after the workspace, so a provider can tell workspaces apart. */
        @Override
        public Optional<String> resolve(String orgId, String credentialRef) {
            String providerId = credentialRef == null ? "" : credentialRef.replace("ref-", "");
            return withoutCredential.contains(providerId) ? Optional.empty() : Optional.of("secret-" + orgId);
        }
    }

    /** A provider that fails with a named failure for one key only, and answers every other key. */
    private static final class KeyedProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;
        private final String failingKey;
        private final ProviderFailure failure;
        private final AtomicInteger failedCalls;

        KeyedProvider(
                ProviderDescriptor.Kind kind, String failingKey, ProviderFailure failure, AtomicInteger failedCalls) {
            this.kind = kind;
            this.failingKey = failingKey;
            this.failure = failure;
            this.failedCalls = failedCalls;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            if (failingKey.equals(credential)) {
                failedCalls.incrementAndGet();
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
            return Mono.just(true);
        }
    }

    /** A provider that never answers, as a stalled model does. */
    private static final class HangingProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;

        HangingProvider(ProviderDescriptor.Kind kind) {
            this.kind = kind;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            return Mono.never();
        }

        @Override
        public Flux<ChatChunk> stream(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            return Flux.never();
        }

        @Override
        public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
            return Mono.just(true);
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

    /**
     * A provider that fails with the exceptions it is given, one per call and in order, and answers
     * every call after that. Records every request it is sent.
     */
    private static final class SequenceProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;
        private final Deque<ProviderException> failures = new ArrayDeque<>();
        private final List<ChatRequest> seen = new ArrayList<>();

        SequenceProvider(ProviderDescriptor.Kind kind) {
            this.kind = kind;
        }

        void failWith(ProviderException... next) {
            failures.addAll(List.of(next));
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            seen.add(request);
            ProviderException next = failures.poll();
            if (next != null) {
                return Mono.error(next);
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
            return Mono.just(true);
        }
    }

    /** Answers every call and records the credential it was given; optionally has an ambient one. */
    private static final class CredentialRecordingProvider implements ChatProvider {
        private final ProviderDescriptor.Kind kind;
        private final boolean ambient;
        private final List<String> seen;

        CredentialRecordingProvider(ProviderDescriptor.Kind kind, boolean ambient, List<String> seen) {
            this.kind = kind;
            this.ambient = ambient;
            this.seen = seen;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public boolean hasAmbientCredential(ProviderDescriptor provider) {
            return ambient;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            seen.add(credential);
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
            return Mono.just(true);
        }
    }
}
