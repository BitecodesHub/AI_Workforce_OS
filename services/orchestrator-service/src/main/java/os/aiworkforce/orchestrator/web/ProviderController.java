// @find: providers api, model providers, add API key, test key, check provider, enable provider, disable provider, list models, OpenRouter, Groq, Bedrock, OpenAI, /api/providers, Providers page, Test key button
// @what: REST endpoints to list providers and models, enable or disable them, and test or check an API key.
// @flow: Called by the Providers settings page; uses JpaProviderRegistry and organisation service credentials
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

import os.aiworkforce.llm.bedrock.BedrockCredentials;
import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
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

    /** The recent "Test now" checks per workspace, newest last. */
    private final Map<UUID, Deque<Long>> recentChecks = new ConcurrentHashMap<>();

    /** "Test now" checks one workspace may make in {@link #TEST_WINDOW}. */
    static final int CHECK_LIMIT = 20;

    /** The workspace's stored keys, for "Test now"; absent only where a test builds this by hand. */
    private CredentialResolver credentials;

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

    @Autowired(required = false)
    void setCredentials(CredentialResolver credentials) {
        this.credentials = credentials;
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
            int modelCount,
            /** A plain sentence when the change just made leaves some routing with no provider that is on; else null. */
            String warning) {

        /** The view with no warning, as every read returns it. */
        public ProviderView(
                String id,
                String displayName,
                String kind,
                boolean enabled,
                boolean platformEnabled,
                String credentialRef,
                String credentialStatus,
                Instant credentialCheckedAt,
                String circuitState,
                List<String> regions,
                int modelCount) {
            this(
                    id,
                    displayName,
                    kind,
                    enabled,
                    platformEnabled,
                    credentialRef,
                    credentialStatus,
                    credentialCheckedAt,
                    circuitState,
                    regions,
                    modelCount,
                    null);
        }
    }

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

    // @find: list providers, GET /api/providers
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

    // @find: list models, GET /api/providers/models
    @GetMapping("/models")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Every model available to this workspace")
    public List<ModelView> models() {
        return registry.workspaceModels(orgId()).stream().map(ProviderController::toView).toList();
    }

    // @find: enable provider, POST /api/providers/{providerId}/enable
    @PostMapping("/{providerId}/enable")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Turn a provider on for this workspace")
    public ProviderView enable(@PathVariable String providerId) {
        return setEnabled(providerId, true);
    }

    // @find: disable provider, POST /api/providers/{providerId}/disable
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
    // @find: test API key, POST /api/providers/{providerId}/test
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
        boolean bedrock = kind == ProviderDescriptor.Kind.BEDROCK;
        String key;
        if (bedrock) {
            // Bedrock's credential is several fields (an access key pair or an API key, and a
            // region) sent as one JSON value. Each field is checked here, before any call.
            try {
                key = BedrockCredentials.parse(request.value()).toJson();
            } catch (BedrockCredentials.Invalid invalid) {
                throw ApiException.validation(invalid.field(), invalid.getMessage());
            }
        } else {
            // A key is one unbroken piece of text. Anything else is a paste that took more than the
            // key, and sent as a header it would fail in a way that reads as the provider being
            // unreachable.
            key = request.value().strip();
            if (key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw ApiException.validation(
                        "value", "A key has no spaces or line breaks. Copy it again, with nothing around it.");
            }
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
        // Bedrock's adapter already reads AWS's error type, and its 403 also means "model access
        // not turned on", which says the credentials worked; so the failure alone decides.
        KeyCheck check = bedrock ? classify(failure) : classifyAnswer(failed);
        String message = bedrock ? bedrockMessage(check, failed, name, model) : messageFor(check, failure, name);
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
     * What a Bedrock check came to. The adapter's own sentence says which AWS answer it was (model
     * access not turned on, an inference profile needed, IAM not allowing the call), which matters
     * more here than for a one-key provider: AWS can accept the credentials and still refuse the
     * model, and the person needs to know which of the two to fix.
     */
    static String bedrockMessage(KeyCheck check, ProviderException failed, String provider, ModelSpec model) {
        String detail = failed == null || failed.getMessage() == null ? "" : " " + failed.getMessage();
        String modelName = model.displayName() == null || model.displayName().isBlank()
                ? model.modelId()
                : model.displayName();
        return switch (check) {
            case VALID -> failed == null
                    ? provider + " accepted the credentials, and " + modelName + " answered."
                    : provider + " accepted the credentials, but " + modelName + " could not answer." + detail
                            + " Turn on model access in the Amazon Bedrock console for the models you want, in"
                            + " the region you chose.";
            case REJECTED -> failed != null && failed.failure() == ProviderFailure.AUTHORISATION_FAILED
                    ? detail.strip() + " Nothing was saved."
                    : "Credentials refused." + detail + " Check the access key ID, secret access key and session"
                            + " token, or the API key, then try again. Nothing was saved.";
            case NO_CREDIT -> messageFor(check, failed == null ? null : failed.failure(), provider);
            case NETWORK_ERROR -> failed != null && failed.failure() == ProviderFailure.RATE_LIMITED
                    ? provider + " is limiting requests right now, so the credentials could not be checked. Wait a"
                            + " minute, then try again."
                    : "We could not reach " + provider + " in that region to check the credentials, so nothing"
                            + " was saved. Check the region, then try again.";
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
        takeSlot(
                recentTests,
                orgId,
                TEST_LIMIT,
                "A key can be checked " + TEST_LIMIT + " times a minute in a workspace. Wait a moment, then try again.");
    }

    private void takeSlot(Map<UUID, Deque<Long>> recent, UUID orgId, int limit, String refusal) {
        long now = clock.getAsLong();
        long window = TEST_WINDOW.toMillis();
        Deque<Long> calls = recent.computeIfAbsent(orgId, id -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && now - calls.peekFirst() >= window) {
                calls.pollFirst();
            }
            if (calls.size() >= limit) {
                long waitMillis = window - (now - calls.peekFirst());
                throw new ApiException(ErrorCode.RATE_LIMITED, refusal)
                        .retryAfter(Duration.ofSeconds(Math.max(1, (waitMillis + 999) / 1000)));
            }
            calls.addLast(now);
        }
    }

    /** What "Test now" is asked: one model of the provider, or its cheapest when none is named. */
    public record CheckRequest(@Size(max = 300) String modelId) {}

    /**
     * What "Test now" found.
     *
     * @param result ok, rejected, no_credit, not_found, busy, timeout, unreachable, no_key or error
     * @param message what happened, in plain words, safe to show; never what the provider said
     * @param latencyMs how long the call took, when one was made
     */
    public record CheckResult(
            String providerId,
            String modelId,
            String modelName,
            String result,
            String message,
            Long latencyMs,
            Instant checkedAt) {}

    @PostMapping("/{providerId}/check")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(
            summary = "Test a provider's model now, with the workspace's stored key",
            description =
                    "Makes one real, minimal call and says what happened. Also clears anything a past"
                            + " failure left behind for the model, so the next run starts fresh.")
    // @find: check provider, POST /api/providers/{providerId}/check
    public CheckResult check(@PathVariable String providerId, @RequestBody(required = false) CheckRequest request) {
        UUID orgId = orgId();
        WorkspaceProvider provider = registry.workspaceProvider(orgId, providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        String name = provider.entity().getDisplayName();
        ProviderDescriptor.Kind kind = ProviderDescriptor.Kind.valueOf(provider.entity().getKind());
        ChatProvider adapter = adapters.get(kind);
        if (adapter == null) {
            throw new ApiException(
                    ErrorCode.PROVIDER_NOT_CONFIGURED, "This installation cannot test " + name + ".");
        }
        String wanted = request == null || request.modelId() == null || request.modelId().isBlank()
                ? null
                : request.modelId().strip();
        ModelSpec model = (wanted == null
                        ? cheapestChatModel(orgId, providerId)
                        : registry.model(orgId.toString(), providerId, wanted))
                .orElseThrow(() -> wanted == null
                        ? new ApiException(
                                ErrorCode.PROVIDER_NOT_CONFIGURED, "No chat model is listed for " + name + " to test.")
                        : ApiException.notFound("model", providerId + "/" + wanted));
        String modelName = model.displayName() == null || model.displayName().isBlank()
                ? model.modelId()
                : model.displayName();

        takeSlot(recentChecks, orgId, CHECK_LIMIT, "Models can be tested " + CHECK_LIMIT
                + " times a minute in a workspace. Wait a moment, then try again.");
        // Whatever a past failure left behind goes, whatever this call finds.
        router.forgetCoolOff(orgId.toString(), providerId, model.modelId());
        registry.forgetNotes(orgId, providerId, model.modelId());

        ProviderDescriptor descriptor = registry.provider(orgId.toString(), providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        String key = null;
        if (descriptor.requiresCredential()) {
            CredentialResolver.Lookup lookup =
                    credentials == null ? null : credentials.lookup(orgId.toString(), descriptor.credentialRef());
            if (lookup instanceof CredentialResolver.Found found && !found.value().isBlank()) {
                key = found.value();
            } else if (lookup instanceof CredentialResolver.NotFound && adapter.hasAmbientCredential(descriptor)) {
                // Nothing stored, and this server reaches the provider with its own identity (an
                // instance role): the test makes the same call a run would, with no key.
                key = null;
            } else {
                String message = lookup instanceof CredentialResolver.Unavailable
                        ? "Could not reach the key store to test " + name + ". Try again in a minute."
                        : "No key is stored for " + name + ". Add one, then test again.";
                return new CheckResult(
                        providerId,
                        model.modelId(),
                        modelName,
                        lookup instanceof CredentialResolver.Unavailable ? "unreachable" : "no_key",
                        message,
                        null,
                        Instant.now());
            }
        }

        long started = System.nanoTime();
        ProviderException failed = probe(adapter, descriptor, model, key);
        long latency = (System.nanoTime() - started) / 1_000_000;
        String result = checkResult(failed);
        String label = name + " \u00b7 " + modelName;
        String message = switch (result) {
            case "ok" -> label + " answered in " + latency + " ms.";
            case "rejected" -> name + " refused the stored key. Paste a new key for " + name + ".";
            case "no_credit" -> label + " needs credit the account does not have. Add credit with " + name + ".";
            case "not_found" -> kind == ProviderDescriptor.Kind.BEDROCK && failed != null
                    ? label + " could not answer. " + failed.getMessage()
                            + " Turn on model access in the Amazon Bedrock console, or choose another model."
                    : name + " does not offer " + modelName + " to this account.";
            case "busy" -> label + " is busy right now. It will still be tried on every run.";
            case "timeout" -> label + " did not answer in time. It will still be tried on every run.";
            case "unreachable" -> "Could not reach " + name + ". It will still be tried on every run.";
            default -> label + " answered with an error. It will still be tried on every run.";
        };
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        if ("ok".equals(result)) {
            recordValid(orgId, providerId, actor);
        } else if ("rejected".equals(result)) {
            registry.markCredentialInvalid(orgId.toString(), providerId, "Refused during a test");
        }
        log.info("Model test for {}/{} in workspace {}: {}", providerId, model.modelId(), orgId, result);
        audit.record(
                orgId,
                actor,
                "provider.check",
                "provider",
                providerId,
                "ok".equals(result) ? "succeeded" : "failed",
                Map.of("provider", name, "model", model.modelId(), "result", result));
        return new CheckResult(providerId, model.modelId(), modelName, result, message, latency, Instant.now());
    }

    /** What a test call came to, in the words the console switches on. */
    static String checkResult(ProviderException failed) {
        if (failed == null) {
            return "ok";
        }
        return switch (failed.failure()) {
            case AUTHENTICATION_FAILED, AUTHORISATION_FAILED -> "rejected";
            case INSUFFICIENT_CREDIT, QUOTA_EXHAUSTED -> "no_credit";
            case MODEL_NOT_FOUND -> "not_found";
            case RATE_LIMITED, OVERLOADED -> "busy";
            case TIMEOUT -> "timeout";
            case NETWORK_ERROR -> "unreachable";
            case UNKNOWN -> "No answer".equals(failed.getMessage()) ? "timeout" : "error";
            default -> classifyAnswer(failed) == KeyCheck.VALID
                    ? "ok"
                    : classifyAnswer(failed) == KeyCheck.REJECTED ? "rejected" : "error";
        };
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
        // Turning a provider off is never refused. Chains that list it keep it (turning it back on
        // restores them); the router skips it while it is off, and the person is told which
        // routing is left with nothing that is on.
        String warning = !enabled && current.enabled() ? strandedWarning(orgId, entity) : null;

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
        ProviderView view = toView(updated, circuit);
        return warning == null
                ? view
                : new ProviderView(
                        view.id(),
                        view.displayName(),
                        view.kind(),
                        view.enabled(),
                        view.platformEnabled(),
                        view.credentialRef(),
                        view.credentialStatus(),
                        view.credentialCheckedAt(),
                        view.circuitState(),
                        view.regions(),
                        view.modelCount(),
                        warning);
    }

    /**
     * Which routing is left with no provider that is on once this one is off, as a sentence, or
     * null when every chain still has one. The workspace default, which agents without their own
     * routing inherit, and each active agent's own chain are checked. A chain that falls back to
     * the offline model when everything fails still has somewhere to go, so it is not named.
     */
    private String strandedWarning(UUID orgId, LlmProviderEntity provider) {
        Map<String, Boolean> onHere = new HashMap<>();
        boolean workspaceStranded = false;
        RoutingPolicy workspace = policies.resolve(orgId, null);
        if (strands(
                workspace.exhausted() == RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX,
                workspace.candidates().stream().map(RoutingPolicy.Candidate::providerId).toList(),
                orgId,
                provider,
                onHere)) {
            workspaceStranded = true;
        }
        Map<UUID, ModelPolicyEntity> own = new HashMap<>();
        for (ModelPolicyEntity policy : modelPolicies.findByOrgId(orgId)) {
            if (policy.getAgentId() != null && !policy.getCandidates().isEmpty()) {
                own.put(policy.getAgentId(), policy);
            }
        }
        List<String> agentNames = new java.util.ArrayList<>();
        if (!own.isEmpty()) {
            for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
                ModelPolicyEntity policy = own.get(agent.getId());
                if (policy == null || "retired".equals(agent.getStatus())) {
                    continue;
                }
                if (strands(
                        "DEGRADE_TO_SANDBOX".equals(policy.getExhaustedBehaviour()),
                        policy.getCandidates().stream()
                                .map(ModelPolicyCandidate::getProviderId)
                                .toList(),
                        orgId,
                        provider,
                        onHere)) {
                    agentNames.add(agent.getName());
                }
            }
        }
        if (!workspaceStranded && agentNames.isEmpty()) {
            return null;
        }
        List<String> whose = new java.util.ArrayList<>();
        if (workspaceStranded) {
            whose.add("the workspace routing");
        }
        if (!agentNames.isEmpty()) {
            whose.add((agentNames.size() == 1 ? "this agent's own routing: " : "these agents' own routing: ")
                    + String.join(", ", agentNames));
        }
        return provider.getDisplayName() + " is off. Its models stay in the routing and are skipped while it is"
                + " off, so " + String.join(", and ", whose)
                + " now has no provider that is on, and those runs will fail until you turn a provider on or"
                + " add another model.";
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
