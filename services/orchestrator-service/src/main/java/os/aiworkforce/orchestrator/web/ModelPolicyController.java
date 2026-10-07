package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Which models a workspace or an agent should try, and in what order.
 *
 * <p>{@link os.aiworkforce.orchestrator.service.RoutingPolicyResolver} already resolves narrowest
 * first - an agent's own policy, then the workspace default, then a built-in sandbox-only fallback
 * - and enabling a provider and storing its credential only makes a candidate <em>usable</em>, not
 * <em>chosen</em>. Without a row here, every run still resolves to the built-in fallback,
 * regardless of what is enabled. This is the endpoint that writes that row.
 *
 * <p>A save takes the workspace's provider-settings lock before it reads anything, the lock that
 * turning a provider off also takes. A policy is refused when every provider it lists is switched
 * off for this workspace and it is set to stop when its models fail, because every run on it would
 * stop. Without the shared lock, that check and a provider being switched off could each pass
 * while the other was in flight, and together leave a policy that no longer works.
 *
 * <p>Every change to a policy - saving the workspace's, saving or clearing an agent's - is audited
 * after it commits, with the chain before and after, so "who changed the models, and to what" has
 * an answer.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Model policy")
public class ModelPolicyController {

    private static final int MAX_CANDIDATES = 10;

    private final ModelPolicies policies;
    private final Models models;
    private final Agents agents;
    private final WorkspaceProviderSettings settings;
    private final JpaProviderRegistry registry;
    private final AuditClient audit;

    public ModelPolicyController(
            ModelPolicies policies,
            Models models,
            Agents agents,
            WorkspaceProviderSettings settings,
            JpaProviderRegistry registry,
            AuditClient audit) {
        this.policies = policies;
        this.models = models;
        this.agents = agents;
        this.settings = settings;
        this.registry = registry;
        this.audit = audit;
    }

    public record CandidateInput(
            @NotBlank String providerId,
            @NotBlank String modelId,
            @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
            @Positive Integer maxOutputTokens) {}

    public record ModelPolicyRequest(
            @Valid @Size(max = MAX_CANDIDATES) List<CandidateInput> candidates,
            @Pattern(regexp = "FAIL_CLOSED|DEGRADE_TO_SANDBOX") String exhaustedBehaviour,
            @Min(1) @Max(10) Integer maxAttemptsPerCandidate,
            @Min(30) @Max(1800) Integer overallDeadlineSeconds,
            Boolean compactOnOverflow) {}

    public record CandidateView(
            int position, String providerId, String modelId, BigDecimal temperature, Integer maxOutputTokens) {}

    /** {@code configured} is false when nothing has been set and the resolver will fall through. */
    public record ModelPolicyView(
            boolean configured,
            String exhaustedBehaviour,
            int maxAttemptsPerCandidate,
            int overallDeadlineSeconds,
            boolean compactOnOverflow,
            List<CandidateView> candidates) {}

    @GetMapping("/model-policy")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "The workspace's default routing policy")
    public ModelPolicyView workspaceDefault() {
        return policies.findWorkspaceDefault(orgId())
                .map(ModelPolicyController::toView)
                .orElseGet(ModelPolicyController::unset);
    }

    @PutMapping("/model-policy")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Set the workspace's default routing policy")
    public ModelPolicyView setWorkspaceDefault(@Valid @RequestBody ModelPolicyRequest request) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        ModelPolicyEntity policy = policies.findWorkspaceDefault(orgId).orElseGet(() -> {
            ModelPolicyEntity fresh = new ModelPolicyEntity();
            fresh.setOrgId(orgId);
            return fresh;
        });
        requireAProviderThatIsOn(orgId, policy, request, "workspace");
        Map<String, Object> before = snapshot(policy);
        apply(policy, request);
        policies.save(policy);
        record(orgId, "model_policy.update", "model_policy", orgId, "workspace", null, before, policy);
        return toView(policy);
    }

    @GetMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "An agent's own routing policy, if it has set one")
    public ModelPolicyView forAgent(@PathVariable UUID agentId) {
        UUID orgId = orgId();
        requireAgent(orgId, agentId);
        return policies.findByOrgIdAndAgentId(orgId, agentId)
                .map(ModelPolicyController::toView)
                .orElseGet(ModelPolicyController::unset);
    }

    @PutMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_SET_MODEL_POLICY)
    @Transactional
    @Operation(summary = "Set an agent's own routing policy, overriding the workspace default")
    public ModelPolicyView setForAgent(@PathVariable UUID agentId, @Valid @RequestBody ModelPolicyRequest request) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        Agent agent = requireAgent(orgId, agentId);
        ModelPolicyEntity policy = policies.findByOrgIdAndAgentId(orgId, agentId)
                .orElseGet(() -> {
                    ModelPolicyEntity fresh = new ModelPolicyEntity();
                    fresh.setOrgId(orgId);
                    fresh.setAgentId(agentId);
                    return fresh;
                });
        requireAProviderThatIsOn(orgId, policy, request, "agent");
        Map<String, Object> before = snapshot(policy);
        apply(policy, request);
        policies.save(policy);
        record(orgId, "agent.model_policy.set", "agent", agentId, "agent", agent.getName(), before, policy);
        return toView(policy);
    }

    /**
     * Gives an agent back to the workspace's routing: its own chain is deleted, and the router
     * resolves it to the workspace default (or the built-in offline model) from the next call.
     *
     * <p>Idempotent. An agent with no policy of its own is left as it is, answers the same
     * "nothing set" view as a read, and is not audited, because nothing changed.
     */
    @DeleteMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_SET_MODEL_POLICY)
    @Transactional
    @Operation(summary = "Clear an agent's own routing policy, so it follows the workspace default")
    public ModelPolicyView clearForAgent(@PathVariable UUID agentId) {
        UUID orgId = orgId();
        Agent agent = requireAgent(orgId, agentId);
        policies.findByOrgIdAndAgentId(orgId, agentId).ifPresent(policy -> {
            Map<String, Object> before = snapshot(policy);
            policies.delete(policy);
            record(orgId, "agent.model_policy.clear", "agent", agentId, "agent", agent.getName(), before, null);
        });
        return unset();
    }

    /**
     * Refuses a policy that stops when its models fail and lists only providers that are off here.
     *
     * <p>Saved, it would look configured and then fail every run it decides, which is only
     * noticed when the next run stops. A policy that falls back to the offline model when
     * everything fails is not refused: it always has somewhere to go. Neither is one with no
     * candidates, which the resolver treats as unset. A provider this workspace cannot see counts
     * as off, as it does for the router.
     *
     * <p>Checked against the request as it will be applied: a field left out keeps what the policy
     * has now.
     *
     * @param routing {@code workspace} or {@code agent}, whose policy this is, so the console can
     *     point at the right place
     */
    private void requireAProviderThatIsOn(
            UUID orgId, ModelPolicyEntity current, ModelPolicyRequest request, String routing) {
        List<CandidateInput> candidates = request.candidates() == null ? List.of() : request.candidates();
        String behaviour =
                request.exhaustedBehaviour() != null ? request.exhaustedBehaviour() : current.getExhaustedBehaviour();
        if (candidates.isEmpty() || !"FAIL_CLOSED".equals(behaviour)) {
            return;
        }
        Set<String> on = registry.workspaceProviders(orgId).stream()
                .filter(WorkspaceProvider::enabled)
                .map(provider -> provider.entity().getId())
                .collect(Collectors.toSet());
        if (candidates.stream().map(CandidateInput::providerId).anyMatch(on::contains)) {
            return;
        }
        String whose = "agent".equals(routing) ? "this agent's runs" : "every run that uses the workspace routing";
        ErrorCode code = ErrorCode.RESOURCE_IN_USE;
        throw new ApiException(
                code,
                "Every provider in this routing policy is switched off for this workspace, so " + whose
                        + " would stop. Turn on a provider it lists, or add a model from a provider that is on.",
                Map.of("routing", routing),
                null,
                null,
                code.retryable());
    }

    private Agent requireAgent(UUID orgId, UUID agentId) {
        return agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
    }

    /**
     * Audits a change after it commits, so a save that rolls back leaves no entry saying it
     * happened. {@code after} is null when the policy was removed.
     */
    private void record(
            UUID orgId,
            String action,
            String resourceType,
            UUID resourceId,
            String scope,
            String agentName,
            Map<String, Object> before,
            ModelPolicyEntity after) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scope", scope);
        if (agentName != null) {
            detail.put("agent", agentName);
        }
        detail.put("old", before);
        // A cleared policy has no chain at all: the agent follows the workspace's.
        detail.put("new", after == null ? Map.of("candidates", List.of()) : snapshot(after));
        LifecycleAnnouncer.afterCommit(() ->
                audit.record(orgId, actor, action, resourceType, resourceId.toString(), "succeeded", detail));
    }

    /** A policy as the audit trail records it: the chain as provider/model, in order, and what happens when it runs out. */
    private static Map<String, Object> snapshot(ModelPolicyEntity policy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "candidates",
                policy.getCandidates().stream()
                        .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                        .map(candidate -> candidate.getProviderId() + "/" + candidate.getModelId())
                        .toList());
        values.put("exhaustedBehaviour", policy.getExhaustedBehaviour());
        return values;
    }

    /**
     * Rejects a candidate that names a model this platform does not know about, rather than
     * saving a policy that would fail silently the first time a run tries to resolve it.
     */
    private void apply(ModelPolicyEntity policy, ModelPolicyRequest request) {
        List<CandidateInput> inputs = request.candidates() == null ? List.of() : request.candidates();
        List<ModelPolicyCandidate> candidates = new ArrayList<>();
        for (int position = 0; position < inputs.size(); position++) {
            CandidateInput input = inputs.get(position);
            if (models.findById(new LlmModelEntity.Key(input.providerId(), input.modelId()))
                    .isEmpty()) {
                throw ApiException.validation(
                        "candidates[" + position + "]",
                        "Unknown model " + input.providerId() + "/" + input.modelId() + ".");
            }
            ModelPolicyCandidate candidate =
                    ModelPolicyCandidate.of(policy.getId(), position, input.providerId(), input.modelId());
            candidate.setTemperature(input.temperature());
            candidate.setMaxOutputTokens(input.maxOutputTokens());
            candidates.add(candidate);
        }
        policy.setCandidates(candidates);

        if (request.exhaustedBehaviour() != null) {
            policy.setExhaustedBehaviour(request.exhaustedBehaviour());
        }
        if (request.maxAttemptsPerCandidate() != null) {
            policy.setMaxAttemptsPerCandidate(request.maxAttemptsPerCandidate());
        }
        if (request.overallDeadlineSeconds() != null) {
            policy.setOverallDeadlineSeconds(request.overallDeadlineSeconds());
        }
        if (request.compactOnOverflow() != null) {
            policy.setCompactOnOverflow(request.compactOnOverflow());
        }
    }

    private static ModelPolicyView toView(ModelPolicyEntity entity) {
        List<CandidateView> candidates = entity.getCandidates().stream()
                .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                .map(c -> new CandidateView(
                        c.getPosition(), c.getProviderId(), c.getModelId(), c.getTemperature(), c.getMaxOutputTokens()))
                .toList();
        return new ModelPolicyView(
                !candidates.isEmpty(),
                entity.getExhaustedBehaviour(),
                entity.getMaxAttemptsPerCandidate(),
                entity.getOverallDeadlineSeconds(),
                entity.isCompactOnOverflow(),
                candidates);
    }

    private static ModelPolicyView unset() {
        return new ModelPolicyView(false, "FAIL_CLOSED", 2, 300, true, List.of());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
