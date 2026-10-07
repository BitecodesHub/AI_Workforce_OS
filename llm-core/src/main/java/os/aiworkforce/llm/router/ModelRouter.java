package os.aiworkforce.llm.router;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *       credential, breaker open, wrong capability, window too small even after compaction,
 *       budget spent. This costs nothing and each rejection is recorded with its reason.
 *   <li><b>Attempt.</b> Call the first surviving candidate, retrying it only for failures that
 *       could pass on a repeat, with jittered backoff and any {@code Retry-After} honoured.
 *   <li><b>Fall over.</b> On a failure that another model might not share, move to the next
 *       candidate. Some failures deliberately stop the chain instead.
 * </ol>
 *
 * <p>A spending cap is one of the things that stops the chain. Once the budget refuses a
 * candidate, cheaper candidates further down are not tried: quietly answering with a weaker
 * model nobody chose changes the quality of the work without anybody being told, which is what
 * a cap set to "stop" exists to prevent. The workspace can choose the offline model instead.
 *
 * <p>Bookkeeping never fails a call. Once a provider has answered, the answer has been paid for,
 * and a usage row that could not be written is logged rather than allowed to throw the answer
 * away and pay the next candidate for another.
 *
 * <p>Calls to one provider are limited the way the provider's own row says: no more than {@code
 * maxConcurrentRequests} at once and no more than {@code requestsPerMinute} in a minute, counted
 * separately for each workspace because each calls with its own key and account. A call that has
 * to wait for room waits, up to {@value #LIMIT_WAIT_SECONDS} seconds; one that still cannot go is
 * recorded as skipped and the chain moves on. A burst of runs therefore queues here, rather than
 * being sent to the provider to be answered with a 429 that fails them.
 *
 * <p>Every call that reaches a provider is also counted ({@value #CALL_METER}, with the cost
 * beside it), tagged by provider and outcome only.
 */
@Service
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final int ASSUMED_OUTPUT_TOKENS = 800;

    /** Latency of every call that reached a provider, tagged {@code provider} and {@code outcome}. */
    public static final String CALL_METER = "aiwos.llm.call";

    /** What those calls cost, in US dollars, tagged as {@link #CALL_METER} is. */
    public static final String COST_METER = "aiwos.llm.call.cost";

    /** Calls not made because the platform's own limit for the provider was reached. */
    public static final String THROTTLED_METER = "aiwos.llm.throttled";

    /** How long a call waits for its turn under a provider's limits before giving up on that candidate. */
    static final int LIMIT_WAIT_SECONDS = 20;

    /**
     * How long a workspace sets a model aside when its own account cannot pay for it. Long,
     * because credit and quota come back when somebody tops the account up, not in a minute.
     */
    private static final Duration ACCOUNT_COOLDOWN = Duration.ofHours(6);

    /**
     * How long a model the provider calls unknown is set aside. Short, because "not enabled on
     * this account" arrives as the same 404 as a real retirement. The registry notes it for the
     * workspace that was told, and for everybody only once a second workspace is told the same.
     */
    private static final Duration RETIRED_MODEL_COOLDOWN = Duration.ofMinutes(15);

    /**
     * Failures that describe one workspace's key or account rather than the provider's health.
     *
     * <p>They are kept out of the circuit breaker. A refused key or an empty account repeats on
     * every call, and counting it would pause a provider that is perfectly well; the remedy is a
     * new key or more credit, which the registry is told about instead.
     */
    private static final Set<ProviderFailure> ACCOUNT_FAILURES = EnumSet.of(
            ProviderFailure.AUTHENTICATION_FAILED,
            ProviderFailure.AUTHORISATION_FAILED,
            ProviderFailure.INSUFFICIENT_CREDIT,
            ProviderFailure.QUOTA_EXHAUSTED);

    private final Map<ProviderDescriptor.Kind, ChatProvider> adapters = new EnumMap<>(ProviderDescriptor.Kind.class);
    private final ProviderRegistry registry;
    private final CredentialResolver credentials;
    private final BudgetGuard budget;
    private final UsageRecorder usage;
    private final ResiliencePresets resilience;
    private final TranscriptCompactor compactor;
    /** One bulkhead and one rate limiter per provider and workspace, rebuilt when the provider's row changes. */
    private final Map<String, Limits> limits = new ConcurrentHashMap<>();

    private Duration limitWait = Duration.ofSeconds(LIMIT_WAIT_SECONDS);
    /** Where calls are counted. The global registry when none is injected, which records nowhere until one is added. */
    private MeterRegistry meters = Metrics.globalRegistry;

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

    @Autowired(required = false)
    void useMeterRegistry(MeterRegistry meters) {
        if (meters != null) {
            this.meters = meters;
        }
    }

    /** Shortens how long a call waits for its turn, for a test that would otherwise wait it out. */
    void useLimitWait(Duration wait) {
        this.limitWait = wait;
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
        BudgetGuard.Decision refusedByBudget = null;

        for (RoutingPolicy.Candidate candidate : policy.candidates()) {
            if (Instant.now().isAfter(deadline)) {
                skip(
                        context,
                        history,
                        AttemptRecord.skipped(
                                candidate.providerId(),
                                candidate.modelId(),
                                AttemptRecord.SkipReason.RATE_LIMIT_COOLDOWN,
                                "The overall deadline for this call had already passed."));
                break;
            }

            Resolution resolution = resolve(candidate, current, policy, context);
            if (resolution.skip() != null) {
                skip(context, history, resolution.skip());
                if (resolution.refusedByBudget() != null) {
                    // The cap stops the chain rather than letting it reach a cheaper candidate:
                    // see the class comment on why a cap never quietly downgrades the model.
                    refusedByBudget = resolution.refusedByBudget();
                    break;
                }
                continue;
            }

            // What this candidate is sent: the conversation as it stands, or a version shortened
            // to fit this candidate's window, which a larger candidate further down never sees.
            ChatRequest sent = resolution.request();
            Outcome outcome = attempt(resolution, sent, policy, context, deadline, history);

            // A context overflow is the one failure the request itself can fix. The provider has
            // just said this model is the one it overflowed, so the shortened conversation is
            // tried on this same model once before the chain moves on, and carried to the next.
            if (outcome.response() == null
                    && !outcome.stopChain()
                    && outcome.lastFailure() == ProviderFailure.CONTEXT_LENGTH_EXCEEDED
                    && policy.compactOnOverflow()) {
                ChatRequest compacted = compactor.compact(sent, resolution.model());
                if (compacted != null) {
                    current = compacted;
                    sent = compacted;
                    outcome = attempt(resolution, sent, policy, context, deadline, history);
                }
            }

            if (outcome.response() != null) {
                // Pictures turned into notes for a model that cannot see them is not a shortened
                // conversation: measured against what this candidate was meant to be sent.
                ChatRequest meant = resolution.baseline() != null && sent == resolution.baseline() ? sent : request;
                return answered(outcome.response(), meant, sent, history);
            }
            if (outcome.stopChain()) {
                throw terminal(outcome.lastFailure(), history);
            }
        }

        if (refusedByBudget != null) {
            return atBudgetCap(refusedByBudget, request, current, context, history);
        }

        if (policy.exhausted() == RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX) {
            log.warn(
                    "Every candidate failed for agent {}; falling back to the offline model as configured",
                    context.agentId());
            return answered(degradeToSandbox(current, context, history), request, current, history);
        }

        // Nothing was called because the credential store did not answer: that heals on its own,
        // so the caller is told to try again rather than that no model is configured.
        boolean nothingCalled = history.stream().allMatch(a -> a.outcome() == AttemptRecord.Outcome.SKIPPED);
        boolean storeUnreachable = history.stream()
                .anyMatch(a -> a.skipReason() == AttemptRecord.SkipReason.CREDENTIAL_UNAVAILABLE);
        if (nothingCalled && storeUnreachable) {
            throw new ApiException(
                            ErrorCode.DEPENDENCY_UNAVAILABLE,
                            "Could not reach the credential store, so no model could be asked. Try again in a"
                                    + " minute.")
                    .with("attempts", history.stream().map(AttemptRecord::summary).toList());
        }

        throw new ApiException(ErrorCode.NO_MODEL_AVAILABLE)
                .with("attempts", history.stream().map(AttemptRecord::summary).toList());
    }

    /**
     * What happens once the budget has refused: the offline model when the workspace chose it at
     * its cap, otherwise a 402 that says which cap was reached. Never retryable - the same call
     * a minute later meets the same cap - and never the routing policy's own sandbox fallback,
     * which is for providers failing, not for a cap a workspace set to stop.
     */
    private ChatResponse atBudgetCap(
            BudgetGuard.Decision decision,
            ChatRequest original,
            ChatRequest current,
            CallContext context,
            List<AttemptRecord> history) {
        if (decision.degradeToSandbox() && adapters.containsKey(ProviderDescriptor.Kind.SANDBOX)) {
            log.warn(
                    "Workspace {} is at a spending cap; answering with the offline model, as its budget is set to",
                    context.orgId());
            return answered(degradeToSandbox(current, context, history), original, current, history);
        }
        log.info("Workspace {} is at a spending cap; the call stops: {}", context.orgId(), decision.reason());
        throw new ApiException(ErrorCode.BUDGET_EXCEEDED, decision.reason())
                .with("attempts", history.stream().map(AttemptRecord::summary).toList());
    }

    /** The answer with the full trace attached, and the shortened conversation when there was one. */
    private static ChatResponse answered(
            ChatResponse response, ChatRequest original, ChatRequest sent, List<AttemptRecord> history) {
        ChatResponse traced = response.withAttempts(List.copyOf(history));
        return sent == original ? traced : traced.withCompactedConversation(sent.messages());
    }

    // ---- Stage one: disqualification -----------------------------------------------------

    /**
     * A candidate ready to call, or the reason it was skipped.
     *
     * @param request what to send this candidate: the conversation given, or a version shortened
     *     to fit its window
     * @param refusedByBudget the budget's refusal, when that was the reason for the skip
     * @param baseline the conversation this candidate is meant to be sent before any shortening:
     *     the one given, or that one with its pictures noted for a model that cannot see them
     */
    private record Resolution(
            ProviderDescriptor provider,
            ModelSpec model,
            ChatProvider adapter,
            String credential,
            ChatRequest request,
            AttemptRecord skip,
            BudgetGuard.Decision refusedByBudget,
            ChatRequest baseline) {

        static Resolution skipped(AttemptRecord record) {
            return new Resolution(null, null, null, null, null, record, null, null);
        }

        static Resolution refused(AttemptRecord record, BudgetGuard.Decision decision) {
            return new Resolution(null, null, null, null, null, record, decision, null);
        }
    }

    /**
     * Decides whether a candidate is worth calling.
     *
     * <p>Every rejection here is free and is recorded with a reason, which is what turns "the
     * agent was slow" into "OpenRouter had no credential and Groq's window was too small". The
     * order is cheapest check first, so a disabled provider costs nothing to reject.
     */
    private Resolution resolve(
            RoutingPolicy.Candidate candidate, ChatRequest request, RoutingPolicy policy, CallContext context) {
        String providerId = candidate.providerId();
        String modelId = candidate.modelId();

        Optional<ProviderDescriptor> maybeProvider = registry.provider(context.orgId(), providerId);
        if (maybeProvider.isEmpty() || !maybeProvider.get().enabled()) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.PROVIDER_DISABLED,
                    "The provider is not available to this workspace, or is switched off for it."));
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
                    "The provider recently reported that this model cannot be used here, so it is set aside"
                            + " for a while."));
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

        if (request.hasImages() && !(model.supportsVision() && adapter.sendsImages())) {
            // Not a reason to skip: an answer that says plainly the picture could not be read is
            // better than no answer, and the files' text still reaches the model. The pictures are
            // swapped for that note for this candidate only; a vision model further down the chain
            // still gets them.
            request = request.withImagesAsNotes(
                    model.displayName() == null || model.displayName().isBlank() ? modelId : model.displayName());
        }

        int promptTokens = request.estimatedPromptTokens();
        Integer requestedOutput =
                candidate.maxOutputTokens() != null ? candidate.maxOutputTokens() : request.maxOutputTokens();
        ChatRequest toSend = request;
        if (!model.canHold(promptTokens, requestedOutput) && policy.compactOnOverflow()) {
            // Shortened against this model's own window. Kept for this candidate only: a larger
            // model further down the chain can still take the whole conversation.
            ChatRequest compacted = compactor.compact(request, model);
            if (compacted != null && model.canHold(compacted.estimatedPromptTokens(), requestedOutput)) {
                toSend = compacted;
            }
        }
        if (toSend == request && !model.canHold(promptTokens, requestedOutput)) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.CONTEXT_TOO_SMALL,
                    "The conversation needs about " + promptTokens + " tokens and the model holds "
                            + model.contextWindowTokens() + "."));
        }
        promptTokens = toSend.estimatedPromptTokens();

        CircuitBreaker breaker = breaker(context.orgId(), providerId);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            return Resolution.skipped(AttemptRecord.skipped(
                    providerId,
                    modelId,
                    AttemptRecord.SkipReason.CIRCUIT_OPEN,
                    "Calls to this provider are paused after repeated failures."));
        }

        String credential = null;
        if (provider.requiresCredential()) {
            CredentialResolver.Lookup lookup = credentials.lookup(context.orgId(), provider.credentialRef());
            if (lookup instanceof CredentialResolver.Unavailable unavailable) {
                // Not "no credential": the store did not answer, and saying a key is missing
                // sends an administrator to re-enter a key that was never wrong.
                log.warn(
                        "Credential store unreachable for provider {} in workspace {}: {}",
                        providerId,
                        context.orgId(),
                        unavailable.reason());
                return Resolution.skipped(AttemptRecord.skipped(
                        providerId,
                        modelId,
                        AttemptRecord.SkipReason.CREDENTIAL_UNAVAILABLE,
                        "Could not reach the credential store."));
            }
            if (!(lookup instanceof CredentialResolver.Found found) || found.value().isBlank()) {
                return Resolution.skipped(AttemptRecord.skipped(
                        providerId,
                        modelId,
                        AttemptRecord.SkipReason.CREDENTIAL_MISSING,
                        "No credential is stored for this provider."));
            }
            credential = found.value();
        }

        BigDecimal estimate =
                model.estimateCost(promptTokens, requestedOutput != null ? requestedOutput : ASSUMED_OUTPUT_TOKENS);
        BudgetGuard.Decision decision = budget.check(context, estimate);
        if (!decision.allowed()) {
            return Resolution.refused(
                    AttemptRecord.skipped(
                            providerId, modelId, AttemptRecord.SkipReason.BUDGET_EXHAUSTED, decision.reason()),
                    decision);
        }

        return new Resolution(provider, model, adapter, credential, toSend, null, null, request);
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
        CircuitBreaker breaker = breaker(context.orgId(), provider.id());
        ProviderFailure lastFailure = ProviderFailure.UNKNOWN;

        for (int attempt = 1; attempt <= policy.maxAttemptsPerCandidate(); attempt++) {
            Instant startedAt = Instant.now();
            if (startedAt.isAfter(deadline)) {
                return Outcome.failed(ProviderFailure.TIMEOUT, false);
            }

            // Room under the provider's limits, waited for before the clock on the call starts: the
            // wait is ours, and neither the trace nor the latency figure should blame the provider
            // for it. Never inside the breaker either - a full queue says nothing about its health.
            Slot slot;
            try {
                slot = acquire(context.orgId(), provider);
            } catch (LimitReached busy) {
                skip(
                        context,
                        history,
                        AttemptRecord.skipped(
                                provider.id(),
                                model.modelId(),
                                AttemptRecord.SkipReason.RATE_LIMIT_COOLDOWN,
                                busy.getMessage()));
                throttled(provider.id(), busy.limit());
                return Outcome.failed(ProviderFailure.RATE_LIMITED, false);
            }
            startedAt = Instant.now();
            if (startedAt.isAfter(deadline)) {
                slot.release();
                return Outcome.failed(ProviderFailure.TIMEOUT, false);
            }

            try {
                ChatResponse response;
                try {
                    response = callThrough(breaker, () -> resolution
                            .adapter()
                            .complete(provider, model, request, resolution.credential())
                            .block(remaining(deadline)));
                } finally {
                    // Before any back-off below, so a call that is waiting to try again is not
                    // holding a place another run of the workspace could use.
                    slot.release();
                }

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
                BigDecimal cost = accountFor(context, record, model, response.usage());
                recordCall(provider.id(), "success", took, cost);
                return Outcome.success(response);

            } catch (CallNotPermittedException e) {
                // The breaker opened between the check and the call, which is ordinary under load.
                skip(
                        context,
                        history,
                        AttemptRecord.skipped(
                                provider.id(),
                                model.modelId(),
                                AttemptRecord.SkipReason.CIRCUIT_OPEN,
                                "Calls to this provider were paused while this request was in flight."));
                return Outcome.failed(ProviderFailure.SERVER_ERROR, false);

            } catch (Exception e) {
                ProviderException failure = reclassified(asProviderException(e, provider, model));
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
                // "failure" is the provider being unwell, by the same rule the breaker uses;
                // "rejected" is a request or an account it refused, which no alert should page on.
                recordCall(provider.id(), countsAgainstBreaker(e) ? "failure" : "rejected", took, BigDecimal.ZERO);

                reactToFailure(failure, provider, model, context);

                boolean lastTry = attempt >= policy.maxAttemptsPerCandidate();
                if (lastFailure.retrySameCandidate() && !lastTry) {
                    // Named by provider alone, unlike the breaker: a back-off is only a wait before
                    // the next try and holds no state, so there is nothing one workspace could
                    // leave behind for another. It also keeps any per-provider back-off setting
                    // keyed "provider.<id>" applying to every workspace.
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
                        ProviderFailure.MODEL_NOT_FOUND,
                        RETIRED_MODEL_COOLDOWN,
                        failure.getMessage());
            case INSUFFICIENT_CREDIT ->
                registry.markModelUnavailable(
                        context.orgId(),
                        provider.id(),
                        model.modelId(),
                        ProviderFailure.INSUFFICIENT_CREDIT,
                        ACCOUNT_COOLDOWN,
                        "The provider account does not have enough credit for this model.");
            // An exhausted account is not a refused key: the key is fine, the account is empty.
            // Recording it as a rejection sent people to replace a key that was never wrong.
            case QUOTA_EXHAUSTED ->
                registry.markModelUnavailable(
                        context.orgId(),
                        provider.id(),
                        model.modelId(),
                        ProviderFailure.QUOTA_EXHAUSTED,
                        ACCOUNT_COOLDOWN,
                        "The provider account has used up its quota or reached its spending cap.");
            case AUTHENTICATION_FAILED, AUTHORISATION_FAILED ->
                registry.markCredentialInvalid(context.orgId(), provider.id(), failure.getMessage());
            default -> {
                /* Nothing to write back for a transient failure. */
            }
        }
        if (failure.failure().operatorActionRequired()) {
            log.error("Provider {} needs attention: {} ({})", provider.id(), failure.failure(), failure.getMessage());
        }
    }

    /**
     * Writes down what an attempt cost. Never throws.
     *
     * <p>Called while the provider's answer is in hand, and that answer has already been paid
     * for. A failure here - a database error writing the usage row, say - used to surface as a
     * failed attempt, which threw the paid answer away and paid the next candidate for another.
     * A missing row is a gap in a report; a discarded answer is money spent twice.
     */
    private BigDecimal accountFor(CallContext context, AttemptRecord record, ModelSpec model, TokenUsage tokens) {
        BigDecimal cost;
        try {
            cost = tokens.cost(
                    model.inputCostPerMillion(), model.cachedInputCostPerMillion(), model.outputCostPerMillion());
        } catch (RuntimeException e) {
            log.warn("Could not price an attempt on {}/{}: {}", record.provider(), record.model(), e.toString());
            cost = BigDecimal.ZERO;
        }
        try {
            usage.record(context.orgId(), context.agentId(), context.runId(), record, cost);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record usage for {}/{} in workspace {}; the call itself stands: {}",
                    record.provider(),
                    record.model(),
                    context.orgId(),
                    e.toString());
        }
        if (cost.signum() > 0) {
            try {
                budget.record(context, cost);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not record spend of {} against the budget of workspace {}: {}",
                        cost,
                        context.orgId(),
                        e.toString());
            }
        }
        return cost;
    }

    /**
     * Adds a skipped candidate to the trace and to the usage table, where the spend report counts
     * skips. A skip costs nothing, and a row that could not be written is only logged.
     */
    private void skip(CallContext context, List<AttemptRecord> history, AttemptRecord record) {
        history.add(record);
        try {
            usage.record(context.orgId(), context.agentId(), context.runId(), record, BigDecimal.ZERO);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record a skipped candidate {}/{} in workspace {}: {}",
                    record.provider(),
                    record.model(),
                    context.orgId(),
                    e.toString());
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
        if (response == null) {
            throw new ApiException(ErrorCode.NO_MODEL_AVAILABLE)
                    .with(
                            "attempts",
                            history.stream().map(AttemptRecord::summary).toList());
        }
        history.add(AttemptRecord.succeeded("sandbox", "sandbox-1", Instant.now(), Duration.ZERO, TokenUsage.NONE));
        return response;
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

    /**
     * Corrects a classification an adapter cannot make from the status code alone.
     *
     * <p>Groq answers 413 both for a prompt too long for the window and for one request larger
     * than its tokens-per-minute allowance. The second is a throttle: compacting does not help,
     * and waiting (for the {@code Retry-After} it names) or asking another provider does.
     */
    static ProviderException reclassified(ProviderException failure) {
        if (failure.failure() != ProviderFailure.CONTEXT_LENGTH_EXCEEDED
                || failure.httpStatus() == null
                || failure.httpStatus() != 413) {
            return failure;
        }
        if (!ProviderFailure.isTokensPerMinuteLimit(failure.rawBody())
                && !ProviderFailure.isTokensPerMinuteLimit(failure.getMessage())) {
            return failure;
        }
        return new ProviderException(
                ProviderFailure.RATE_LIMITED,
                failure.provider(),
                failure.model(),
                failure.getMessage(),
                failure.httpStatus(),
                failure.retryAfter(),
                failure.rawBody(),
                failure);
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

    // ---- Limits and figures ----------------------------------------------------------------

    /** Which of a provider's two limits made a call wait too long. */
    private enum Limit {
        CONCURRENCY,
        RATE
    }

    /** Raised, and caught in {@link #attempt}, when a call could not get its turn in time. */
    private static final class LimitReached extends RuntimeException {

        private final transient Limit limit;

        LimitReached(Limit limit, String message) {
            super(message, null, false, false);
            this.limit = limit;
        }

        Limit limit() {
            return limit;
        }
    }

    /** A provider's two limits as one workspace uses them, with the figures they were built from. */
    private record Limits(int concurrent, Integer perMinute, Bulkhead bulkhead, RateLimiter limiter) {

        boolean matches(int wantedConcurrent, Integer wantedPerMinute) {
            return concurrent == wantedConcurrent && java.util.Objects.equals(perMinute, wantedPerMinute);
        }
    }

    /** A place under the bulkhead, held for the length of one provider call. */
    private static final class Slot {

        private static final Slot NONE = new Slot(null);

        private final Bulkhead bulkhead;
        private boolean held;

        private Slot(Bulkhead bulkhead) {
            this.bulkhead = bulkhead;
            this.held = bulkhead != null;
        }

        void release() {
            if (held) {
                held = false;
                bulkhead.onComplete();
            }
        }
    }

    /**
     * Waits for this workspace's turn at a provider: first its requests-per-minute allowance, then
     * a place among its concurrent calls. The rate comes first because a place is held while a
     * call is made, and waiting for the rate while holding one would leave it idle.
     *
     * @throws LimitReached when the turn did not come within the wait
     */
    private Slot acquire(String orgId, ProviderDescriptor provider) {
        if (provider.kind() == ProviderDescriptor.Kind.SANDBOX) {
            return Slot.NONE;
        }
        Limits mine = limitsFor(orgId, provider);
        if (mine.limiter() != null && !mine.limiter().acquirePermission()) {
            throw new LimitReached(
                    Limit.RATE,
                    "This workspace is sending requests to " + provider.id() + " faster than its limit of "
                            + mine.perMinute() + " a minute.");
        }
        try {
            mine.bulkhead().acquirePermission();
        } catch (BulkheadFullException full) {
            throw new LimitReached(
                    Limit.CONCURRENCY,
                    "This workspace already has " + mine.concurrent() + " calls to " + provider.id()
                            + " in flight, which is its limit.");
        }
        return new Slot(mine.bulkhead());
    }

    /**
     * The limits for one workspace's use of one provider, built the first time and again whenever
     * the provider's row changes them. The ceiling on concurrent calls is the row's, or the
     * platform preset's when the row names none; the requests-per-minute limit exists only when the
     * row sets one.
     *
     * <p>Named as the breaker is, so a per-provider override in configuration applies to every
     * workspace and a workspace's limits never touch another's.
     */
    private Limits limitsFor(String orgId, ProviderDescriptor provider) {
        String name = breakerName(orgId, provider.id());
        BulkheadConfig preset = resilience.bulkhead(name);
        Integer rowConcurrent = provider.maxConcurrentRequests();
        int concurrent = rowConcurrent != null && rowConcurrent > 0 ? rowConcurrent : preset.getMaxConcurrentCalls();
        Integer rowPerMinute = provider.requestsPerMinute();
        Integer perMinute = rowPerMinute != null && rowPerMinute > 0 ? rowPerMinute : null;
        return limits.compute(name, (key, existing) -> {
            if (existing != null && existing.matches(concurrent, perMinute)) {
                return existing;
            }
            Bulkhead bulkhead = Bulkhead.of(
                    name,
                    BulkheadConfig.from(preset)
                            .maxConcurrentCalls(concurrent)
                            .maxWaitDuration(limitWait)
                            .build());
            RateLimiter limiter = perMinute == null ? null : rateLimiter(name, perMinute);
            return new Limits(concurrent, perMinute, bulkhead, limiter);
        });
    }

    /**
     * A limiter that allows {@code perMinute} requests a minute, with a small burst.
     *
     * <p>Six or more a minute are granted in tenths of the minute: a sixth of the allowance every
     * ten seconds, so several runs starting together do not queue behind one another a second at
     * a time, and the minute's total never exceeds the allowance. Fewer than six a minute are
     * spread evenly, one at a time.
     */
    private RateLimiter rateLimiter(String name, int perMinute) {
        int perPeriod = perMinute >= 6 ? perMinute / 6 : 1;
        Duration period =
                perMinute >= 6 ? Duration.ofSeconds(10) : Duration.ofMillis(Math.max(1, 60_000L / perMinute));
        return RateLimiter.of(
                name,
                RateLimiterConfig.custom()
                        .limitForPeriod(perPeriod)
                        .limitRefreshPeriod(period)
                        .timeoutDuration(limitWait)
                        .build());
    }

    /** Counts one call that reached a provider. Bookkeeping, so it never fails the call it describes. */
    private void recordCall(String providerId, String outcome, Duration took, BigDecimal cost) {
        try {
            Timer.builder(CALL_METER)
                    .description("Calls to model providers, by provider and outcome")
                    .tag("provider", providerId)
                    .tag("outcome", outcome)
                    .serviceLevelObjectives(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(30),
                            Duration.ofSeconds(60))
                    .register(meters)
                    .record(took);
            Counter.builder(COST_METER)
                    .description("What calls to model providers cost, by provider and outcome")
                    .baseUnit("usd")
                    .tag("provider", providerId)
                    .tag("outcome", outcome)
                    .register(meters)
                    .increment(cost == null ? 0 : cost.doubleValue());
        } catch (RuntimeException e) {
            log.debug("Could not record the call to {} as a metric: {}", providerId, e.toString());
        }
    }

    private void throttled(String providerId, Limit limit) {
        try {
            Counter.builder(THROTTLED_METER)
                    .description("Calls held back by a provider's own limit instead of being sent")
                    .tag("provider", providerId)
                    .tag("limit", limit.name().toLowerCase())
                    .register(meters)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("Could not record a throttled call to {} as a metric: {}", providerId, e.toString());
        }
    }

    /**
     * Exposed for the health panel: which providers are currently paused for this workspace. The
     * same per-workspace breakers the router consults, so the panel never shows another
     * workspace's trouble.
     */
    public Map<String, String> providerHealth(String orgId) {
        Map<String, String> health = new java.util.LinkedHashMap<>();
        for (ProviderDescriptor provider : registry.providers(orgId)) {
            // Looked up, never created: a workspace that only opens the routing page must not leave
            // a breaker behind for every provider it can see. No breaker yet means no failures yet.
            health.put(
                    provider.id(),
                    resilience
                            .registry()
                            .find(breakerName(orgId, provider.id()))
                            .map(breaker -> breaker.getState().name())
                            .orElse(CircuitBreaker.State.CLOSED.name()));
        }
        return health;
    }

    /**
     * The breaker for one provider as one workspace uses it.
     *
     * <p>Per workspace, because the calls behind it are made with that workspace's own key and
     * account. A breaker shared by every workspace let one workspace's failures pause the provider
     * for all of them.
     */
    private CircuitBreaker breaker(String orgId, String providerId) {
        return resilience.circuitBreaker(breakerName(orgId, providerId));
    }

    /** The breaker's registry name; package-private so the tests can look a breaker up. */
    static String breakerName(String orgId, String providerId) {
        return "provider." + orgId + "." + providerId;
    }

    /**
     * Makes one call under the breaker, leaving account failures out of its statistics.
     *
     * <p>{@link CircuitBreaker#executeCallable} records every exception it sees that the breaker
     * does not ignore, and the breaker's own ignore rule only knows the platform's exceptions. So
     * the permission and the outcome are handled here: an account failure hands its permission
     * back without counting, and anything else is reported as the breaker would have recorded it.
     */
    private static ChatResponse callThrough(CircuitBreaker breaker, Callable<ChatResponse> call) throws Exception {
        breaker.acquirePermission();
        long start = breaker.getCurrentTimestamp();
        try {
            ChatResponse response = call.call();
            breaker.onSuccess(breaker.getCurrentTimestamp() - start, breaker.getTimestampUnit());
            return response;
        } catch (Exception e) {
            if (countsAgainstBreaker(e)) {
                breaker.onError(breaker.getCurrentTimestamp() - start, breaker.getTimestampUnit(), e);
            } else {
                breaker.releasePermission();
            }
            throw e;
        }
    }

    /**
     * Whether a failure says the provider is unwell. Not when it was the request's fault (a
     * safety refusal, a malformed or oversized request), nor when it was one workspace's key or
     * account.
     */
    static boolean countsAgainstBreaker(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProviderException provided) {
                ProviderFailure failure = reclassified(provided).failure();
                return failure.countsAgainstCircuit() && !ACCOUNT_FAILURES.contains(failure);
            }
            current = current.getCause();
        }
        return true;
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
