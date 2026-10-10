// @find: model router, LLM, model providers, routing policy, ordered candidates, fallback order, when all models fail, exhausted behaviour, agent model preference, RoutingPolicy
// @what: Ordered list of models to try for an agent and what to do when none work.
// @flow: Built from the agent's settings; consumed by ModelRouter.route.
package os.aiworkforce.llm.router;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The ordered list of models to try, and what to do when none of them work.
 *
 * <p>Stored against an agent, so one agent can prefer a fast cheap model while another insists on
 * a large one, without either being named in code. Resolution order when a call is made is:
 * an explicit override on the request, then the agent's policy, then the workspace default, then
 * the platform default - so a workspace can change every agent at once, and an agent can still
 * opt out.
 *
 * @param candidates models to try, in order; the first viable one is used
 * @param exhausted what to do when every candidate has been skipped or has failed
 * @param maxAttemptsPerCandidate retries against one candidate before moving on
 * @param overallDeadline total time for the whole chain, including waits between retries
 * @param compactOnOverflow whether to shorten the conversation rather than fail on overflow
 */
public record RoutingPolicy(
        List<Candidate> candidates,
        ExhaustedBehaviour exhausted,
        int maxAttemptsPerCandidate,
        Duration overallDeadline,
        boolean compactOnOverflow) {

    /**
     * What happens when the chain runs out.
     *
     * <p>The default is to fail. Quietly substituting an offline model would mean a person acts
     * on a placeholder answer believing a real model produced it, and nothing in the interface
     * would say otherwise. A workspace can choose the substitute deliberately - a demonstration
     * environment reasonably would - but it has to choose it.
     */
    public enum ExhaustedBehaviour {
        FAIL_CLOSED,
        DEGRADE_TO_SANDBOX
    }

    public RoutingPolicy {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        exhausted = exhausted == null ? ExhaustedBehaviour.FAIL_CLOSED : exhausted;
        if (maxAttemptsPerCandidate < 1) {
            maxAttemptsPerCandidate = 1;
        }
    }

    public static RoutingPolicy of(List<Candidate> candidates) {
        return new RoutingPolicy(candidates, ExhaustedBehaviour.FAIL_CLOSED, 2, Duration.ofMinutes(5), true);
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    /**
     * One model to try.
     *
     * @param providerId the provider row to use
     * @param modelId the model to ask for
     * @param temperature overrides the request's temperature for this candidate only
     * @param maxOutputTokens overrides the request's output cap for this candidate only
     * @param weight tie-break among otherwise equal candidates; higher is preferred
     */
    public record Candidate(
            String providerId, String modelId, Double temperature, Integer maxOutputTokens, int weight) {

        public Candidate {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(modelId, "modelId");
        }

        public static Candidate of(String providerId, String modelId) {
            return new Candidate(providerId, modelId, null, null, 0);
        }

        public String key() {
            return providerId + "/" + modelId;
        }
    }
}
