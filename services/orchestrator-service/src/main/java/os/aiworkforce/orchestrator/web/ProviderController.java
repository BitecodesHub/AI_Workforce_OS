package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Exceptions;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceModel;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Model providers, their models and their health, as the calling workspace sees them.
 *
 * <p>This is what makes fallover visible. A run that answered on the third candidate is
 * inexplicable unless somebody can see that the first is paused and the second has no credential,
 * and that is exactly what this endpoint reports.
 *
 * <p>Everything here is per workspace. The provider rows are a catalogue shared by every
 * workspace, so turning a provider on or off writes the caller's own setting and never the shared
 * row. Which providers the installation offers at all is changed in the database by whoever runs
 * it, and has no endpoint here.
 *
 * <p>{@code POST /{id}/test} checks a key a person has pasted before it is stored anywhere. It
 * makes one one-token call with that key, tells the person in plain words whether the provider
 * took it, and never returns or logs what the provider said back, which can quote the key. It is
 * rate-limited per workspace, because a form that tests keys on request is also a way to guess at
 * them.
 */
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers")
public class ProviderController {

    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

    /** Key checks one workspace may make in {@link #TEST_WINDOW}. */
    static final int TEST_LIMIT = 5;

    static final Duration TEST_WINDOW = Duration.ofMinutes(1);

    /** The longest the check waits for the provider, so a stalled one does not hold the request open. */
    private static final Duration TEST_DEADLINE = Duration.ofSeconds(20);

    /**
     * What a provider's answer to a bad key looks like when it is not a 401. Google and xAI answer
     * a made-up key with a 400, which an adapter files under "the request was malformed" because
     * the status says so; the words in the body say whose fault it was.
     *
     * <p>Two shapes, either order: a thing that identifies the caller beside a word for it being
     * wrong ("API key not valid", "Incorrect API key provided", "Missing Authorization header"),
     * or a plain statement that the caller is not authenticated.
     */
    private static final Pattern KEY_COMPLAINT = Pattern.compile(
            "\\b(api[ _-]?key|x-api-key|authorization|bearer|credentials?|access token)\\W.{0,40}?"
                    + "\\b(invalid|incorrect|expired|revoked|missing|not valid|rejected|denied|wrong|not provided)\\b"
                    + "|\\b(invalid|incorrect|expired|revoked|missing|wrong|no)\\W.{0,24}?"
                    + "\\b(api[ _-]?key|x-api-key|authorization|bearer|credentials?|auth credentials)"
                    + "|unauthori[sz]ed|unauthenticated|authentication (failed|required|error)"
                    + "|invalid_(api_key|authentication)|api_key_invalid",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * What a 400 says when the provider took the key and objected to how the one-token call was
     * asked. Only these count: a 400 in any other shape could as well be a refusal of the key,
     * and a key is not called valid on a guess.
     */
    private static final Pattern REQUEST_SHAPE_COMPLAINT = Pattern.compile(
            "max_tokens|max_completion_tokens|max_output_tokens|unsupported (parameter|value)|unknown parameter"
                    + "|not supported with this model|extra inputs are not permitted",
            Pattern.CASE_INSENSITIVE);

    /** Failures that arrive only once a provider has accepted the caller: the call was understood. */
    private static final Set<ProviderFailure> AFTER_AUTHENTICATION = Set.of(
            ProviderFailure.MODEL_NOT_FOUND,
            ProviderFailure.CONTEXT_LENGTH_EXCEEDED,
            ProviderFailure.CONTENT_FILTERED,
            ProviderFailure.MALFORMED_TOOL_CALL);

    private final Providers providers;
    private final Models models;
    private final ModelRouter router;
    private final JpaProviderRegistry registry;
    private final WorkspaceProviderSettings settings;
    private final RoutingPolicyResolver policies;
    private final ModelPolicies modelPolicies;
    private final Agents agents;
    private final AuditClient audit;

    /*
     * What the key check needs beyond the constructor's beans. Set by Spring after construction,
     * the way AgentController takes its executor, so the controller keeps the constructor the
     * rest of the code and its tests already build it with.
     */
    private final Map<ProviderDescriptor.Kind, ChatProvider> adapters = new EnumMap<>(ProviderDescriptor.Kind.class);
    private TransactionTemplate transaction;

    /** The recent key checks per workspace, newest last, for the rate limit. In memory: one instance counts its own. */
    private final Map<UUID, Deque<Long>> recentTests = new ConcurrentHashMap<>();

    /** Where the rate limit reads the time; replaced by a test. */
    LongSupplier clock = System::currentTimeMillis;

    public ProviderController(
            Providers providers,
            Models models,
            ModelRouter router,
            JpaProviderRegistry registry,
            WorkspaceProviderSettings settings,
            RoutingPolicyResolver policies,
            ModelPolicies modelPolicies,
            Agents agents,
            AuditClient audit) {
        this.providers = providers;
        this.models = models;
        this.router = router;
        this.registry = registry;
        this.settings = settings;
        this.policies = policies;
        this.modelPolicies = modelPolicies;
        this.agents = agents;
        this.audit = audit;
    }

    @Autowired
    void setKeyTesting(List<ChatProvider> adapters, PlatformTransactionManager transactions) {
        this.adapters.clear();
        adapters.forEach(adapter -> this.adapters.put(adapter.kind(), adapter));
        this.transaction = new TransactionTemplate(transactions);
    }

    public record ProviderView(
            String id,
            String displayName,
            String kind,
            /** Whether runs in this workspace may use it: the platform's switch and this workspace's. */
            boolean enabled,
            /** False when this installation does not offer the provider at all; no workspace can turn it on. */
            boolean platformEnabled,
            /** What the console passes to {@code PUT /api/credentials/{ref}} to configure this provider. Null for the sandbox, which needs no credential. */
            String credentialRef,
            /** What this workspace's own key last did: unknown, valid or rejected. */
            String credentialStatus,
            Instant credentialCheckedAt,
            String circuitState,
            List<String> regions,
            int modelCount) {}

    public record ModelView(
            String providerId,
            String modelId,
            String displayName,
            int contextWindow,
            int maxOutputTokens,
            boolean supportsTools,
            boolean supportsJsonMode,
            boolean supportsStreaming,
            BigDecimal inputCostPerMillion,
            BigDecimal outputCostPerMillion,
            boolean enabled,
            Instant unavailableUntil,
            String unavailableReason) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Configured providers and their current health, for this workspace")
    public List<ProviderView> list() {
        UUID orgId = orgId();
        Map<String, String> health = router.providerHealth(orgId.toString());
        return registry.workspaceProviders(orgId).stream()
                .map(provider -> toView(provider, health.getOrDefault(provider.entity().getId(), "UNKNOWN")))
                .toList();
    }

    @GetMapping("/models")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Every model available to this workspace")
    public List<ModelView> models() {
        return registry.workspaceModels(orgId()).stream().map(ProviderController::toView).toList();
    }

    @PostMapping("/{providerId}/enable")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Turn a provider on for this workspace")
    public ProviderView enable(@PathVariable String providerId) {
        return setEnabled(providerId, true);
    }

    @PostMapping("/{providerId}/disable")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Turn a provider off for this workspace, so the router stops considering it here")
    public ProviderView disable(@PathVariable String providerId) {
        return setEnabled(providerId, false);
    }

    /** What a pasted key came to. The wire names are the ones the console switches on. */
    public enum KeyCheck {
        VALID("valid"),
        REJECTED("rejected"),
        NO_CREDIT("no_credit"),
        NETWORK_ERROR("network_error");

        private final String wire;

        KeyCheck(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public record KeyTestRequest(@NotBlank @Size(max = 8_000) String value) {}

    /**
     * @param result valid, rejected, no_credit or network_error
     * @param message what happened, in plain words, safe to show; never what the provider said
     */
    public record KeyTestResult(KeyCheck result, String message) {}

    @PostMapping("/{providerId}/test")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(
            summary = "Check a key with one small call, before it is stored",
            description =
                    "Nothing is stored: the key lives only for this request. A key that works is recorded as"
                            + " valid for this workspace, never on the shared provider row.")
    public KeyTestResult test(@PathVariable String providerId, @Valid @RequestBody KeyTestRequest request) {
        UUID orgId = orgId();
        WorkspaceProvider provider = registry.workspaceProvider(orgId, providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        LlmProviderEntity entity = provider.entity();
        String name = entity.getDisplayName();
        ProviderDescriptor.Kind kind = ProviderDescriptor.Kind.valueOf(entity.getKind());

        if (kind == ProviderDescriptor.Kind.SANDBOX) {
            throw ApiException.validation("providerId", name + " runs offline and needs no key.");
        }
        if (kind == ProviderDescriptor.Kind.BEDROCK) {
            throw ApiException.validation(
                    "providerId",
                    name + " signs in with AWS credentials and regions rather than one key. Set it up on the"
                            + " Model routing page.");
        }
        if (!provider.platformEnabled()) {
            throw new ApiException(
                    ErrorCode.PROVIDER_NOT_CONFIGURED,
                    name + " is not available yet. Contact support to have it offered.");
        }
        ChatProvider adapter = adapters.get(kind);
        if (adapter == null) {
            throw new ApiException(
                    ErrorCode.PROVIDER_NOT_CONFIGURED, "This installation cannot check keys for " + name + ".");
        }
        // A key is one unbroken piece of text. Anything else is a paste that took more than the key,
        // and sent as a header it would fail in a way that reads as the provider being unreachable.
        String key = request.value().strip();
        if (key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw ApiException.validation(
                    "value", "A key has no spaces or line breaks. Copy it again, with nothing around it.");
        }

        // Only a call that would reach a provider uses up one of the workspace's checks.
        takeTestSlot(orgId);
        ProviderDescriptor descriptor = registry.provider(orgId.toString(), providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        ModelSpec model = cheapestChatModel(orgId, providerId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PROVIDER_NOT_CONFIGURED,
                        "No chat model is listed for " + name + " to check the key with."));

        ProviderException failed = probe(adapter, descriptor, model, key);
        ProviderFailure failure = failed == null ? null : failed.failure();
        KeyCheck check = classifyAnswer(failed);
        String message = messageFor(check, failure, name);
        log.info("Key check for provider {} in workspace {}: {}", providerId, orgId, check.wire());

        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        if (check == KeyCheck.VALID) {
            recordValid(orgId, providerId, actor);
        }
        // What was found, never the key and never anything the provider said.
        audit.record(
                orgId,
                actor,
                "provider.test_key",
                "provider",
                providerId,
                check == KeyCheck.VALID ? "succeeded" : "failed",
                Map.of("provider", name, "result", check.wire()));
        return new KeyTestResult(check, message);
    }

    /**
     * Makes the one call. Null means the provider took the key and answered; otherwise what went
     * wrong, with UNKNOWN standing for anything that could not be told apart (a stalled call
     * included).
     *
     * <p>A one-token completion for every provider kind, not a model listing for the
     * OpenAI-compatible ones. A listing is cheaper but cannot do this job: OpenRouter's is public
     * and answers 200 to a key that was made up (checked 4 October 2026), so a mistyped key would
     * read as valid, and no listing says whether the account can pay for an answer. The completion
     * is the call that fails the way a real run would fail.
     */
    private ProviderException probe(ChatProvider adapter, ProviderDescriptor provider, ModelSpec model, String key) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Reply with the single word ok.")))
                .maxOutputTokens(1)
                .timeout(Duration.ofSeconds(15))
                .build();
        try {
            adapter.complete(provider, model, request, key).block(TEST_DEADLINE);
            return null;
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            if (cause instanceof ProviderException failed) {
                return failed;
            }
            // A blocking read that ran out of time, or anything else the adapter did not classify.
            log.debug("Key check for provider {} ended with {}", provider.id(), cause.getClass().getSimpleName());
            return ProviderException.of(ProviderFailure.UNKNOWN, provider.id(), model.modelId(), "No answer", cause);
        }
    }

    /**
     * Whether the provider took the key. A refusal of the key itself is {@code rejected}; an account
     * with nothing left to spend is {@code no_credit}, which says the key is real. Anything that is
     * the provider's or the network's trouble, including a throttle, could not be settled either
     * way, and nothing is saved on it.
     *
     * <p>Valid is for a 2xx answer and for failures that only follow a key being accepted (a model
     * the account does not list, a prompt over the window). It is not for an answer that merely
     * has a 400 on it: providers disagree on the status of a refused key, so a 400 or a 401 or 403
     * is read for what it says. One that names the key is {@code rejected}; a 401 or 403 always
     * is; a 400 that objects to a parameter of the call is valid; a 400 that says nothing either
     * way could not be settled.
     *
     * @param answer what the call came to, null for a 2xx answer
     */
    static KeyCheck classifyAnswer(ProviderException answer) {
        if (answer == null) {
            return KeyCheck.VALID;
        }
        return classify(answer.failure(), answer.httpStatus(), answer.rawBody());
    }

    /** As {@link #classifyAnswer}, from the failure alone, when no status or body is to hand. */
    static KeyCheck classify(ProviderFailure failure) {
        return classify(failure, null, null);
    }

    static KeyCheck classify(ProviderFailure failure, Integer status, String body) {
        if (failure == null) {
            return KeyCheck.VALID;
        }
        return switch (failure) {
            case AUTHENTICATION_FAILED, AUTHORISATION_FAILED -> KeyCheck.REJECTED;
            case INSUFFICIENT_CREDIT, QUOTA_EXHAUSTED -> KeyCheck.NO_CREDIT;
            case INVALID_REQUEST, MODEL_NOT_FOUND, CONTEXT_LENGTH_EXCEEDED, CONTENT_FILTERED, MALFORMED_TOOL_CALL ->
                classifyClientError(failure, status, body);
            default -> KeyCheck.NETWORK_ERROR;
        };
    }

    /**
     * An answer an adapter filed as a complaint about the call. The filing was made from the
     * status and a few words in the body, so it is checked again here for the one thing it must
     * not hide: the key being refused.
     */
    private static KeyCheck classifyClientError(ProviderFailure failure, Integer status, String body) {
        if (status != null && (status == 401 || status == 403)) {
            return KeyCheck.REJECTED;
        }
        if (body != null && KEY_COMPLAINT.matcher(body).find()) {
            return KeyCheck.REJECTED;
        }
        if (AFTER_AUTHENTICATION.contains(failure)) {
            return KeyCheck.VALID;
        }
        // INVALID_REQUEST is the catch-all a refused key can hide in: valid only in a known shape.
        return body != null && REQUEST_SHAPE_COMPLAINT.matcher(body).find() ? KeyCheck.VALID : KeyCheck.NETWORK_ERROR;
    }

    static String messageFor(KeyCheck check, ProviderFailure failure, String provider) {
        return switch (check) {
            case VALID -> provider + " accepted the key.";
            case REJECTED ->
                "Key refused. " + provider + " did not accept this key. Check that you copied all of it, with no"
                        + " spaces, and that it is a key for " + provider + ".";
            case NO_CREDIT ->
                provider + " accepted the key, but the account has no credit left. Add credit with " + provider
                        + ", then check the key again.";
            case NETWORK_ERROR ->
                failure == ProviderFailure.RATE_LIMITED
                        ? provider + " is limiting requests right now, so the key could not be checked. Wait a"
                                + " minute, then try again."
                        : failure == ProviderFailure.INVALID_REQUEST
                                ? provider + " answered, but not in a way that shows whether the key works, so"
                                        + " nothing was saved. Check that you copied the whole key, then try again."
                                : "We could not reach " + provider + " to check the key, so nothing was saved. Try"
                                        + " again in a moment.";
        };
    }

    /**
     * The cheapest chat model this workspace can see for the provider, to spend the one token on.
     * Availability notes are ignored: a model set aside for a lapsed account is exactly what a
     * new key is being checked against.
     */
    private Optional<ModelSpec> cheapestChatModel(UUID orgId, String providerId) {
        return registry.workspaceModels(orgId).stream()
                .map(WorkspaceModel::entity)
                .filter(model -> model.getProviderId().equals(providerId))
                .filter(model -> model.isEnabled() && model.getMaxOutputTokens() > 1)
                .min(Comparator.comparing(ProviderController::pricePerMillion)
                        .thenComparing(LlmModelEntity::getModelId))
                .flatMap(model -> registry.model(orgId.toString(), providerId, model.getModelId()));
    }

    private static BigDecimal pricePerMillion(LlmModelEntity model) {
        return model.getInputCostPerMillion().add(model.getOutputCostPerMillion());
    }

    /** Counts this check against the workspace's allowance, or refuses it with how long to wait. */
    private void takeTestSlot(UUID orgId) {
        long now = clock.getAsLong();
        long window = TEST_WINDOW.toMillis();
        Deque<Long> calls = recentTests.computeIfAbsent(orgId, id -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && now - calls.peekFirst() >= window) {
                calls.pollFirst();
            }
            if (calls.size() >= TEST_LIMIT) {
                long waitMillis = window - (now - calls.peekFirst());
                throw new ApiException(
                                ErrorCode.RATE_LIMITED,
                                "A key can be checked " + TEST_LIMIT
                                        + " times a minute in a workspace. Wait a moment, then try again.")
                        .retryAfter(Duration.ofSeconds(Math.max(1, (waitMillis + 999) / 1000)));
            }
            calls.addLast(now);
        }
    }

    /**
     * Records that this workspace's key worked, in the workspace's own row and nowhere else. Under
     * the workspace's settings lock, so it never interleaves with a provider being turned on or off.
     */
    private void recordValid(UUID orgId, String providerId, Actor actor) {
        Runnable write = () -> transaction.executeWithoutResult(status -> {
            settings.holdWorkspaceLock(orgId);
            Instant now = Instant.now();
            WorkspaceProviderSetting row = settings.findById(new WorkspaceProviderSetting.Key(orgId, providerId))
                    .orElseGet(() -> new WorkspaceProviderSetting(orgId, providerId));
            row.setCredentialStatus(WorkspaceProviderSetting.VALID);
            row.setCredentialCheckedAt(now);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actor.id());
            settings.saveAndFlush(row);
        });
        try {
            write.run();
        } catch (DataIntegrityViolationException raced) {
            // A refused call elsewhere created the row between the look and the insert; it exists now.
            write.run();
        }
    }

    /**
     * Turns a provider on or off for the calling workspace only.
     *
     * <p>A platform-wide row is never written: the caller's own setting is, so no other workspace
     * notices. A provider the workspace added for itself is its own to change directly. A provider
     * scoped to another workspace is a 404, exactly as if it did not exist.
     *
     * <p>The workspace's settings are locked for the rest of the transaction before anything is
     * read, so two toggles in one workspace run one after the other and the policy check below
     * always sees the other's result.
     */
    private ProviderView setEnabled(String providerId, boolean enabled) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        WorkspaceProvider current = registry.workspaceProvider(orgId, providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        LlmProviderEntity entity = current.entity();

        if (enabled && entity.isPlatformWide() && !entity.isEnabled()) {
            throw new ApiException(
                    ErrorCode.PROVIDER_NOT_CONFIGURED,
                    entity.getDisplayName() + " is not available yet. Contact support to have it offered.");
        }
        if (!enabled && current.enabled()) {
            requireAnotherProviderInPolicy(orgId, entity);
        }

        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        if (entity.isPlatformWide()) {
            settings.upsertEnabled(orgId, providerId, enabled, Instant.now(), actor.id());
        } else {
            entity.setEnabled(enabled);
            providers.save(entity);
        }

        String action = enabled ? "provider.enable" : "provider.disable";
        Map<String, Object> detail = Map.of(
                "provider", entity.getDisplayName(),
                "scope", entity.isPlatformWide() ? "workspace_setting" : "workspace_provider",
                "wasEnabled", current.enabled());
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, action, "provider", providerId, "succeeded", detail));

        boolean nowEnabled = enabled && entity.isEnabled();
        WorkspaceProvider updated = new WorkspaceProvider(
                entity, nowEnabled, current.credentialStatus(), current.credentialCheckedAt());
        String circuit = router.providerHealth(orgId.toString()).getOrDefault(providerId, "UNKNOWN");
        return toView(updated, circuit);
    }

    /**
     * Refuses to switch off the last provider a routing policy in this workspace can use.
     *
     * <p>Without this, one click leaves runs without a model and the failure only shows up when
     * the next run stops. Every policy that decides a run here is checked: the workspace default,
     * which agents without their own routing inherit, and each agent's own, which the router tries
     * instead of the default. A policy that falls back to the offline sandbox when everything fails
     * still has somewhere to go, so it is not guarded. A retired agent never runs, so its policy
     * is left out.
     *
     * <p>The agents' own policies are read in one query for the whole workspace, not one per agent;
     * an agent with none inherits the workspace default, which was checked first.
     */
    private void requireAnotherProviderInPolicy(UUID orgId, LlmProviderEntity provider) {
        Map<String, Boolean> onHere = new HashMap<>();
        RoutingPolicy workspace = policies.resolve(orgId, null);
        if (strands(
                workspace.exhausted() == RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX,
                workspace.candidates().stream().map(RoutingPolicy.Candidate::providerId).toList(),
                orgId,
                provider,
                onHere)) {
            throw stranded(
                    "Turning off " + provider.getDisplayName() + " would leave this workspace's routing policy"
                            + " with no provider that is on, so every run would stop. Add another model to the"
                            + " routing policy first, or turn on a provider it already lists.",
                    "workspace");
        }
        // The router skips an agent policy with no candidates, so only a non-empty one is the agent's own.
        Map<UUID, ModelPolicyEntity> own = new HashMap<>();
        for (ModelPolicyEntity policy : modelPolicies.findByOrgId(orgId)) {
            if (policy.getAgentId() != null && !policy.getCandidates().isEmpty()) {
                own.put(policy.getAgentId(), policy);
            }
        }
        if (own.isEmpty()) {
            return;
        }
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            ModelPolicyEntity policy = own.get(agent.getId());
            if (policy == null || "retired".equals(agent.getStatus())) {
                continue;
            }
            if (strands(
                    "DEGRADE_TO_SANDBOX".equals(policy.getExhaustedBehaviour()),
                    policy.getCandidates().stream().map(ModelPolicyCandidate::getProviderId).toList(),
                    orgId,
                    provider,
                    onHere)) {
                throw stranded(
                        "Turning off " + provider.getDisplayName() + " would leave " + agent.getName()
                                + "'s own routing with no provider that is on, so its runs would stop. Add another"
                                + " model to " + agent.getName() + "'s routing first, or turn on a provider it"
                                + " already lists.",
                        "agent");
            }
        }
    }

    /**
     * The 409 for a toggle that would strand a policy. {@code routing} says whose policy, so the
     * console offers a link to the workspace policy only when that is where the fix is made.
     */
    private static ApiException stranded(String message, String routing) {
        ErrorCode code = ErrorCode.RESOURCE_IN_USE;
        return new ApiException(code, message, Map.of("routing", routing), null, null, code.retryable());
    }

    /**
     * True when the policy lists the provider and no other provider it lists is on here.
     *
     * @param degradesToSandbox whether the policy answers on the offline sandbox when every model
     *     fails, which means it always has somewhere to go
     * @param providerIds the providers the policy lists, in any order
     */
    private boolean strands(
            boolean degradesToSandbox,
            Collection<String> providerIds,
            UUID orgId,
            LlmProviderEntity provider,
            Map<String, Boolean> onHere) {
        if (degradesToSandbox) {
            return false;
        }
        Set<String> inPolicy = new LinkedHashSet<>(providerIds);
        if (!inPolicy.contains(provider.getId())) {
            return false;
        }
        return inPolicy.stream()
                .filter(id -> !id.equals(provider.getId()))
                .noneMatch(id -> onHere.computeIfAbsent(id, key -> registry.workspaceProvider(orgId, key)
                        .map(WorkspaceProvider::enabled)
                        .orElse(false)));
    }

    private ProviderView toView(WorkspaceProvider provider, String circuitState) {
        LlmProviderEntity entity = provider.entity();
        return new ProviderView(
                entity.getId(),
                entity.getDisplayName(),
                entity.getKind(),
                provider.enabled(),
                provider.platformEnabled(),
                entity.getCredentialRef(),
                // The credential itself is never returned, only whether this workspace's works.
                provider.credentialStatus(),
                provider.credentialCheckedAt(),
                circuitState,
                entity.getRegions(),
                models.findByProviderIdAndEnabledTrue(entity.getId()).size());
    }

    private static ModelView toView(WorkspaceModel workspaceModel) {
        LlmModelEntity model = workspaceModel.entity();
        return new ModelView(
                model.getProviderId(),
                model.getModelId(),
                model.getDisplayName(),
                model.getContextWindow(),
                model.getMaxOutputTokens(),
                model.isSupportsTools(),
                model.isSupportsJsonMode(),
                model.isSupportsStreaming(),
                model.getInputCostPerMillion(),
                model.getOutputCostPerMillion(),
                model.isEnabled(),
                workspaceModel.unavailableUntil(),
                workspaceModel.unavailableReason());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
