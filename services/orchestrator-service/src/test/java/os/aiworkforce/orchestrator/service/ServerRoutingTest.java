// @find: tests for server routing, local fallback, Ollama appended last, AIWOS_DEFAULT_ROUTING, deployment default chain
// @what: Checks the local model is appended last at run time without touching saved policies, and the deployment default chain applies only where nothing is saved.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.ModelPolicies;

class ServerRoutingTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final String NOVA = "bedrock/apac.amazon.nova-lite-v1:0";

    private ModelPolicies policies;
    private ModelPolicyEntity workspace;

    @BeforeEach
    void setUp() {
        policies = mock(ModelPolicies.class);
        when(policies.findByOrgIdAndAgentId(eq(ORG), any())).thenReturn(Optional.empty());
        when(policies.findWorkspaceDefault(ORG)).thenAnswer(call -> Optional.ofNullable(workspace));
    }

    private static ModelPolicyEntity policy(String... keys) {
        ModelPolicyEntity entity = new ModelPolicyEntity();
        UUID id = UUID.randomUUID();
        List<ModelPolicyCandidate> rows = new java.util.ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            String[] parts = keys[i].split("/", 2);
            rows.add(ModelPolicyCandidate.of(id, i, parts[0], parts[1]));
        }
        entity.setCandidates(rows);
        return entity;
    }

    private static List<String> keys(RoutingPolicy policy) {
        return policy.candidates().stream().map(RoutingPolicy.Candidate::key).toList();
    }

    @Test
    @DisplayName("appends the local model as the last candidate of a saved policy, without changing the saved rows")
    void appendsLocalModelLast() {
        workspace = policy("groq/llama-3.3-70b-versatile", NOVA);
        ServerRouting server = new ServerRouting(true, "http://ollama:11434/v1", "", "");
        RoutingPolicyResolver resolver = new RoutingPolicyResolver(policies, server);

        RoutingPolicy resolved = resolver.resolve(ORG, AGENT);

        assertThat(keys(resolved))
                .containsExactly("groq/llama-3.3-70b-versatile", NOVA, "ollama/qwen2.5:1.5b-instruct");
        assertThat(workspace.getCandidates()).hasSize(2);
        assertThat(resolved.maxAttemptsPerCandidate()).isEqualTo(2);
    }

    @Test
    @DisplayName("does not add the local model twice when a policy already names it")
    void noDuplicate() {
        workspace = policy("ollama/qwen2.5:1.5b-instruct", NOVA);
        RoutingPolicyResolver resolver =
                new RoutingPolicyResolver(policies, new ServerRouting(true, "", "qwen2.5:1.5b-instruct", ""));

        assertThat(keys(resolver.resolve(ORG, null))).containsExactly("ollama/qwen2.5:1.5b-instruct", NOVA);
    }

    @Test
    @DisplayName("gives a workspace with no policy the deployment's chain: Bedrock first, then the local model")
    void deploymentDefaultForEmptyWorkspace() {
        workspace = null;
        ServerRouting server = new ServerRouting(true, "http://ollama:11434/v1", "llama3.2:3b", NOVA);

        RoutingPolicy resolved = new RoutingPolicyResolver(policies, server).resolve(ORG, null);

        assertThat(keys(resolved)).containsExactly(NOVA, "ollama/llama3.2:3b");
        assertThat(resolved.exhausted()).isEqualTo(RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED);
    }

    @Test
    @DisplayName("changes nothing when the deployment sets none of the variables")
    void offByDefault() {
        workspace = null;
        assertThat(new RoutingPolicyResolver(policies).resolve(ORG, null).isEmpty()).isTrue();

        workspace = policy(NOVA);
        assertThat(keys(new RoutingPolicyResolver(policies, ServerRouting.none()).resolve(ORG, null)))
                .containsExactly(NOVA);
    }

    @Test
    @DisplayName("reads a comma-separated chain and ignores entries that are not provider/model")
    void parsesChain() {
        assertThat(ServerRouting.parse(" bedrock/apac.amazon.nova-lite-v1:0 , junk, /x, y/ ,ollama/qwen2.5:1.5b-instruct"))
                .extracting(RoutingPolicy.Candidate::key)
                .containsExactly(NOVA, "ollama/qwen2.5:1.5b-instruct");
        assertThat(ServerRouting.parse(null)).isEmpty();
    }
}
