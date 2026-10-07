package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Giving an agent back to the workspace's routing, and the audit trail every policy change leaves.
 *
 * <p>The policies live in a small in-memory table behind the repository mock, and the resolver is
 * the real one, so "the router falls back to the workspace policy" is shown by asking it, not by
 * asserting what the controller called.
 */
class ModelPolicyAgentClearTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private final List<ModelPolicyEntity> table = new ArrayList<>();

    private ModelPolicies policies;
    private AuditClient audit;
    private ModelPolicyController controller;
    private RoutingPolicyResolver resolver;

    @BeforeEach
    void setUp() {
        policies = mock(ModelPolicies.class);
        Models models = mock(Models.class);
        Agents agents = mock(Agents.class);
        WorkspaceProviderSettings settings = mock(WorkspaceProviderSettings.class);
        JpaProviderRegistry registry = mock(JpaProviderRegistry.class);
        audit = mock(AuditClient.class);
        controller = new ModelPolicyController(policies, models, agents, settings, registry, audit);
        resolver = new RoutingPolicyResolver(policies);
        RequestContext.setActor(Actor.user("user-1", ORG.toString(), "role", Set.of(), 0L));

        Agent agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", AGENT);
        agent.setName("Ava");
        when(agents.findByIdAndOrgId(AGENT, ORG)).thenReturn(Optional.of(agent));
        when(models.findById(any(LlmModelEntity.Key.class))).thenReturn(Optional.of(mock(LlmModelEntity.class)));
        LlmProviderEntity groq = new LlmProviderEntity();
        groq.setId("groq");
        when(registry.workspaceProviders(ORG)).thenReturn(List.of(new WorkspaceProvider(groq, true, "unknown", null)));

        when(policies.findWorkspaceDefault(ORG))
                .thenAnswer(call -> table.stream().filter(p -> p.getAgentId() == null).findFirst());
        when(policies.findByOrgIdAndAgentId(eq(ORG), any()))
                .thenAnswer(call -> table.stream()
                        .filter(p -> call.getArgument(1).equals(p.getAgentId()))
                        .findFirst());
        when(policies.save(any(ModelPolicyEntity.class))).thenAnswer(call -> {
            ModelPolicyEntity saved = call.getArgument(0);
            if (!table.contains(saved)) {
                table.add(saved);
            }
            return saved;
        });
        org.mockito.Mockito.doAnswer(call -> {
                    table.remove(call.<ModelPolicyEntity>getArgument(0));
                    return null;
                })
                .when(policies)
                .delete(any(ModelPolicyEntity.class));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static ModelPolicyController.ModelPolicyRequest chain(String... candidates) {
        List<ModelPolicyController.CandidateInput> inputs = new ArrayList<>();
        for (String candidate : candidates) {
            String[] parts = candidate.split("/", 2);
            inputs.add(new ModelPolicyController.CandidateInput(parts[0], parts[1], BigDecimal.ONE, null));
        }
        return new ModelPolicyController.ModelPolicyRequest(inputs, "FAIL_CLOSED", 2, 300, true);
    }

    @Test
    @DisplayName("clearing deletes the agent's chain, and the router then resolves it to the workspace policy")
    void clearFallsBackToTheWorkspacePolicy() {
        controller.setWorkspaceDefault(chain("groq/workspace-model"));
        controller.setForAgent(AGENT, chain("groq/agent-model"));
        assertThat(resolver.resolve(ORG, AGENT).candidates().get(0).modelId()).isEqualTo("agent-model");

        ModelPolicyController.ModelPolicyView view = controller.clearForAgent(AGENT);

        assertThat(view.configured()).isFalse();
        assertThat(view.candidates()).isEmpty();
        RoutingPolicy resolved = resolver.resolve(ORG, AGENT);
        assertThat(resolved.candidates()).extracting(RoutingPolicy.Candidate::modelId).containsExactly("workspace-model");
        // Only the agent's row went; the workspace's own is where it was.
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getAgentId()).isNull();
        assertThat(controller.forAgent(AGENT).configured()).isFalse();
    }

    @Test
    @DisplayName("with no workspace policy either, a cleared agent resolves to the built-in offline model")
    void clearWithNoWorkspacePolicyFallsToTheBuiltIn() {
        controller.setForAgent(AGENT, chain("groq/agent-model"));

        controller.clearForAgent(AGENT);

        assertThat(resolver.resolve(ORG, AGENT).candidates())
                .extracting(RoutingPolicy.Candidate::providerId)
                .containsExactly("sandbox");
    }

    @Test
    @DisplayName("clearing an agent that has no policy of its own changes nothing and is not audited")
    void clearIsIdempotent() {
        ModelPolicyController.ModelPolicyView view = controller.clearForAgent(AGENT);

        assertThat(view.configured()).isFalse();
        verify(policies, never()).delete(any(ModelPolicyEntity.class));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("an unknown agent, or another workspace's, is a 404 and nothing is deleted")
    void clearUnknownAgent() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> controller.clearForAgent(stranger))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        verify(policies, never()).delete(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("clearing needs the permission to set an agent's model policy")
    void clearNeedsTheSetPermission() throws Exception {
        Method method = ModelPolicyController.class.getMethod("clearForAgent", UUID.class);

        assertThat(method.getAnnotation(RequiresPermission.class).value())
                .containsExactly(Permission.Codes.AGENT_SET_MODEL_POLICY);
    }

    @Test
    @DisplayName("every policy change is audited only after it commits, with the chain before and after")
    void changesAreAuditedAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        controller.setForAgent(AGENT, chain("groq/first"));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), anyMap());

        controller.setForAgent(AGENT, chain("groq/second", "groq/third"));
        controller.clearForAgent(AGENT);
        controller.setWorkspaceDefault(chain("groq/workspace"));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), anyMap());

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceId = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.times(4))
                .record(eq(ORG), any(Actor.class), action.capture(), any(), resourceId.capture(), eq("succeeded"), detail.capture());
        assertThat(action.getAllValues())
                .containsExactly(
                        "agent.model_policy.set",
                        "agent.model_policy.set",
                        "agent.model_policy.clear",
                        "model_policy.update");
        assertThat(resourceId.getAllValues())
                .containsExactly(AGENT.toString(), AGENT.toString(), AGENT.toString(), ORG.toString());

        Map<String, Object> second = detail.getAllValues().get(1);
        assertThat(second).containsEntry("scope", "agent").containsEntry("agent", "Ava");
        assertThat(second.get("old")).isEqualTo(Map.of("candidates", List.of("groq/first"), "exhaustedBehaviour", "FAIL_CLOSED"));
        assertThat(second.get("new"))
                .isEqualTo(Map.of("candidates", List.of("groq/second", "groq/third"), "exhaustedBehaviour", "FAIL_CLOSED"));
        Map<String, Object> cleared = detail.getAllValues().get(2);
        assertThat(cleared.get("old"))
                .isEqualTo(Map.of("candidates", List.of("groq/second", "groq/third"), "exhaustedBehaviour", "FAIL_CLOSED"));
        assertThat(cleared.get("new")).isEqualTo(Map.of("candidates", List.of()));
        assertThat(detail.getAllValues().get(3)).containsEntry("scope", "workspace");
    }

    @Test
    @DisplayName("a refused save is not audited")
    void aRefusedSaveLeavesNoEntry() {
        assertThatThrownBy(() -> controller.setForAgent(AGENT, chain("someone-elses/model")))
                .isInstanceOf(ApiException.class);

        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), anyMap());
        assertThat(table).isEmpty();
    }

    @Test
    @DisplayName("the unused model entity is never returned: a policy candidate is saved at the position it was sent")
    void candidatesKeepTheirOrder() {
        controller.setForAgent(AGENT, chain("groq/a", "groq/b", "groq/c"));

        assertThat(table.get(0).getCandidates())
                .extracting(ModelPolicyCandidate::getModelId)
                .containsExactly("a", "b", "c");
    }
}
