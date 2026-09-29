package os.aiworkforce.llm.router;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.llm.usage.UsageRecorder;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * Chooses a model, calls it, and keeps going when it fails.
 *
 * <p>This is the only place in the platform that decides which model answers. Everything above it
 * asks for an answer and states its requirements; everything below it speaks one vendor's
 * protocol. The consequence worth stating: an agent never names a provider, so replacing one is a
 * database change.
 *
 * <p>The work happens in three stages, and keeping them separate is what makes the behaviour
 * explainable to the person reading a run trace:
 *
 * <ol>
 *   <li><b>Disqualify.</b> Before any call, remove candidates that cannot work - disabled, no
 *       credential, breaker open, wrong capability, window too small, budget spent. This costs
 *       nothing and each rejection is recorded with its reason.
 *   <li><b>Attempt.</b> Call the first surviving candidate, retrying it only for failures that
 *       could pass on a repeat, with jittered backoff and any {@code Retry-After} honoured.
 *   <li><b>Fall over.</b> On a failure that another model might not share, move to the next
 *       candidate. Some failures deliberately stop the chain instead.
 * </ol>
 */
@Service
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final int ASSUMED_OUTPUT_TOKENS = 800;
    private static final Duration MODEL_UNAVAILABLE_COOLDOWN = Duration.ofHours(6);

    private final Map<ProviderDescriptor.Kind, ChatProvider> adapters = new EnumMap<>(ProviderDescriptor.Kind.class);
    private final ProviderRegistry registry;
    private final CredentialResolver credentials;
    private final BudgetGuard budget;
    private final UsageRecorder usage;
    private final ResiliencePresets resilience;
    private final TranscriptCompactor compactor;

    public ModelRouter(
            List<ChatProvider> providers,
            ProviderRegistry registry,
            CredentialResolver credentials,
            BudgetGuard budget,
            UsageRecorder usage,
            ResiliencePresets resilience,
            TranscriptCompactor compactor) {
        providers.forEach(provider -> adapters.put(provider.kind(), provider));
        this.registry = registry;
        this.credentials = credentials;
        this.budget = budget;
        this.usage = usage;
        this.resilience = resilience;
        this.compactor = compactor;
    }

    /** The context a call is made in, for budgeting, accounting and the trace. */
    public record CallContext(String orgId, String agentId, String runId) {}

    /**
     * Runs the chain and returns the first answer, or fails with every reason recorded.
     *
     * <p>Synchronous rather than reactive at this boundary. The orchestrator runs on virtual
     * threads, so blocking here costs a stack rather than a platform thread, and the sequential
     * shape of "try, fail, try the next" is far clearer written as a loop than as a chain of
     * reactive operators.
     */
    public ChatResponse route(ChatRequest request, RoutingPolicy policy, CallContext context) {
        if (policy.isEmpty()) {
            throw new ApiException(
                    ErrorCode.NO_MODEL_AVAILABLE,
                    "This agent has no model routing policy. Add one in the agent's settings.");
        }

        List<AttemptRecord> history = new ArrayList<>();
        Instant deadline =
                policy.overallDeadline() == null ? Instant.MAX : Instant.now().plus(policy.overallDeadline());
        ChatRequest current = request;

        for (RoutingPolicy.Candidate candidate : policy.candidates()) {
            if (Instant.now().isAfter(deadline)) {
                history.add(AttemptRecord.skipped(
                        candidate.providerId(),
                        candidate.modelId(),
                        AttemptRecord.SkipReason.RATE_LIMIT_COOLDOWN,
                        "The overall deadline for this call had already passed."));
                break;
            }

            Resolution resolution = resolve(candidate, current, context);
            if (resolution.skip() != null) {
                history.add(resolution.skip());
                continue;
            }

            Outcome outcome = attempt(resolution, current, policy, context, deadline, history);
            if (outcome.response() != null) {
                return outcome.response().withAttempts(List.copyOf(history));
            }
            if (outcome.stopChain()) {
                throw terminal(outcome.lastFailure(), history);
            }
            // A context overflow is the one failure the request itself can fix: shortening the
            // conversation may let the very next candidate - or this one - succeed.
            if (outcome.lastFailure() == ProviderFailure.CONTEXT_LENGTH_EXCEEDED && policy.compactOnOverflow()) {
                ChatRequest compacted = compactor.compact(current, resolution.model());
                if (compacted != null) {
                    current = compacted;
                }
            }
        }

        if (policy.exhausted() == RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX) {
            log.warn(
                    "Every candidate failed for agent {}; falling back to the offline model as configured",
                    context.agentId());
            return degradeToSandbox(current, context, history);
        }

        throw new ApiException(ErrorCode.NO_MODEL_AVAILABLE)
                .with("attempts", history.stream().map(AttemptRecord::summary).toList());
    }

    // ---- Stage one: disqualification -----------------------------------------------------

    private record Resolution(
            ProviderDescriptor provider, ModelSpec model, ChatProvider adapter, String credential, AttemptRecord skip) {

        static Resolution skipped(AttemptRecord record) {
            return new Resolution(null, null, null, null, record);
        }
    }

    /**
     * Decides whether a candidate is worth calling.
     *
     * <p>Every rejection here is free and is recorded with a reason, which is what turns "the
     * agent was slow" into "OpenRouter had no credential and Groq's window was too small". The
     * order is cheapest check first, so a disabled provider costs nothing to reject.
     */
    private Resolution resolve(RoutingPolicy.Candidate candidate, ChatRequest request, CallContext context) {
        String providerId = candidate.providerId();
        String modelId = candidate.modelId();

        Optional<ProviderDescriptor> maybeProvider = registry.provider(context.orgId(), providerId);
        if (maybeProvider.isEmpty() || !maybeProvider.get().enabled()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.PROVIDER_DISABLED,
                    "The provider is not configured or is switched off for this workspace."));
        }
        ProviderDescriptor provider = maybeProvider.get();

        Optional<ModelSpec> maybeModel = registry.model(context.orgId(), providerId, modelId);
        if (maybeModel.isEmpty() || !maybeModel.get().enabled()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.MODEL_DISABLED,
                    "The model is not configured or is switched off."));
        }
        ModelSpec model = maybeModel.get();

        if (model.isCurrentlyUnavailable()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.MODEL_MARKED_UNAVAILABLE,
                    "The provider recently reported that this model does not exist."));
        }

        ChatProvider adapter = adapters.get(provider.kind());
        if (adapter == null) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.PROVIDER_DISABLED,
                    "No adapter is installed for this provider kind."));
        }

        // Capability checks come before the credential lookup: there is no point decrypting a
        // secret for a model that cannot do what this call needs.
        if (request.requireToolSupport() && !model.supportsTools()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.TOOLS_UNSUPPORTED,
                    "This task needs tool calling, which the model does not support."));
        }
        if (request.jsonMode() && !model.supportsJsonMode()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.JSON_MODE_UNSUPPORTED,
                    "This task needs a strict JSON answer, which the model does not support."));
        }

        int promptTokens = request.estimatedPromptTokens();
        Integer requestedOutput =
                candidate.maxOutputTokens() != null ? candidate.maxOutputTokens() : request.maxOutputTokens();
        if (!model.canHold(promptTokens, requestedOutput)) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.CONTEXT_TOO_SMALL,
                    "The conversation needs about " + promptTokens + " tokens and the model holds "
                            + model.contextWindowTokens() + "."));
        }

        CircuitBreaker breaker = resilience.circuitBreaker("provider." + providerId);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.CIRCUIT_OPEN,
                    "Calls to this provider are paused after repeated failures."));
        }

        String credential = null;
        if (provider.requiresCredential()) {
            Optional<String> resolved = credentials.resolve(context.orgId(), provider.credentialRef());
            if (resolved.isEmpty() || resolved.get().isBlank()) {
                return Resolution.skipped(AttemptRecord.skipped(
                        providerId,
                        modelId,
                        AttemptRecord.SkipReason.CREDENTIAL_MISSING,
                        "No credential is stored for this provider."));
            }
            credential = resolved.get();
        }

        BigDecimal estimate =
                model.estimateCost(promptTokens, requestedOutput != null ? requestedOutput : ASSUMED_OUTPUT_TOKENS);
        BudgetGuard.Decision decision = budget.check(context.orgId(), context.agentId(), estimate);
        if (!decision.allowed()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId, modelId, AttemptRecord.SkipReason.BUDGET_EXHAUSTED, decision.reason()));
        }

        return new Resolution(provider, model, adapter, credential, null);
    }

    // ---- Stages two and three: attempt and fall over --------------------------------------

    private record Outcome(ChatResponse response, ProviderFailure lastFailure, boolean stopChain) {

        static Outcome success(ChatResponse response) {
            return new Outcome(response, null, false);
        }

        static Outcome failed(ProviderFailure failure, boolean stopChain) {
            return new Outcome(null, failure, stopChain);
        }
    }

    private Outcome attempt(
            Resolution resolution,
            ChatRequest request,
            RoutingPolicy policy,
            CallContext context,
            Instant deadline,
            List<AttemptRecord> history) {

        ProviderDescriptor provider = resolution.provider();
        ModelSpec model = resolution.model();
        CircuitBreaker breaker = resilience.circuitBreaker("provider." + provider.id());
        ProviderFailure lastFailure = ProviderFailure.UNKNOWN;

        for (int attempt = 1; attempt <= policy.maxAttemptsPerCandidate(); attempt++) {
            Instant startedAt = Instant.now();
            if (startedAt.isAfter(deadline)) {
                return Outcome.failed(ProviderFailure.TIMEOUT, false);
            }

            try {
                ChatResponse response = breaker.executeCallable(() -> resolution
                        .adapter()
                        .complete(provider, model, request, resolution.credential())
                        .block(remaining(deadline)));

                if (response == null) {
                    throw ProviderException.of(
                            ProviderFailure.MALFORMED_RESPONSE,
                            provider.id(),
                            model.modelId(),
                            "The provider returned nothing.");
                }

                Duration took = Duration.between(startedAt, Instant.now());
                AttemptRecord record =
                        AttemptRecord.succeeded(provider.id(), model.modelId(), startedAt, took, response.usage());
                history.add(record);
                accountFor(context, record, model, response.usage());
                return Outcome.success(response);

            } catch (CallNotPermittedException e) {
                // The breaker opened between the check and the call, which is ordinary under load.
                history.add(AttemptRecord.skipped(
                        provider.id(),
                        model.modelId(),
                        AttemptRecord.SkipReason.CIRCUIT_OPEN,
                        "Calls to this provider were paused while this request was in flight."));
                return Outcome.failed(ProviderFailure.SERVER_ERROR, false);

            } catch (Exception e) {
                ProviderException failure = asProviderException(e, provider, model);
                lastFailure = failure.failure();
                // The trace keeps only the failure's category; the provider's own words are what
                // an operator needs to fix a rejected request, so they go to the log as well.
                log.warn(
                        "Provider {} model {} failed ({}, HTTP {}): {}",
                        provider.id(),
                        model.modelId(),
                        lastFailure,
                        failure.httpStatus(),
                        failure.getMessage());

                Duration took = Duration.between(startedAt, Instant.now());
                AttemptRecord record = AttemptRecord.failed(
                        provider.id(),
                        model.modelId(),
                        lastFailure,
                        failure.getMessage(),
                        startedAt,
                        took,
                        failure.httpStatus(),
                        TokenUsage.NONE);
                history.add(record);
                accountFor(context, record, model, TokenUsage.NONE);

                reactToFailure(failure, provider, model, context);

                boolean lastTry = attempt >= policy.maxAttemptsPerCandidate();
                if (lastFailure.retrySameCandidate() && !lastTry) {
                    Duration wait = failure.retryAfter() != null
                            ? failure.retryAfter()
                            : resilience.backoff("provider." + provider.id(), attempt);
                    if (Instant.now().plus(wait).isAfter(deadline)) {
                        return Outcome.failed(lastFailure, false);
                    }
                    sleep(wait);
                    continue;
                }

                // A safety refusal is the one failure the chain must not route around.
                return Outcome.failed(lastFailure, !lastFailure.tryNextCandidate());
            }
        }
        return Outcome.failed(lastFailure, false);
    }

    /**
     * Does the bookkeeping a failure implies, beyond deciding what to try next.
     *
     * <p>A retired model and a rejected credential both keep failing until somebody changes
     * something, so each is written back to the registry: otherwise every request pays the same
     * doomed round trip, and the operator sees latency rather than the actual problem.
     */
    private void reactToFailure(
            ProviderException failure, ProviderDescriptor provider, ModelSpec model, CallContext context) {
        switch (failure.failure()) {
            case MODEL_NOT_FOUND ->
                registry.markModelUnavailable(
                        context.orgId(),
                        provider.id(),
                        model.modelId(),
                        MODEL_UNAVAILABLE_COOLDOWN,
                        failure.getMessage());
            case INSUFFICIENT_CREDIT ->
                registry.markModelUnavailable(
                        context.orgId(),
                        provider.id(),
                        model.modelId(),
                        MODEL_UNAVAILABLE_COOLDOWN,
                        "The provider account does not have enough credit for this model.");
            case AUTHENTICATION_FAILED, AUTHORISATION_FAILED, QUOTA_EXHAUSTED ->
                registry.markCredentialInvalid(context.orgId(), provider.id(), failure.getMessage());
            default -> {
                /* Nothing to write back for a transient failure. */
            }
        }
        if (failure.failure().operatorActionRequired()) {
            log.error("Provider {} needs attention: {} ({})", provider.id(), failure.failure(), failure.getMessage());
        }
    }

    private void accountFor(CallContext context, AttemptRecord record, ModelSpec model, TokenUsage tokens) {
        BigDecimal cost = tokens.cost(
                model.inputCostPerMillion(), model.cachedInputCostPerMillion(), model.outputCostPerMillion());
        usage.record(context.orgId(), context.agentId(), context.runId(), record, cost);
        if (cost.signum() > 0) {
            budget.record(context.orgId(), context.agentId(), cost);
        }
    }

    private ChatResponse degradeToSandbox(ChatRequest request, CallContext context, List<AttemptRecord> history) {
        ChatProvider sandbox = adapters.get(ProviderDescriptor.Kind.SANDBOX);
        if (sandbox == null) {
            throw new ApiException(ErrorCode.NO_MODEL_AVAILABLE)
                    .with(
                            "attempts",
                            history.stream().map(AttemptRecord::summary).toList());
        }
        ProviderDescriptor descriptor = new ProviderDescriptor(
                "sandbox",
                "Offline sandbox",
                ProviderDescriptor.Kind.SANDBOX,
                "",
                null,
                true,
                Map.of(),
                List.of(),
                null,
                null,
                999);
        ModelSpec model = new ModelSpec(
                "sandbox",
                "sandbox-1",
                "Offline sandbox",
                128_000,
                4096,
                true,
                true,
                true,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null);
        ChatResponse response =
                sandbox.complete(descriptor, model, request, null).block(Duration.ofSeconds(10));
        history.add(AttemptRecord.succeeded("sandbox", "sandbox-1", Instant.now(), Duration.ZERO, TokenUsage.NONE));
        return response == null ? null : response.withAttempts(List.copyOf(history));
    }

    private ApiException terminal(ProviderFailure failure, List<AttemptRecord> history) {
        return new ApiException(failure.toErrorCode())
                .with("attempts", history.stream().map(AttemptRecord::summary).toList());
    }

    private static ProviderException asProviderException(Exception e, ProviderDescriptor provider, ModelSpec model) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ProviderException provided) {
                return provided;
            }
            current = current.getCause();
        }
        return ProviderException.of(
                ProviderFailure.UNKNOWN,
                provider.id(),
                model.modelId(),
                "The provider call failed: " + e.getClass().getSimpleName(),
                e);
    }

    private static Duration remaining(Instant deadline) {
        if (deadline == Instant.MAX) {
            return Duration.ofMinutes(5);
        }
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ofMillis(1) : left;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The request was cancelled.", e);
        }
    }

    /** Exposed for the health panel: which providers are currently paused, and why. */
    public Map<String, String> providerHealth(String orgId) {
        Map<String, String> health = new java.util.LinkedHashMap<>();
        for (ProviderDescriptor provider : registry.providers(orgId)) {
            CircuitBreaker breaker = resilience.circuitBreaker("provider." + provider.id());
            health.put(provider.id(), breaker.getState().name());
        }
        return health;
    }

    /** The first message of a conversation, used by callers that log a prompt summary. */
    static String firstUserText(ChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.USER)
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("");
    }
}
