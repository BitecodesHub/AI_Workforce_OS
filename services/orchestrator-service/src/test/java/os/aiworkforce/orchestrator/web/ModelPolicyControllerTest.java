package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Saving a routing policy: it holds the workspace's provider lock first, and it refuses a policy
 * that would stop every run because every provider it lists is switched off.
 */
class ModelPolicyControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private ModelPolicies policies;
    private Models models;
    private Agents agents;
    private WorkspaceProviderSettings settings;
    private JpaProviderRegistry registry;
    private AuditClient audit;
    private ModelPolicyController controller;

    @BeforeEach
    void setUp() {
        policies = mock(ModelPolicies.class);
        models = mock(Models.class);
        agents = mock(Agents.class);
        settings = mock(WorkspaceProviderSettings.class);
        registry = mock(JpaProviderRegistry.class);
        audit = mock(AuditClient.class);
        controller = new ModelPolicyController(policies, models, agents, settings, registry, audit);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        when(models.findById(any(LlmModelEntity.Key.class))).thenReturn(Optional.of(mock(LlmModelEntity.class)));
        when(policies.findWorkspaceDefault(ORG)).thenReturn(Optional.empty());
        when(policies.findByOrgIdAndAgentId(ORG, AGENT)).thenReturn(Optional.empty());
        when(agents.findByIdAndOrgId(AGENT, ORG)).thenReturn(Optional.of(mock(Agent.class)));
        when(policies.save(any(ModelPolicyEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private void providers(String on, String... off) {
        List<WorkspaceProvider> all = new java.util.ArrayList<>();
        all.add(provider(on, true));
        for (String id : off) {
            all.add(provider(id, false));
        }
        when(registry.workspaceProviders(ORG)).thenReturn(all);
    }

    private static WorkspaceProvider provider(String id, boolean enabled) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(id);
        return new WorkspaceProvider(entity, enabled, "unknown", null);
    }

    private static ModelPolicyController.ModelPolicyRequest request(String behaviour, String... candidates) {
        List<ModelPolicyController.CandidateInput> inputs = new java.util.ArrayList<>();
        for (String candidate : candidates) {
            String[] parts = candidate.split("/", 2);
            inputs.add(new ModelPolicyController.CandidateInput(parts[0], parts[1], BigDecimal.ONE, null));
        }
        return new ModelPolicyController.ModelPolicyRequest(inputs, behaviour, 2, 300, true);
    }

    @Test
    @DisplayName("a save takes the workspace's provider lock before it reads anything")
    void lockIsTakenFirst() {
        providers("groq");

        controller.setWorkspaceDefault(request("FAIL_CLOSED", "groq/llama"));

        InOrder order = inOrder(settings, policies);
        order.verify(settings).holdWorkspaceLock(ORG);
        order.verify(policies).findWorkspaceDefault(ORG);
        order.verify(policies).save(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("an agent's policy takes the same lock")
    void agentSaveTakesTheLock() {
        providers("groq");

        controller.setForAgent(AGENT, request("FAIL_CLOSED", "groq/llama"));

        InOrder order = inOrder(settings, policies);
        order.verify(settings).holdWorkspaceLock(ORG);
        order.verify(policies).save(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("a policy that stops when it fails and lists only providers that are off is refused with a 409")
    void everyProviderOffIsRefused() {
        providers("groq", "openrouter", "anthropic");

        ApiException refused = catchThrowableOfType(
                () -> controller.setWorkspaceDefault(request("FAIL_CLOSED", "openrouter/qwen", "anthropic/claude")),
                ApiException.class);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo(ErrorCode.RESOURCE_IN_USE);
        assertThat(refused.getMessage()).contains("switched off for this workspace").contains("every run");
        assertThat(refused.details()).containsEntry("routing", "workspace");
        verify(policies, never()).save(any());
    }

    @Test
    @DisplayName("the same refusal for an agent's own policy, saying whose it is")
    void agentPolicyRefused() {
        providers("groq", "openrouter");

        ApiException refused = catchThrowableOfType(
                () -> controller.setForAgent(AGENT, request("FAIL_CLOSED", "openrouter/qwen")), ApiException.class);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.getMessage()).contains("this agent's runs");
        assertThat(refused.details()).containsEntry("routing", "agent");
        verify(policies, never()).save(any());
    }

    @Test
    @DisplayName("one provider that is on is enough")
    void oneProviderOnIsEnough() {
        providers("groq", "openrouter");

        ModelPolicyController.ModelPolicyView view =
                controller.setWorkspaceDefault(request("FAIL_CLOSED", "openrouter/qwen", "groq/llama"));

        assertThat(view.candidates()).hasSize(2);
        verify(policies).save(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("a policy that falls back to the offline model always has somewhere to go")
    void sandboxFallbackIsNotRefused() {
        providers("groq", "openrouter");

        controller.setWorkspaceDefault(request("DEGRADE_TO_SANDBOX", "openrouter/qwen"));

        verify(policies).save(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("a request that leaves the behaviour out keeps the policy's own, and is judged by it")
    void behaviourLeftOutKeepsTheCurrentOne() {
        providers("groq", "openrouter");
        ModelPolicyEntity existing = new ModelPolicyEntity();
        existing.setOrgId(ORG);
        existing.setExhaustedBehaviour("DEGRADE_TO_SANDBOX");
        when(policies.findWorkspaceDefault(ORG)).thenReturn(Optional.of(existing));

        controller.setWorkspaceDefault(request(null, "openrouter/qwen"));

        verify(policies).save(existing);
    }

    @Test
    @DisplayName("a policy with no candidates is treated as unset, not refused")
    void emptyPolicyIsNotRefused() {
        providers("groq");

        controller.setWorkspaceDefault(request("FAIL_CLOSED"));

        verify(policies).save(any(ModelPolicyEntity.class));
    }

    @Test
    @DisplayName("a provider this workspace cannot see counts as off")
    void invisibleProviderCountsAsOff() {
        providers("groq");

        ApiException refused = catchThrowableOfType(
                () -> controller.setWorkspaceDefault(request("FAIL_CLOSED", "someone-elses/model")),
                ApiException.class);

        assertThat(refused.status()).isEqualTo(409);
    }
}
