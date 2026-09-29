package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.voice.VoiceClipService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The trace records what the agent was asked and what the run cost, and a resumed run remembers
 * both. A run parks for an approval or an answer, and resumes exactly once, only on the decision
 * it parked for.
 */
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
    private ApprovalService approvals;
    private ToolGateway tools;
    private VoiceClipService voiceClips;
    private QuestionService questions;
    private LifecycleAnnouncer announcer;
    private AuditClient audit;
    private PlatformTransactionManager transactions;
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
        approvals = mock(ApprovalService.class);
        tools = mock(ToolGateway.class);
        voiceClips = mock(VoiceClipService.class);
        lenient().when(voiceClips.afterVoiceNote(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(voiceClips.keyStored(any())).thenReturn(false);
        questions = mock(QuestionService.class);
        lenient().when(questions.policyFor(any())).thenReturn(new QuestionService.AskPolicy(true, false, null));
        announcer = mock(LifecycleAnnouncer.class);
        audit = mock(AuditClient.class);
        // A real status, so the resume claim can roll itself back when its check fails.
        transactions = mock(PlatformTransactionManager.class);
        lenient().when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        runner = new AgentRunner(
                runs,
                steps,
                agents,
                versions,
                mock(ToolGrants.class),
                router,
                tools,
                policies,
                approvals,
                mock(ToolCredentialResolver.class),
                audit,
                usage,
                progress,
                voiceClips,
                questions,
                new AskPersonTool(new ObjectMapper()),
                announcer,
                transactions);

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
        assertThat(first.getDetail()).containsEntry("type", "instruction").containsEntry("content", INSTRUCTION);
        assertThat(saved.getAllValues()).extracting(RunStep::getKind).containsExactly("note", "model_call");
    }

    @Test
    @DisplayName("a resumed run rebuilds its conversation with the original instruction after the system prompt")
    void resumeRebuildsInstruction() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "gmail.send_message");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(
                        RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                        RunStep.of(
                                ORG, runId, 1, "model_call", Map.of("content", "I have drafted it and will send it.")),
                        RunStep.of(
                                ORG,
                                runId,
                                2,
                                "approval",
                                Map.of("tool", "gmail.send_message", "approvalId", approvalId.toString()))));
        when(steps.highestPosition(runId)).thenReturn(2);
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent the welcome email.", Duration.ZERO)));

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
        assertThat(messages.get(3).content()).contains("its result follows");
        assertThat(outcome.status()).isEqualTo("completed");
        verify(progress).onRunResumed(run);
        verify(progress).onRunFinished(run, "completed", "Sent.", null);
    }

    @Test
    @DisplayName(
            "a resumed run keeps the assistant's original tool call, so it knows the call it made rather than repeating it")
    void resumeRebuildsToolCallNotJustApproval() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "call_1");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(
                        RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                        RunStep.of(
                                ORG,
                                runId,
                                1,
                                "model_call",
                                Map.of(
                                        "content",
                                        "",
                                        "toolCallRecords",
                                        List.of(Map.of(
                                                "id", "call_1",
                                                "name", "gmail.send_message",
                                                "argumentsJson", "{\"to\":\"priya@example.com\"}")))),
                        RunStep.of(
                                ORG,
                                runId,
                                2,
                                "approval",
                                Map.of(
                                        "tool",
                                        "gmail.send_message",
                                        "toolCallId",
                                        "call_1",
                                        "approvalId",
                                        approvalId.toString()))));
        when(steps.highestPosition(runId)).thenReturn(2);
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent the welcome email.", Duration.ZERO)));

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
        assertThat(toolReply.content()).contains("its result follows");
    }

    @Test
    @DisplayName("a successful voice note tool call is handed to the voice clip service, and its clip id is recorded")
    void voiceNoteCapturesAClip() {
        UUID clipId = UUID.randomUUID();
        when(voiceClips.afterVoiceNote(any(), eq(agent), eq("{\"text\":\"Hello there.\"}")))
                .thenReturn(Optional.of(clipId));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_1", "voice.create_voice_note", "{\"text\":\"Hello there.\"}"),
                        answer("Noted."));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded(
                        "{\"ok\":true}", "Saved a voice note script (11 characters).", Duration.ZERO)));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("clipId"))
                .containsExactly(clipId.toString());
    }

    @Test
    @DisplayName("a voice note made without a stored ElevenLabs key says so plainly, but the tool call still succeeds")
    void voiceNoteWithoutKeyNotesItPlainly() {
        // voiceClips defaults (set up in @BeforeEach) already answer no clip and no key.
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_1", "voice.create_voice_note", "{\"text\":\"Hello there.\"}"),
                        answer("Noted."));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded(
                        "{\"ok\":true}", "Saved a voice note script (11 characters).", Duration.ZERO)));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly("Saved a voice note script (11 characters)."
                        + " No ElevenLabs key is stored, so no audio was created.");
    }

    @Test
    @DisplayName("resume invokes the approved call exactly once, and its result reaches the conversation")
    void resumeInvokesApprovedCallOnce() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = UUID.randomUUID();
        List<RunStep> trace = new java.util.ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message"))));
        stubStepsBackedBy(runId, trace);
        Approval approval = new Approval();
        approval.setId(approvalId);
        approval.setOrgId(ORG);
        approval.setRunId(runId);
        approval.setAgentId(agent.getId());
        approval.setTool("gmail.send_message");
        approval.setToolCallId("call_1");
        approval.setStatus("approved");
        approval.setPayload("{\"to\":\"priya@example.com\"}");
        when(approvals.find(ORG, approvalId)).thenReturn(Optional.of(approval));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent the welcome email.", Duration.ZERO)));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        verify(tools, times(1)).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly("Sent the welcome email.");
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .containsExactly("Sent the welcome email.");
    }

    @Test
    @DisplayName("resuming the same run again does not invoke the already-answered call a second time")
    void resumeDoesNotReinvokeAnAlreadyAnsweredCall() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = UUID.randomUUID();
        List<RunStep> trace = new java.util.ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message")),
                RunStep.of(
                        ORG,
                        runId,
                        2,
                        "tool_call",
                        Map.of(
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message",
                                "status",
                                "SUCCEEDED",
                                "summary",
                                "Sent the welcome email."))));
        stubStepsBackedBy(runId, trace);
        Approval approval = new Approval();
        approval.setId(approvalId);
        approval.setOrgId(ORG);
        approval.setRunId(runId);
        approval.setAgentId(agent.getId());
        approval.setTool("gmail.send_message");
        approval.setToolCallId("call_1");
        approval.setStatus("approved");
        when(approvals.find(ORG, approvalId)).thenReturn(Optional.of(approval));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("a rejected approval is never invoked, whatever a caller asks resume to do")
    void resumeNeverInvokesRejectedApproval() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = UUID.randomUUID();
        List<RunStep> trace = new java.util.ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message"))));
        stubStepsBackedBy(runId, trace);
        Approval approval = new Approval();
        approval.setId(approvalId);
        approval.setOrgId(ORG);
        approval.setRunId(runId);
        approval.setAgentId(agent.getId());
        approval.setTool("gmail.send_message");
        approval.setToolCallId("call_1");
        approval.setStatus("rejected");
        when(approvals.find(ORG, approvalId)).thenReturn(Optional.of(approval));
        when(router.route(any(), any(), any())).thenReturn(answer("Stopped."));

        runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
    }

    /** Makes the mocked step repository behave like real persistence for one run's trace. */
    private void stubStepsBackedBy(UUID runId, List<RunStep> trace) {
        when(steps.findByRunIdOrderByPosition(runId)).thenAnswer(call -> List.copyOf(trace));
        when(steps.highestPosition(runId))
                .thenAnswer(call ->
                        trace.stream().mapToInt(RunStep::getPosition).max().orElse(-1));
        when(steps.save(any())).thenAnswer(call -> {
            RunStep step = call.getArgument(0);
            trace.add(step);
            return step;
        });
    }

    @Test
    @DisplayName(
            "each model step is charged what the router recorded since the last one, and the run carries the total")
    void costRecorded() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setTotalCost(new BigDecimal("0.0010"));
        UUID approvalId = approved(runId, "gmail.send_message", "call_1");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(RunStep.of(
                        ORG,
                        runId,
                        0,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message"))));
        when(steps.highestPosition(runId)).thenReturn(2);
        when(usage.costForRun(runId)).thenReturn(new BigDecimal("0.0042"));
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent.", Duration.ZERO)));

        runner.resume(ORG, runId);

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        RunStep modelStep = saved.getAllValues().stream()
                .filter(step -> "model_call".equals(step.getKind()))
                .findFirst()
                .orElseThrow();
        assertThat(modelStep.getCost()).isEqualByComparingTo("0.0032");
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

        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
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

        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
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

        assertThatThrownBy(() -> runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CONFLICT);
        verifyNoInteractions(runs, steps);
    }

    @Test
    @DisplayName(
            "a provider that answers with no content and no recognised finish reason fails the run rather than completing it blank")
    void blankAnswerFailsRatherThanCompletes() {
        when(steps.highestPosition(any())).thenReturn(-1, 0);
        when(router.route(any(), any(), any()))
                .thenReturn(new ChatResponse(
                        "",
                        List.of(),
                        FinishReason.UNKNOWN,
                        TokenUsage.of(120, 0),
                        "openrouter",
                        "some-model",
                        Duration.ofMillis(40),
                        List.of(),
                        Map.of()));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
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

    // ---- Asking a person ---------------------------------------------------------------------

    private static final String ASK_ARGS = "{\"questions\":[{\"header\":\"Audience\",\"question\":\"Who is this for?\","
            + "\"options\":[{\"label\":\"The whole team\",\"description\":\"Plain language.\"},"
            + "{\"label\":\"Managers\",\"description\":\"Decisions first.\"}]}]}";

    @Test
    @DisplayName("the ask tool parks the run waiting for input, with the question raised and recorded in the trace")
    void askParksRunAndRaisesQuestion() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("call_0", AskPersonTool.NAME, ASK_ARGS));
        RunQuestion question = question("t0_0_call_0", "pending");
        when(questions.raise(any(), eq(agent), eq("t0_0_call_0"), any())).thenReturn(question);
        when(questions.askStepDetail(question))
                .thenReturn(Map.of("questionId", question.getId().toString()));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        assertThat(outcome.status()).isEqualTo("waiting_input");
        assertThat(outcome.isWaiting()).isTrue();
        assertThat(outcome.isWaitingForInput()).isTrue();
        ArgumentCaptor<AskPersonTool.Ask> ask = ArgumentCaptor.forClass(AskPersonTool.Ask.class);
        verify(questions).raise(any(), eq(agent), eq("t0_0_call_0"), ask.capture());
        assertThat(ask.getValue().questions())
                .extracting(AskPersonTool.Question::header)
                .containsExactly("Audience");
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues()).extracting(RunStep::getKind).containsExactly("note", "model_call", "question");
        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        Run run = saved.getValue();
        assertThat(run.getStatus()).isEqualTo("waiting_input");
        verify(progress).onRunParked(run);
        verify(announcer).questionAsked(question.getId());
        verify(progress, never()).onRunFinished(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an ask the tool cannot read is returned to the model as a failure, and the run carries on")
    void invalidAskReturnsFailureToModelAndContinues() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", AskPersonTool.NAME, "{\"questions\":[]}"),
                        answer("I used the whole team as the audience."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(questions, never()).raise(any(), any(), any(), any());
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getDetail())
                            .containsEntry("status", "FAILED")
                            .containsEntry("summary", "Ask between one and four questions.");
                    assertThat((String) step.getDetail().get("modelContent"))
                            .contains("Ask between one and four questions.");
                });
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .singleElement()
                .asString()
                .contains("Ask between one and four questions.");
    }

    @Test
    @DisplayName("an ask the run may not make is refused with the policy's reason, and nothing is raised")
    void askRefusedWhenPolicyDisallows() {
        when(questions.policyFor(any()))
                .thenReturn(new QuestionService.AskPolicy(false, true, QuestionService.UNANSWERED_REASON));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", AskPersonTool.NAME, ASK_ARGS), answer("Finished with what I had."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(questions, never()).raise(any(), any(), any(), any());
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly(QuestionService.UNANSWERED_REASON);
    }

    @Test
    @DisplayName("calls after the one that parks the run are recorded as not run, and never reach the gateway")
    void callsAfterParkingCallAreRecordedAsNotRun() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(new ChatResponse(
                        "",
                        List.of(
                                new ToolCall("call_0", AskPersonTool.NAME, ASK_ARGS),
                                new ToolCall("call_1", "gmail.draft_message", "{}")),
                        FinishReason.TOOL_CALLS,
                        TokenUsage.of(120, 30),
                        "sandbox",
                        "sandbox-echo",
                        Duration.ofMillis(40),
                        List.of(),
                        Map.of()));
        RunQuestion question = question("t0_0_call_0", "pending");
        when(questions.raise(any(), any(), any(), any())).thenReturn(question);
        when(questions.askStepDetail(question)).thenReturn(Map.of());

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, never()).evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getDetail())
                            .containsEntry("toolCallId", "t0_1_call_1")
                            .containsEntry("status", "BLOCKED")
                            .containsEntry(
                                    "summary",
                                    "Not run: the run paused for a person. Call it again if it is still needed.");
                    assertThat(step.getDetail().get("modelContent")).isNotNull();
                });
    }

    @Test
    @DisplayName(
            "an answer is delivered once as the ask call's result, and a second resume that loses the claim writes nothing")
    void resumeAfterAnswerWritesToolResultOnceAndDrives() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("waiting_input");
        List<RunStep> trace = new ArrayList<>(askTrace(runId, "t0_0_call_0"));
        stubStepsBackedBy(runId, trace);
        RunQuestion question = question("t0_0_call_0", "answered");
        when(questions.latestForRun(runId)).thenReturn(Optional.of(question));
        when(questions.resultSummary(question)).thenReturn("Answered: Managers");
        when(questions.modelResult(question)).thenReturn("{\"status\":\"answered\"}");
        when(runs.claimParked(eq(runId), eq("waiting_input"), any(), any(), any()))
                .thenAnswer(call -> {
                    run.setStatus("running");
                    return 1;
                })
                .thenReturn(0);
        when(router.route(any(), any(), any())).thenReturn(answer("Written for managers."));

        AgentRunner.Outcome first = runner.resume(ORG, runId);
        run.setStatus("waiting_input");
        AgentRunner.Outcome second = runner.resume(ORG, runId);

        assertThat(first.status()).isEqualTo("completed");
        assertThat(second.status()).isEqualTo("waiting_input");
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("toolCallId", "t0_0_call_0")
                        .containsEntry("tool", AskPersonTool.NAME)
                        .containsEntry("summary", "Answered: Managers")
                        .containsEntry("modelContent", "{\"status\":\"answered\"}")
                        .containsEntry("questionId", question.getId().toString()));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(1)).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages.get(2).toolCalls()).extracting(ToolCall::id).containsExactly("t0_0_call_0");
        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(messages.get(3).toolCallId()).isEqualTo("t0_0_call_0");
        assertThat(messages.get(3).content()).isEqualTo("{\"status\":\"answered\"}");
        verify(progress, times(1)).onRunResumed(any());
    }

    @Test
    @DisplayName("a resume that loses the claim returns where the run stands, and drives nothing")
    void resumeAfterAnswerLosesClaimReturnsCurrentStatus() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("waiting_input");
        when(runs.claimParked(eq(runId), eq("waiting_input"), any(), any(), any()))
                .thenAnswer(call -> {
                    run.setStatus("running"); // another resume won the claim
                    return 0;
                });

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        assertThat(outcome.status()).isEqualTo("running");
        verify(steps, never()).save(any());
        verify(router, never()).route(any(), any(), any());
        verify(questions, never()).latestForRun(any());
    }

    @Test
    @DisplayName("an approved call is never made by a resume that did not win the claim")
    void resumeApprovalClaimsBeforeExecuting() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "call_1");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(RunStep.of(
                        ORG,
                        runId,
                        0,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_1",
                                "tool",
                                "gmail.send_message"))));
        when(runs.claimParked(eq(runId), eq("waiting_approval"), any(), any(), any()))
                .thenReturn(0);

        runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName(
            "a run parked on a newer approval still pending is never driven, even though an earlier one was granted")
    void resumeApprovalDoesNotDriveWhenNewestApprovalIsPending() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID earlier = approved(runId, "gmail.send_message", "t0_0_call_0");
        Approval pending = new Approval();
        pending.setId(UUID.randomUUID());
        pending.setOrgId(ORG);
        pending.setRunId(runId);
        pending.setTool("gmail.send_message");
        pending.setStatus("pending");
        when(approvals.find(ORG, pending.getId())).thenReturn(Optional.of(pending));
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(
                        RunStep.of(
                                ORG,
                                runId,
                                0,
                                "approval",
                                Map.of(
                                        "approvalId",
                                        earlier.toString(),
                                        "toolCallId",
                                        "t0_0_call_0",
                                        "tool",
                                        "gmail.send_message")),
                        RunStep.of(
                                ORG,
                                runId,
                                1,
                                "tool_call",
                                Map.of(
                                        "toolCallId",
                                        "t0_0_call_0",
                                        "tool",
                                        "gmail.send_message",
                                        "status",
                                        "SUCCEEDED",
                                        "summary",
                                        "Sent.")),
                        RunStep.of(
                                ORG,
                                runId,
                                2,
                                "approval",
                                Map.of(
                                        "approvalId",
                                        pending.getId().toString(),
                                        "toolCallId",
                                        "t1_0_call_0",
                                        "tool",
                                        "gmail.send_message"))));

        runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
        verify(router, never()).route(any(), any(), any());
        verify(progress, never()).onRunResumed(any());
    }

    @Test
    @DisplayName("a run whose newest question is still pending is never driven")
    void resumeAnswerDoesNotDriveWhenNewestQuestionIsPending() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("waiting_input");
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(askTrace(runId, "t2_0_call_0"));
        when(questions.latestForRun(runId)).thenReturn(Optional.of(question("t2_0_call_0", "pending")));

        runner.resume(ORG, runId);

        verify(steps, never()).save(any());
        verify(router, never()).route(any(), any(), any());
        verify(progress, never()).onRunResumed(any());
    }

    @Test
    @DisplayName("an answer already delivered is never delivered again")
    void resumeAnswerDoesNotRedeliverAnAnsweredQuestion() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("waiting_input");
        List<RunStep> trace = new ArrayList<>(askTrace(runId, "t0_0_call_0"));
        trace.add(RunStep.of(
                ORG,
                runId,
                3,
                "tool_call",
                Map.of(
                        "toolCallId",
                        "t0_0_call_0",
                        "tool",
                        AskPersonTool.NAME,
                        "status",
                        "SUCCEEDED",
                        "summary",
                        "Answered: Managers")));
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(trace);
        when(questions.latestForRun(runId)).thenReturn(Optional.of(question("t0_0_call_0", "answered")));

        runner.resume(ORG, runId);

        verify(steps, never()).save(any());
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName("two turns whose provider numbers calls from zero get distinct ids, and the ask gets its own")
    void callIdsAreUniqueWithinRun() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"id\":\"d1\"}", "Drafted.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", "gmail.draft_message", "{}"),
                        toolCallAnswer("call_0", AskPersonTool.NAME, ASK_ARGS));
        RunQuestion question = question("t1_0_call_0", "pending");
        when(questions.raise(any(), any(), any(), any())).thenReturn(question);
        when(questions.askStepDetail(question)).thenReturn(Map.of());

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("toolCallId"))
                .containsExactly("t0_0_call_0");
        verify(questions).raise(any(), any(), eq("t1_0_call_0"), any());

        assertThat(AgentRunner.uniqueCallIds(3, List.of(new ToolCall("call.0/x", "a.b", "{}"))))
                .extracting(ToolCall::id)
                .containsExactly("t3_0_call0x");
        assertThat(AgentRunner.uniqueCallIds(12, List.of(new ToolCall("x".repeat(80), "a.b", "{}")))
                        .getFirst()
                        .id())
                .hasSize(40)
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("the rewritten ids are what the trace, the conversation and the approval all carry")
    @SuppressWarnings("unchecked")
    void rewrittenIdsReachTraceConversationAndApproval() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(
                        ApprovalDecision.PROCEED,
                        new ApprovalDecision.AwaitApproval("Send the welcome email", "approval:decide"));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"id\":\"d1\"}", "Drafted.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", "gmail.draft_message", "{}"),
                        toolCallAnswer("call_0", "gmail.send_message", "{}"));
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any())).thenReturn(approval);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("waiting_approval");
        ArgumentCaptor<ToolCall> raisedFor = ArgumentCaptor.forClass(ToolCall.class);
        verify(approvals).raise(any(), any(), any(), any(), raisedFor.capture());
        assertThat(raisedFor.getValue().id()).isEqualTo("t1_0_call_0");
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "approval".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("toolCallId"))
                .containsExactly("t1_0_call_0");
        assertThat(trace.getAllValues())
                .filteredOn(step -> "model_call".equals(step.getKind()))
                .extracting(
                        step -> ((List<Map<String, Object>>) step.getDetail().get("toolCallRecords"))
                                .getFirst()
                                .get("id"))
                .containsExactly("t0_0_call_0", "t1_0_call_0");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        List<ChatMessage> second = requests.getAllValues().get(1).messages();
        assertThat(second)
                .filteredOn(ChatMessage::hasToolCalls)
                .flatExtracting(ChatMessage::toolCalls)
                .extracting(ToolCall::id)
                .containsExactly("t0_0_call_0");
        assertThat(second)
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::toolCallId)
                .containsExactly("t0_0_call_0");
    }

    @Test
    @DisplayName("results are rebuilt straight after the turn that made the calls, in call order")
    void rebuildEmitsResultsInCallOrder() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "t0_0_a");
        List<RunStep> trace = new ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "model_call",
                        Map.of(
                                "content",
                                "",
                                "toolCallRecords",
                                List.of(
                                        Map.of("id", "t0_0_a", "name", "gmail.send_message", "argumentsJson", "{}"),
                                        Map.of(
                                                "id",
                                                "t0_1_b",
                                                "name",
                                                "calendar.list_events",
                                                "argumentsJson",
                                                "{}")))),
                RunStep.of(
                        ORG,
                        runId,
                        2,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "t0_0_a",
                                "tool",
                                "gmail.send_message")),
                RunStep.of(
                        ORG,
                        runId,
                        3,
                        "tool_call",
                        Map.of(
                                "toolCallId",
                                "t0_1_b",
                                "tool",
                                "calendar.list_events",
                                "status",
                                "BLOCKED",
                                "summary",
                                "Not run.",
                                "modelContent",
                                "{\"error\":\"Not run.\"}"))));
        stubStepsBackedBy(runId, trace);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent the welcome email.", Duration.ZERO)));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.resume(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages)
                .extracting(ChatMessage::role)
                .containsExactly(
                        ChatMessage.Role.SYSTEM,
                        ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.TOOL,
                        ChatMessage.Role.TOOL);
        assertThat(messages.get(3).toolCallId()).isEqualTo("t0_0_a");
        assertThat(messages.get(3).content()).isEqualTo("Sent the welcome email.");
        assertThat(messages.get(4).toolCallId()).isEqualTo("t0_1_b");
        assertThat(messages.get(4).content()).isEqualTo("{\"error\":\"Not run.\"}");
    }

    @Test
    @DisplayName("a legacy trace that repeats call_0 in every turn attributes each result to the nearest turn")
    void rebuildAttributesRepeatedLegacyIdsToNearestTurn() {
        UUID runId = UUID.randomUUID();
        parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "call_0");
        List<RunStep> trace = new ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "model_call",
                        Map.of(
                                "content",
                                "",
                                "toolCallRecords",
                                List.of(Map.of("id", "call_0", "name", "gmail.draft_message", "argumentsJson", "{}")))),
                RunStep.of(
                        ORG,
                        runId,
                        2,
                        "tool_call",
                        Map.of(
                                "toolCallId",
                                "call_0",
                                "tool",
                                "gmail.draft_message",
                                "status",
                                "SUCCEEDED",
                                "summary",
                                "Drafted.",
                                "modelContent",
                                "{\"draft\":1}")),
                RunStep.of(
                        ORG,
                        runId,
                        3,
                        "model_call",
                        Map.of(
                                "content",
                                "",
                                "toolCallRecords",
                                List.of(Map.of("id", "call_0", "name", "gmail.send_message", "argumentsJson", "{}")))),
                RunStep.of(
                        ORG,
                        runId,
                        4,
                        "approval",
                        Map.of(
                                "approvalId",
                                approvalId.toString(),
                                "toolCallId",
                                "call_0",
                                "tool",
                                "gmail.send_message"))));
        stubStepsBackedBy(runId, trace);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Sent.", Duration.ZERO)));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.resume(ORG, runId);

        verify(tools, times(1)).invoke(any(), any(), any());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages)
                .extracting(ChatMessage::role)
                .containsExactly(
                        ChatMessage.Role.SYSTEM, ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT, ChatMessage.Role.TOOL,
                        ChatMessage.Role.ASSISTANT, ChatMessage.Role.TOOL);
        assertThat(messages.get(3).content()).isEqualTo("{\"draft\":1}");
        assertThat(messages.get(5).content()).isEqualTo("Sent.");
    }

    @Test
    @DisplayName("a rebuilt result is what the model was told at the time, not only the one-line summary")
    void rebuildUsesModelContent() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("waiting_input");
        List<RunStep> trace = new ArrayList<>(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "model_call",
                        Map.of(
                                "content",
                                "",
                                "toolCallRecords",
                                List.of(Map.of(
                                        "id", "t0_0_call_0", "name", "crm.find_contact", "argumentsJson", "{}")))),
                RunStep.of(
                        ORG,
                        runId,
                        2,
                        "tool_call",
                        Map.of(
                                "toolCallId",
                                "t0_0_call_0",
                                "tool",
                                "crm.find_contact",
                                "status",
                                "SUCCEEDED",
                                "summary",
                                "Found one contact.",
                                "modelContent",
                                "{\"name\":\"Priya\"}"))));
        trace.addAll(askTrace(runId, "t1_0_call_0").subList(1, 3));
        stubStepsBackedBy(runId, trace);
        RunQuestion question = question("t1_0_call_0", "answered");
        when(questions.latestForRun(runId)).thenReturn(Optional.of(question));
        when(questions.resultSummary(question)).thenReturn("Answered: Managers");
        when(questions.modelResult(question)).thenReturn("{\"status\":\"answered\"}");
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.resume(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .containsExactly("{\"name\":\"Priya\"}", "{\"status\":\"answered\"}");
    }

    @Test
    @DisplayName("with no other tool, the ask tool is offered without insisting on a model that calls tools")
    void askToolOfferedWithoutRequiringToolSupportWhenOnlyTool() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().tools()).extracting(ToolSpec::name).containsExactly(AskPersonTool.NAME);
        assertThat(request.getValue().requireToolSupport()).isFalse();
    }

    @Test
    @DisplayName("the system prompt describes the ask tool only when the run may ask, and says otherwise when not")
    void systemPromptMentionsAskToolOnlyWhenAllowed() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));
        when(questions.policyFor(any()))
                .thenReturn(
                        new QuestionService.AskPolicy(true, false, null),
                        new QuestionService.AskPolicy(false, false, QuestionService.SCHEDULED_REASON));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");
        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        ChatRequest allowed = requests.getAllValues().get(0);
        ChatRequest refused = requests.getAllValues().get(1);
        assertThat(allowed.systemPrompt())
                .contains("person__ask_question")
                .doesNotContain("No person can answer questions during this run");
        assertThat(refused.systemPrompt())
                .contains("No person can answer questions during this run")
                .doesNotContain("person__ask_question");
        assertThat(refused.tools()).isEmpty();
    }

    /** The first steps of a run that asked: its instruction, the turn that made the ask call, and the question. */
    private List<RunStep> askTrace(UUID runId, String callId) {
        return List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(
                        ORG,
                        runId,
                        1,
                        "model_call",
                        Map.of(
                                "content",
                                "",
                                "toolCallRecords",
                                List.of(Map.of("id", callId, "name", AskPersonTool.NAME, "argumentsJson", ASK_ARGS)))),
                RunStep.of(
                        ORG,
                        runId,
                        2,
                        "question",
                        Map.of(
                                "toolCallId",
                                callId,
                                "tool",
                                AskPersonTool.NAME,
                                "summary",
                                "Asked: Who is this for?")));
    }

    private RunQuestion question(String callId, String status) {
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setToolCallId(callId);
        question.setStatus(status);
        return question;
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
        // The claim is a conditional update; stubbed to change the status the way the real one
        // would, so the loop's cooperative check sees a running run.
        lenient().when(runs.claimParked(eq(runId), any(), any(), any(), any())).thenAnswer(call -> {
            if (!call.getArgument(1).equals(run.getStatus())) {
                return 0;
            }
            run.setStatus("running");
            return 1;
        });
        lenient().when(runs.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    /** An approved approval for this run's call, as the claim transaction reads it. */
    private UUID approved(UUID runId, String tool, String toolCallId) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(runId);
        approval.setAgentId(agent.getId());
        approval.setTool(tool);
        approval.setToolCallId(toolCallId);
        approval.setStatus("approved");
        approval.setPayload("{}");
        when(approvals.find(ORG, approval.getId())).thenReturn(Optional.of(approval));
        return approval.getId();
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content,
                List.of(),
                FinishReason.STOP,
                TokenUsage.of(120, 30),
                "sandbox",
                "sandbox-echo",
                Duration.ofMillis(40),
                List.of(),
                Map.of());
    }

    private static ChatResponse toolCallAnswer(String callId, String toolName, String argumentsJson) {
        return new ChatResponse(
                "",
                List.of(new ToolCall(callId, toolName, argumentsJson)),
                FinishReason.TOOL_CALLS,
                TokenUsage.of(120, 30),
                "sandbox",
                "sandbox-echo",
                Duration.ofMillis(40),
                List.of(),
                Map.of());
    }
}
