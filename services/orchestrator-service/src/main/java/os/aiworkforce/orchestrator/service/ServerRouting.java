// @find: server routing, deployment model defaults, local fallback, Ollama, AIWOS_LOCAL_FALLBACK, AIWOS_OLLAMA_BASE_URL, AIWOS_OLLAMA_MODEL, AIWOS_DEFAULT_ROUTING, default model chain, ServerRouting
// @what: What a deployment sets about model routing from its environment: a default chain for workspaces with none, and a local model appended last.
// @flow: Read by RoutingPolicyResolver and ServerProviderSetup.
package os.aiworkforce.orchestrator.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import os.aiworkforce.llm.router.RoutingPolicy;

/**
 * Model routing a deployment sets for itself, from its environment, without changing any saved
 * policy.
 *
 * <ul>
 *   <li>{@code AIWOS_DEFAULT_ROUTING}: a comma-separated chain of {@code provider/model} used by a
 *       workspace that has chosen no models (for example the demo workspace on a fresh server).
 *       A workspace or agent that saves its own policy is never affected.
 *   <li>{@code AIWOS_LOCAL_FALLBACK=true}: the local model ({@code ollama/AIWOS_OLLAMA_MODEL}) is
 *       appended as the last candidate of every chain at run time, so runs still answer when every
 *       cloud model fails. Saved policies are not modified.
 *   <li>{@code AIWOS_OLLAMA_BASE_URL}: where the local model server answers, OpenAI-compatible
 *       (for example {@code http://ollama:11434/v1}).
 * </ul>
 */
@Component
public class ServerRouting {

    /** The provider row the local model server is reached through (seeded by V22). */
    public static final String OLLAMA_PROVIDER = "ollama";

    /** Small, tool-capable and about 1 GB in memory: fits beside the whole stack on an 8 GB server. */
    public static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:1.5b-instruct";

    private final boolean localFallback;
    private final String ollamaBaseUrl;
    private final String ollamaModel;
    private final List<RoutingPolicy.Candidate> defaultChain;

    public ServerRouting(
            @Value("${AIWOS_LOCAL_FALLBACK:false}") boolean localFallback,
            @Value("${AIWOS_OLLAMA_BASE_URL:}") String ollamaBaseUrl,
            @Value("${AIWOS_OLLAMA_MODEL:" + DEFAULT_OLLAMA_MODEL + "}") String ollamaModel,
            @Value("${AIWOS_DEFAULT_ROUTING:}") String defaultRouting) {
        this.localFallback = localFallback;
        this.ollamaBaseUrl = ollamaBaseUrl == null ? "" : ollamaBaseUrl.strip();
        this.ollamaModel = ollamaModel == null || ollamaModel.isBlank() ? DEFAULT_OLLAMA_MODEL : ollamaModel.strip();
        this.defaultChain = parse(defaultRouting);
    }

    /** Nothing configured: the behaviour of an installation that sets none of these variables. */
    public static ServerRouting none() {
        return new ServerRouting(false, "", DEFAULT_OLLAMA_MODEL, "");
    }

    public boolean localFallback() {
        return localFallback;
    }

    public String ollamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public String ollamaModel() {
        return ollamaModel;
    }

    /** The chain for a workspace with no policy of its own; empty when none is configured. */
    public List<RoutingPolicy.Candidate> defaultChain() {
        return defaultChain;
    }

    /** The candidate appended last when the local fallback is on. */
    public RoutingPolicy.Candidate localCandidate() {
        return RoutingPolicy.Candidate.of(OLLAMA_PROVIDER, ollamaModel);
    }

    /**
     * The chain as it runs: the policy's own candidates and, when the local fallback is on and the
     * policy does not already name it, the local model last. Everything else about the policy is
     * kept.
     */
    public RoutingPolicy withLocalFallback(RoutingPolicy policy) {
        if (!localFallback) {
            return policy;
        }
        RoutingPolicy.Candidate local = localCandidate();
        boolean present = policy.candidates().stream().anyMatch(c -> c.key().equals(local.key()));
        if (present) {
            return policy;
        }
        List<RoutingPolicy.Candidate> candidates = new ArrayList<>(policy.candidates());
        candidates.add(local);
        return new RoutingPolicy(
                candidates,
                policy.exhausted(),
                policy.maxAttemptsPerCandidate(),
                policy.overallDeadline(),
                policy.compactOnOverflow());
    }

    static List<RoutingPolicy.Candidate> parse(String chain) {
        List<RoutingPolicy.Candidate> out = new ArrayList<>();
        if (chain == null || chain.isBlank()) {
            return List.of();
        }
        for (String entry : chain.split(",")) {
            String value = entry.strip();
            int slash = value.indexOf('/');
            if (slash <= 0 || slash == value.length() - 1) {
                continue;
            }
            out.add(RoutingPolicy.Candidate.of(value.substring(0, slash).strip(), value.substring(slash + 1).strip()));
        }
        return List.copyOf(out);
    }
}
