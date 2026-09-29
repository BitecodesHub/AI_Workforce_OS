package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;

class ModelRouterPlannerTest {

    private static final UUID ORG = UUID.randomUUID();

    private ModelRouter router;
    private RoutingPolicyResolver policies;
    private AgentVersions versions;
    private ToolGrants grants;
    private ModelRouterPlanner planner;

    private Agent support;
    private Agent hr;

    private static Agent agent(String key, String name, String category, boolean fallback) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        agent.setCategory(category);
        agent.setStatus("active");
        agent.setFallback(fallback);
        return agent;
    }

    @BeforeEach
    void setUp() {
        router = mock(ModelRouter.class);
        policies = mock(RoutingPolicyResolver.class);
        versions = mock(AgentVersions.class);
        grants = mock(ToolGrants.class);
        planner = new ModelRouterPlanner(router, policies, versions, grants, new ObjectMapper());

        support = agent("support", "Customer Support", "support", false);
        hr = agent("hr", "HR", "operations", false);

        lenient().when(policies.resolve(any(), any())).thenReturn(mock(RoutingPolicy.class));
        lenient().when(versions.findById(any())).thenReturn(Optional.empty());
        lenient().when(grants.findByAgentIdAndEnabledTrue(any())).thenReturn(List.of());
    }

    private static ChatResponse response(String provider, String content) {
        return new ChatResponse(
                content,
                List.of(),
                FinishReason.STOP,
                TokenUsage.of(120, 30),
                provider,
                "some-model",
                Duration.ofMillis(20),
                List.of(),
                Map.of());
    }

    private ArgumentCaptor<ChatRequest> captureRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(router.route(captor.capture(), any(), any()))
                .thenReturn(response(
                        "openrouter",
                        "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"reply\"}],\"reason\":\"ok\"}"));
        return captor;
    }

    @Test
    void aFencedJsonReplyIsParsed() {
        when(router.route(any(), any(), any()))
                .thenReturn(
                        response(
                                "openrouter",
                                "Sure, here you go:\n```json\n{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"Reply to the customer\"}],\"reason\":\"Support fits\"}\n```"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(1);
        assertThat(plan.get().steps().getFirst().agentId()).isEqualTo(support.getId());
        assertThat(plan.get().steps().getFirst().instruction()).isEqualTo("Reply to the customer");
        assertThat(plan.get().reason()).isEqualTo("Support fits");
    }

    @Test
    void schemaCarriesTheActiveKeysEnumAndForbidsExtraProperties() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String schema = captor.getValue().jsonSchema();
        assertThat(schema).contains("\"additionalProperties\":false");
        assertThat(schema).contains("\"enum\":[\"support\",\"hr\"]");
    }

    @Test
    void promptContainsTheHintWhenALastAnswerAgentIsGiven() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "shorter please", List.of(support, hr), new ModelRouterPlanner.PlanHints(support));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("The last reply in this conversation came from Customer Support (key support)");
    }

    @Test
    void promptHasNoHintWhenThereIsNoLastAnswerAgent() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).doesNotContain("The last reply in this conversation came from");
    }

    @Test
    void theActiveFallbacksKeyIsValidAndAppearsInThePrompt() {
        Agent general = agent("general-employee", "General Employee", "operations", true);
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(router.route(captor.capture(), any(), any()))
                .thenReturn(
                        response(
                                "openrouter",
                                "{\"plan\":[{\"agentKey\":\"general-employee\",\"instruction\":\"help\"}],\"reason\":\"nothing else fits\"}"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "something odd", List.of(support, general));

        assertThat(plan).isPresent();
        assertThat(plan.get().steps().getFirst().agentId()).isEqualTo(general.getId());
        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("choose \"general-employee\"");
        assertThat(captor.getValue().jsonSchema()).contains("\"general-employee\"");
    }

    @Test
    void withNoActiveFallbackThePromptHasNoChooseLineForIt() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("- Choose the agent whose work the request is.");
        assertThat(prompt).doesNotContain("clearly fits, choose");
    }

    @Test
    void aSandboxProviderReplyReturnsEmpty() {
        when(router.route(any(), any(), any()))
                .thenReturn(response(
                        "sandbox",
                        "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"reply\"}],\"reason\":\"ok\"}"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        assertThat(plan).isEmpty();
    }
}
