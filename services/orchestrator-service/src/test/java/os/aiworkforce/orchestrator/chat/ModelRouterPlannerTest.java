// @find: tests for model router planner, chat, a fenced json reply is parsed, schema carries the active keys enum and forbids extra properties, prompt contains the hint when alast answer agent is given, prompt has no hint when there is no last answer agent, the active fallbacks key is valid and appears in the prompt, with no active fallback the prompt has no choose line for it, a sandbox provider reply returns empty, the prompt says each agent gets the request verbatim so astep is only ashort label, ModelRouterPlannerTest, ModelRouterPlanner
// @what: Tests for ModelRouterPlanner in the orchestrator chat package (16 test methods).
// @flow: Exercises ModelRouterPlanner
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

    // @find: test a fenced json reply is parsed, model router planner
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

    // @find: test schema carries the active keys enum and forbids extra properties, model router planner
    @Test
    void schemaCarriesTheActiveKeysEnumAndForbidsExtraProperties() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String schema = captor.getValue().jsonSchema();
        assertThat(schema).contains("\"additionalProperties\":false");
        assertThat(schema).contains("\"enum\":[\"support\",\"hr\"]");
    }

    // @find: test prompt contains the hint when alast answer agent is given, model router planner
    @Test
    void promptContainsTheHintWhenALastAnswerAgentIsGiven() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "shorter please", List.of(support, hr), new ModelRouterPlanner.PlanHints(support));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("The last reply in this conversation came from Customer Support (key support)");
    }

    // @find: test prompt has no hint when there is no last answer agent, model router planner
    @Test
    void promptHasNoHintWhenThereIsNoLastAnswerAgent() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).doesNotContain("The last reply in this conversation came from");
    }

    // @find: test the active fallbacks key is valid and appears in the prompt, model router planner
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
        assertThat(prompt).contains("Choose \"general-employee\" only when the request lies outside every specialist");
        assertThat(captor.getValue().jsonSchema()).contains("\"general-employee\"");
    }

    // @find: test with no active fallback the prompt has no choose line for it, model router planner
    @Test
    void withNoActiveFallbackThePromptHasNoChooseLineForIt() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("- Choose the agent whose work the request is.");
        assertThat(prompt).doesNotContain("clearly fits, choose");
    }

    // @find: test a sandbox provider reply returns empty, model router planner
    @Test
    void aSandboxProviderReplyReturnsEmpty() {
        when(router.route(any(), any(), any()))
                .thenReturn(response(
                        "sandbox",
                        "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"reply\"}],\"reason\":\"ok\"}"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        assertThat(plan).isEmpty();
    }

    // @find: test the prompt says each agent gets the request verbatim so astep is only ashort label, model router planner
    @Test
    void thePromptSaysEachAgentGetsTheRequestVerbatimSoAStepIsOnlyAShortLabel() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();

        planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        String prompt = captor.getValue().systemPrompt();
        assertThat(prompt).contains("word for word");
        assertThat(prompt).contains("one short sentence of at most 25 words");
        assertThat(prompt).contains("Never copy names, figures, pasted text or any other content");
        assertThat(prompt).doesNotContain("complete request that agent can act on alone");
    }

    // @find: test the persons text reaches the planner exactly as written, model router planner
    @Test
    void thePersonsTextReachesThePlannerExactlyAsWritten() {
        ArgumentCaptor<ChatRequest> captor = captureRequest();
        String text = "  Summarise the risks for the board.\n\n    Clause 4.2: termination on 30 days' notice.\n";

        planner.plan(ORG, text, List.of(support, hr));

        assertThat(captor.getValue().conversation().getLast().content()).isEqualTo(text);
    }

    // @find: test a step sentence that runs on is cut to alabel, model router planner
    @Test
    void aStepSentenceThatRunsOnIsCutToALabel() {
        String runOn = "Summarise the risks in the contract for the board ".repeat(20);
        when(router.route(any(), any(), any()))
                .thenReturn(response(
                        "openrouter",
                        "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"" + runOn + "\"}],\"reason\":\"ok\"}"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        assertThat(plan).isPresent();
        String label = plan.get().steps().getFirst().instruction();
        assertThat(label).hasSizeLessThanOrEqualTo(300);
        assertThat(label).startsWith("Summarise the risks in the contract for the board");
        assertThat(label).doesNotEndWith(" ");
    }

    // @find: test steps the model padded with placeholder parts are dropped, model router planner
    @Test
    void stepsTheModelPaddedWithPlaceholderPartsAreDropped() {
        // Seen live with openai/gpt-oss-20b on NVIDIA: a one-sentence question came back routed
        // to three agents, two of them with the instruction "--", and was answered three times.
        Agent general = agent("general", "General Employee", "general", true);
        when(router.route(any(), any(), any()))
                .thenReturn(response(
                        "nvidia",
                        "{\"plan\":[{\"agentKey\":\"general\",\"instruction\":\"Answer in one sentence.\"},"
                                + "{\"agentKey\":\"support\",\"instruction\":\"--\"},"
                                + "{\"agentKey\":\"hr\",\"instruction\":\"N/A\"}],\"reason\":\"General knowledge.\"}"));

        Optional<ModelRouterPlanner.Plan> plan =
                planner.plan(ORG, "What is the capital of Australia?", List.of(general, support, hr));

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).extracting(ModelRouterPlanner.PlannedStep::agentId)
                .containsExactly(general.getId());
    }

    // @find: test a repeated agent is planned once, model router planner
    @Test
    void aRepeatedAgentIsPlannedOnce() {
        when(router.route(any(), any(), any()))
                .thenReturn(response(
                        "nvidia",
                        "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"Reply to the customer.\"},"
                                + "{\"agentKey\":\"support\",\"instruction\":\"Reply again.\"}],\"reason\":\"ok\"}"));

        Optional<ModelRouterPlanner.Plan> plan = planner.plan(ORG, "a customer wrote in", List.of(support, hr));

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(1);
    }

    // @find: test a plan of only placeholders falls back to the rules, model router planner
    @Test
    void aPlanOfOnlyPlaceholdersFallsBackToTheRules() {
        when(router.route(any(), any(), any()))
                .thenReturn(response("nvidia", "{\"plan\":[{\"agentKey\":\"support\",\"instruction\":\"--\"}],\"reason\":\"ok\"}"));

        assertThat(planner.plan(ORG, "a customer wrote in", List.of(support, hr))).isEmpty();
    }

    // @find: test real labels are not mistaken for filler, model router planner
    @Test
    void realLabelsAreNotMistakenForFiller() {
        assertThat(ModelRouterPlanner.isFiller("--")).isTrue();
        assertThat(ModelRouterPlanner.isFiller("  ")).isTrue();
        assertThat(ModelRouterPlanner.isFiller("N/A.")).isTrue();
        assertThat(ModelRouterPlanner.isFiller("Reply")).isFalse();
        assertThat(ModelRouterPlanner.isFiller("Draft the email to the customer.")).isFalse();
        assertThat(ModelRouterPlanner.isFiller("None of the invoices are late; confirm.")).isFalse();
    }

    // @find: test planning asks each model once within45 seconds, model router planner
    @Test
    void planningAsksEachModelOnceWithin45Seconds() {
        RoutingPolicy workspace = new RoutingPolicy(
                List.of(RoutingPolicy.Candidate.of("nvidia", "a"), RoutingPolicy.Candidate.of("openrouter", "b")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                3,
                Duration.ofMinutes(5),
                true);

        RoutingPolicy planning = ModelRouterPlanner.forPlanning(workspace);

        assertThat(planning.candidates()).isEqualTo(workspace.candidates());
        assertThat(planning.maxAttemptsPerCandidate()).isEqualTo(1);
        assertThat(planning.overallDeadline()).isEqualTo(Duration.ofSeconds(45));
    }

    // @find: test planning asks at most three models so the wait is bounded, model router planner
    @Test
    void planningAsksAtMostThreeModelsSoTheWaitIsBounded() {
        RoutingPolicy workspace = new RoutingPolicy(
                List.of(
                        RoutingPolicy.Candidate.of("nvidia", "a"),
                        RoutingPolicy.Candidate.of("openrouter", "b"),
                        RoutingPolicy.Candidate.of("groq", "c"),
                        RoutingPolicy.Candidate.of("gemini", "d")),
                RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                3,
                Duration.ofMinutes(5),
                true);

        RoutingPolicy planning = ModelRouterPlanner.forPlanning(workspace);

        assertThat(planning.candidates()).extracting(RoutingPolicy.Candidate::providerId)
                .containsExactly("nvidia", "openrouter", "groq");
        assertThat(ModelRouterPlanner.PLANNING_ATTEMPT.multipliedBy(ModelRouterPlanner.MAX_PLANNING_CANDIDATES))
                .isLessThanOrEqualTo(Duration.ofSeconds(45));
    }
}
