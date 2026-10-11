// @find: routing policy, model chain, which model, agent model policy, workspace default policy, no AI model is set, model fallback order
// @what: Works out the ordered chain of models for an agent: its own policy first, then the workspace default.
// @flow: Called by AgentRunner before routing; reads ModelPolicies
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
 * <p>Resolution runs narrowest first: the agent's own policy, then the workspace default. When
 * neither lists a model the chain is empty and the run fails with a plain "no AI model is set"
 * - never a quiet answer from the offline sandbox, which a person could mistake for a real
 * model's work. The sandbox only answers when an administrator lists it as a candidate.
 */
@Service
public class RoutingPolicyResolver {

    /** Nothing chosen anywhere: an empty chain, which the router refuses with a plain sentence. */
    static final RoutingPolicy NOTHING_SET = new RoutingPolicy(
            List.of(), RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, 1, Duration.ofMinutes(2), true);

    private final ModelPolicies policies;
    private final ServerRouting server;

    /**
     * For tests and callers with no deployment settings: behaves as if no deployment routing is configured.
     *
     * @param policies the saved model policies
     */
    public RoutingPolicyResolver(ModelPolicies policies) {
        this(policies, ServerRouting.none());
    }

    @org.springframework.beans.factory.annotation.Autowired
    /**
     * The constructor Spring uses.
     *
     * @param policies the saved model policies
     * @param server the deployment's routing settings; null is treated as none
     */
    public RoutingPolicyResolver(ModelPolicies policies, ServerRouting server) {
        this.policies = policies;
        this.server = server == null ? ServerRouting.none() : server;
    }

    // @find: workspace default routing policy
    /**
     * The chain a run would use for the workspace with no agent in play: the workspace's own policy,
     * else the deployment default chain, with the local fallback appended when it is on. The chain is
     * empty only when none of these supplies a model. Read only.
     *
     * @param orgId the workspace
     * @return the resolved policy; never null
     */
    @Transactional(readOnly = true)
    public RoutingPolicy workspaceDefault(UUID orgId) {
        return resolve(orgId, null);
    }

    // @find: resolve routing policy for agent
    /**
     * The policy a run will route with: the saved chain for the agent, else the workspace, else the
     * deployment default, with the local fallback model appended last when it is on. Read only. Called
     * by AgentRunner before routing.
     *
     * @param orgId the workspace
     * @param agentId the agent; null skips the agent level
     * @return the resolved policy; an empty chain (fail closed) when no model is set anywhere
     */
    @Transactional(readOnly = true)
    public RoutingPolicy resolve(UUID orgId, UUID agentId) {
        return server.withLocalFallback(saved(orgId, agentId));
    }

    /**
     * The saved chain: the agent's, then the workspace's, then the deployment's default chain
     * ({@code AIWOS_DEFAULT_ROUTING}) for a workspace that has chosen nothing. The local fallback
     * is appended by {@link #resolve} on top of whichever applies, never saved.
     */
    private RoutingPolicy saved(UUID orgId, UUID agentId) {
        Optional<ModelPolicyEntity> agentPolicy = policies.findByOrgIdAndAgentId(orgId, agentId)
                .filter(policy -> !policy.getCandidates().isEmpty());
        if (agentPolicy.isPresent()) {
            return toPolicy(agentPolicy.get());
        }

        return policies.findWorkspaceDefault(orgId)
                .filter(policy -> !policy.getCandidates().isEmpty())
                .map(this::toPolicy)
                .orElseGet(this::deploymentDefault);
    }

    // The deployment's default chain (AIWOS_DEFAULT_ROUTING) as a policy, or NOTHING_SET when none is configured. Fails closed, two attempts per candidate, five minute overall deadline.
    private RoutingPolicy deploymentDefault() {
        if (server.defaultChain().isEmpty()) {
            return NOTHING_SET;
        }
        return new RoutingPolicy(
                server.defaultChain(), RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, 2, Duration.ofMinutes(5), true);
    }

    // Converts a saved policy to the router's form: candidates in saved position order, with the saved exhaustion behaviour, attempt limit, deadline and compaction flag. Throws IllegalArgumentException if the saved exhaustion behaviour is not a known value.
    private RoutingPolicy toPolicy(ModelPolicyEntity entity) {
        List<RoutingPolicy.Candidate> candidates = entity.getCandidates().stream()
                .sorted(java.util.Comparator.comparing(ModelPolicyCandidate::getPosition))
                .map(candidate -> new RoutingPolicy.Candidate(
                        candidate.getProviderId(),
                        candidate.getModelId(),
                        candidate.getTemperature() == null
                                ? null
                                : candidate.getTemperature().doubleValue(),
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
