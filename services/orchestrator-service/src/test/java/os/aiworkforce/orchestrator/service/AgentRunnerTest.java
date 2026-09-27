package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The trace records what the agent was asked and what the run cost, and a resumed run remembers both. */
class AgentRunnerTest {

    private static final String INSTRUCTION = "Draft a welcome email for the new starter and send it.";

    private Runs runs;
    private RunSteps steps;
    private Agents agents;
    private AgentVersions versions;
    private ModelRouter router;
    private Usage usage;
    private TaskProgress progress;
    private RoutingPolicyResolver policies;
    private AgentRunner runner;

    private Agent agent;
    private AgentVersion version;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        agents = mock(Agents.class);
        versions = mock(AgentVersions.class);
        router = mock(ModelRouter.class);
        usage = mock(Usage.class);
        progress = mock(TaskProgress.class);
        policies = mock(RoutingPolicyResolver.class);
        runner = new AgentRunner(
                runs, steps, agents, versions, mock(ToolGrants.class), router, mock(ToolGateway.class),
                policies, mock(ApprovalService.class), mock(ToolCredentialResolver.class),
                mock(AuditClient.class), usage, progress);

        agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey("hr");
        agent.setName("HR");
        version = new AgentVersion();
        version.setId(UUID.randomUUID());
        version.setAgentId(agent.getId());
        version.setOrgId(ORG);
        version.setRevision(1);
        version.setSystemPrompt("You handle people operations for a small care provider.");
        version.setMaxSteps(5);
        agent.setCurrentVersionId(version.getId());

        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(usage.costForRun(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the first step of a new run is the instruction the agent was given")
    void startRecordsInstruction() {
        when(steps.highestPosition(any())).thenReturn(-1, 0);
        when(router.route(any(), any(), any())).thenReturn(answer("Drafted and sent."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        RunStep first = saved.getAllValues().getFirst();
        assertThat(first.getPosition()).isZero();
        assertThat(first.getKind()).isEqualTo("note");
        assertThat(first.getDetail())
                .containsEntry("type", "instruction")
                .containsEntry("content", INSTRUCTION);
        assertThat(saved.getAllValues()).extracting(RunStep::getKind).containsExactly("note", "model_call");
    }

    @Test
    @DisplayName("a resumed run rebuilds its conversation with the original instruction after the system prompt")
    void resumeRebuildsInstruction() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(ORG, runId, 1, "model_call", Map.of("content", "I have drafted it and will send it.")),
                RunStep.of(ORG, runId, 2, "approval", Map.of("tool", "gmail.send_message"))));
        when(steps.highestPosition(runId)).thenReturn(2);
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages.getFirst().role()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(messages.getFirst().content()).startsWith(version.getSystemPrompt());
        assertThat(messages.get(1).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(messages.get(1).content()).isEqualTo(INSTRUCTION);
        assertThat(messages.get(2).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(messages.get(2).content()).isEqualTo("I have drafted it and will send it.");
        // A step written before toolCallId existed falls back to the tool's name, so an old
        // parked run still resumes rather than throwing.
        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(messages.get(3).toolCallId()).isEqualTo("gmail.send_message");
        assertThat(messages.get(3).content()).contains("Approved by a person");
        assertThat(outcome.status()).isEqualTo("completed");
        verify(progress).onRunResumed(run);
        verify(progress).onRunFinished(run, "completed", "Sent.", null);
    }

    @Test
    @DisplayName("a resumed run keeps the assistant's original tool call, so it knows the call it made rather than repeating it")
    void resumeRebuildsToolCallNotJustApproval() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(ORG, runId, 1, "model_call", Map.of(
                        "content", "",
                        "toolCallRecords", List.of(Map.of(
                                "id", "call_1",
                                "name", "gmail.send_message",
                                "argumentsJson", "{\"to\":\"priya@example.com\"}")))),
                RunStep.of(ORG, runId, 2, "approval", Map.of("tool", "gmail.send_message", "toolCallId", "call_1"))));
        when(steps.highestPosition(runId)).thenReturn(2);
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));

        runner.resume(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        ChatMessage assistantTurn = messages.get(2);
        assertThat(assistantTurn.role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(assistantTurn.toolCalls())
                .extracting(ToolCall::id, ToolCall::name, ToolCall::argumentsJson)
                .containsExactly(tuple("call_1", "gmail.send_message", "{\"to\":\"priya@example.com\"}"));
        ChatMessage toolReply = messages.get(3);
        assertThat(toolReply.role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(toolReply.toolCallId()).isEqualTo("call_1");
        assertThat(toolReply.content()).contains("Do not repeat it");
    }

    @Test
    @DisplayName("each model step is charged what the router recorded since the last one, and the run carries the total")
    void costRecorded() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setTotalCost(new BigDecimal("0.0010"));
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(List.of());
        when(steps.highestPosition(runId)).thenReturn(2);
        when(usage.costForRun(runId)).thenReturn(new BigDecimal("0.0042"));
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));

        runner.resume(ORG, runId);

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("model_call");
        assertThat(saved.getValue().getCost()).isEqualByComparingTo("0.0032");
        assertThat(run.getTotalCost()).isEqualByComparingTo("0.0042");
    }

    @Test
    @DisplayName("a run that fails inside the router still carries the cost of the attempts it made")
    void failedRunKeepsAttemptCost() {
        when(steps.highestPosition(any())).thenReturn(-1, 0);
        when(usage.costForRun(any())).thenReturn(new BigDecimal("0.0007"));
        when(router.route(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.NO_MODEL_AVAILABLE, "Every candidate failed."));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(runs, atLeastOnce()).save(saved.capture());
        Run run = saved.getValue();
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getTotalCost()).isEqualByComparingTo("0.0007");
        verify(progress).onRunFinished(run, "failed", null, "Every candidate failed.");
    }

    @Test
    @DisplayName("a refusal inside the loop ends the saved run as failed rather than leaving it running")
    void refusalInsideLoopFailsRun() {
        when(steps.highestPosition(any())).thenReturn(-1, 0);
        when(policies.resolve(any(), any()))
                .thenThrow(new ApiException(ErrorCode.POLICY_VIOLATION, "No routing policy allows a model."));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        verify(runs, atLeastOnce()).save(saved.capture());
        Run run = saved.getValue();
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getFailureReason()).isEqualTo("No routing policy allows a model.");
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues()).extracting(RunStep::getKind).containsExactly("note", "error");
        verify(progress).onRunFinished(run, "failed", null, "No routing policy allows a model.");
    }

    @Test
    @DisplayName("an agent with no configuration is refused before anything is written")
    void noConfigurationRefusedBeforeWriting() {
        agent.setCurrentVersionId(null);

        assertThatThrownBy(
                        () -> runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CONFLICT);
        verifyNoInteractions(runs, steps);
    }

    @Test
    @DisplayName("a provider that answers with no content and no recognised finish reason fails the run rather than completing it blank")
    void blankAnswerFailsRatherThanCompletes() {
        when(steps.highestPosition(any())).thenReturn(-1, 0);
        when(router.route(any(), any(), any())).thenReturn(new ChatResponse(
                "", List.of(), FinishReason.UNKNOWN, TokenUsage.of(120, 0), "openrouter", "some-model",
                Duration.ofMillis(40), List.of(), Map.of()));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(runs, atLeastOnce()).save(saved.capture());
        Run run = saved.getValue();
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getFailureReason()).contains("returned no answer");
        verify(progress).onRunFinished(run, "failed", null, run.getFailureReason());
    }

    @Test
    @DisplayName("the reaper fails an abandoned run's task through the same rules")
    void reaperReportsToTask() {
        Run run = parkedRun(UUID.randomUUID());
        run.setStatus("running");
        when(runs.findAbandoned(any(), any())).thenReturn(List.of(run));

        assertThat(runner.reapAbandoned(50)).isEqualTo(1);

        assertThat(run.getStatus()).isEqualTo("abandoned");
        verify(progress).onRunFinished(run, "abandoned", null, "The worker running this task stopped responding.");
    }

    private Run parkedRun(UUID runId) {
        Run run = new Run();
        run.setId(runId);
        run.setOrgId(ORG);
        run.setTaskId(UUID.randomUUID());
        run.setAgentId(agent.getId());
        run.setAgentVersionId(version.getId());
        run.setStatus("waiting_approval");
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));
        return run;
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content, List.of(), FinishReason.STOP, TokenUsage.of(120, 30), "sandbox", "sandbox-echo",
                Duration.ofMillis(40), List.of(), Map.of());
    }
}
