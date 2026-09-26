package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.ModelPolicies;

/**
 * Works out which chain of models an agent should use.
 *
 * <p>Resolution runs narrowest first: the agent's own policy, then the workspace default, then a
 * built-in fallback. The built-in exists so a brand-new workspace with no configuration at all
 * still runs - on the offline model, saying so - rather than failing with "no policy".
 */
@Service
public class RoutingPolicyResolver {

    /**
     * The last resort when nothing is configured.
     *
     * <p>Deliberately the sandbox alone. A default that pointed at a real provider would start
     * spending money for a workspace that never chose to, and a default that pointed at nothing
     * would make a fresh installation look broken.
     */
    private static final RoutingPolicy BUILT_IN = new RoutingPolicy(
            List.of(RoutingPolicy.Candidate.of("sandbox", "sandbox-1")),
            RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
            1,
            Duration.ofMinutes(2),
            true);

    private final ModelPolicies policies;

    public RoutingPolicyResolver(ModelPolicies policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public RoutingPolicy resolve(UUID orgId, UUID agentId) {
        Optional<ModelPolicyEntity> agentPolicy = policies.findByOrgIdAndAgentId(orgId, agentId)
                .filter(policy -> !policy.getCandidates().isEmpty());
        if (agentPolicy.isPresent()) {
            return toPolicy(agentPolicy.get());
        }

        return policies.findWorkspaceDefault(orgId)
                .filter(policy -> !policy.getCandidates().isEmpty())
                .map(this::toPolicy)
                .orElse(BUILT_IN);
    }

    private RoutingPolicy toPolicy(ModelPolicyEntity entity) {
        List<RoutingPolicy.Candidate> candidates = entity.getCandidates().stream()
                .sorted(java.util.Comparator.comparing(ModelPolicyCandidate::getPosition))
                .map(candidate -> new RoutingPolicy.Candidate(
                        candidate.getProviderId(),
                        candidate.getModelId(),
                        candidate.getTemperature() == null ? null : candidate.getTemperature().doubleValue(),
                        candidate.getMaxOutputTokens(),
                        candidate.getWeight()))
                .toList();

        return new RoutingPolicy(
                candidates,
                RoutingPolicy.ExhaustedBehaviour.valueOf(entity.getExhaustedBehaviour()),
                entity.getMaxAttemptsPerCandidate(),
                Duration.ofSeconds(entity.getOverallDeadlineSeconds()),
                entity.isCompactOnOverflow());
    }
}
