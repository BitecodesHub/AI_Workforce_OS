package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
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
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Model policy")
public class ModelPolicyController {

    private static final int MAX_CANDIDATES = 10;

    private final ModelPolicies policies;
    private final Models models;
    private final Agents agents;

    public ModelPolicyController(ModelPolicies policies, Models models, Agents agents) {
        this.policies = policies;
        this.models = models;
        this.agents = agents;
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
        ModelPolicyEntity policy = policies.findWorkspaceDefault(orgId).orElseGet(() -> {
            ModelPolicyEntity fresh = new ModelPolicyEntity();
            fresh.setOrgId(orgId);
            return fresh;
        });
        apply(policy, request);
        policies.save(policy);
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
        requireAgent(orgId, agentId);
        ModelPolicyEntity policy = policies.findByOrgIdAndAgentId(orgId, agentId)
                .orElseGet(() -> {
                    ModelPolicyEntity fresh = new ModelPolicyEntity();
                    fresh.setOrgId(orgId);
                    fresh.setAgentId(agentId);
                    return fresh;
                });
        apply(policy, request);
        policies.save(policy);
        return toView(policy);
    }

    private void requireAgent(UUID orgId, UUID agentId) {
        agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
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
