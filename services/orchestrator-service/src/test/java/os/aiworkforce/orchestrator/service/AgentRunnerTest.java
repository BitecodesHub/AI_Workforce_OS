package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.spi.McpServerAdapter;
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
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
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
    /** What the model is told in front of a result that came from the sandbox rather than a live service. */
    private static final String PRACTICE = "Practice data from the sandbox, not real. ";

    private Runs runs;
    private RunSteps steps;
    private Agents agents;
    private AgentVersions versions;
    private ModelRouter router;
    private ProviderRegistry registry;
    private ToolCredentialResolver credentials;
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
    private KnowledgeSearchTool knowledge;
    private os.aiworkforce.platform.observability.Redactor redactor;
    private os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup zones;

    private Agent agent;
    private AgentVersion version;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        agents = mock(Agents.class);
        versions = mock(AgentVersions.class);
        router = mock(ModelRouter.class);
        registry = mock(ProviderRegistry.class);
        // A server nobody has connected: the call goes ahead against practice data. Tests of the
        // other two answers say so themselves.
        credentials = mock(ToolCredentialResolver.class);
        lenient().when(credentials.resolve(any(), any())).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
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
        knowledge = mock(KnowledgeSearchTool.class);
        redactor = mock(os.aiworkforce.platform.observability.Redactor.class);
        lenient().when(redactor.text(any())).thenAnswer(call -> call.getArgument(0));
        zones = mock(os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup.class);
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
                registry,
                tools,
                policies,
                approvals,
                credentials,
                audit,
                usage,
                progress,
                voiceClips,
                questions,
                new AskPersonTool(new ObjectMapper()),
                announcer,
                knowledge,
                mock(os.aiworkforce.orchestrator.repository.Tasks.class),
                mock(os.aiworkforce.orchestrator.repository.Goals.class),
                mock(os.aiworkforce.orchestrator.schedule.Schedules.class),
                zones,
                redactor,
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
        when(usage.costForRun(any(), any())).thenReturn(BigDecimal.ZERO);
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
    @DisplayName("a send whose arguments do not fit the tool is answered as a failure the model can fix, not parked for approval")
    void invalidArgumentsAreNotParked() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_1", "gmail.send_message", "{\"to\":\"priya@example.com\"}"),
                        answer("I need the subject and body first."));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new os.aiworkforce.mcp.policy.ApprovalDecision.Invalid("subject is required"));
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.failed("The arguments do not match the tool's schema: subject is required")));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(approvals, never()).raise(any(), any(), any(), any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).noneMatch(step -> "approval".equals(step.getKind()));
        assertThat(saved.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("status"), step -> step.getDetail().get("summary"))
                .containsExactly(tuple(
                        "FAILED", "The arguments do not match the tool's schema: subject is required"));
    }

    // ---- Through the real gateway, one connector at a time ------------------------------------

    /*
     * For each representative connector: a read, a write where it has one, and a send or delete
     * where it has one - as a model would ask for them - against the real gateway and practice
     * data. The read and write run straight away; the send parks the run for approval; approving
     * makes the call exactly once, however many times the run is resumed.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', textBlock = """
            gmail      | list_messages      | {}                                            | draft_message       | {"to":"priya@example.com","subject":"Welcome","body":"Hello"} | send_message   | {"to":"priya@example.com","subject":"Welcome","body":"Hello"}
            slack      | list_channels      | {}                                            |                     |                                                         | post_message   | {"channel":"C01GENERAL","text":"Release is out"}
            github     | list_issues        | {"repo":"acme/website","state":"open"}        | create_issue        | {"repo":"acme/website","title":"Footer overlaps"}        |                |
            jira       | search_issues      | {"jql":"project = OPS","limit":"5"}           | create_issue        | {"project":"OPS","summary":"Printer jammed"}            |                |
            hubspot    | search_contacts    | {"query":"Riverside"}                         | create_contact      | {"email":"dana.wu@example.com","firstName":"Dana"}      |                |
            stripe     | list_payments      | {"customer":"cus_H4rbour"}                    |                     |                                                         | refund_payment | {"id":"pi_1002"}
            webhook    | list_events        | {}                                            |                     |                                                         | send_event     | {"event":"order.shipped","data":{"order":"SO-7"}}
            calendar   | list_events        | {}                                            | create_event        | {"title":"Review","start":"2026-10-20T10:00:00+10:00","end":"2026-10-20T11:00:00+10:00","attendees":"sam@example.com"} | delete_event | {"id":"evt_2001"}
            drive      | list_files         | {}                                            | create_file         | {"name":"Notes","content":"Agenda"}                    |                |
            notion     | search_pages       | {"query":"handbook"}                          | create_page         | {"parentId":"page_1401","title":"Rota"}                | archive_page   | {"id":"page_1402"}
            zendesk    | list_tickets       | {"status":"open"}                             | add_note            | {"ticketId":"9001","body":"Checked the logs"}           | send_reply     | {"ticketId":"9001","body":"We have reset it"}
            salesforce | list_opportunities | {"limit":"2"}                                 | update_opportunity  | {"id":"006B0001","stage":"Negotiation"}                |                |
            """)
    @DisplayName("each representative connector reads, writes, parks a send for approval and makes it once")
    void connectorsThroughTheRealGateway(
            String server, String read, String readArgs, String write, String writeArgs, String gated, String gatedArgs) {
        ObjectMapper json = new ObjectMapper();
        ToolGateway gateway = new ToolGateway(
                os.aiworkforce.mcp.sandbox.SandboxServerRegistry.servers(
                        json, org.springframework.web.reactive.function.client.WebClient.builder(), true),
                new os.aiworkforce.mcp.policy.ArgumentValidator(json),
                new os.aiworkforce.platform.resilience.ResiliencePresets(
                        mock(os.aiworkforce.platform.config.PlatformProperties.class)));
        ToolGrants grants = mock(ToolGrants.class);
        AgentToolGrant grant = new AgentToolGrant();
        grant.setAgentId(agent.getId());
        grant.setServer(server);
        grant.setAllowedTools(List.of());
        grant.setScopes(gateway.adapter(server).orElseThrow().allScopes());
        grant.setEnabled(true);
        when(grants.findByAgentIdAndEnabledTrue(agent.getId())).thenReturn(List.of(grant));
        AgentRunner real = runnerWith(gateway, grants);
        version.setMaxSteps(8);

        List<RunStep> trace = new java.util.ArrayList<>();
        when(steps.save(any())).thenAnswer(call -> {
            RunStep step = call.getArgument(0);
            trace.add(step);
            return step;
        });
        when(steps.findByRunIdOrderByPosition(any())).thenAnswer(call -> List.copyOf(trace));
        when(steps.highestPosition(any()))
                .thenAnswer(call -> trace.stream().mapToInt(RunStep::getPosition).max().orElse(-1));
        List<ChatResponse> turns = new java.util.ArrayList<>();
        turns.add(toolCallAnswer("call_1", server + "." + read, readArgs));
        if (write != null) {
            turns.add(toolCallAnswer("call_2", server + "." + write, writeArgs));
        }
        if (gated != null) {
            turns.add(toolCallAnswer("call_3", server + "." + gated, gatedArgs));
        }
        turns.add(answer("Done."));
        when(router.route(any(), any(), any()))
                .thenReturn(turns.get(0), turns.subList(1, turns.size()).toArray(new ChatResponse[0]));
        Approval pending = new Approval();
        pending.setId(UUID.randomUUID());
        pending.setOrgId(ORG);
        pending.setAgentId(agent.getId());
        pending.setStatus("pending");
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            os.aiworkforce.mcp.model.ToolInvocation invocation = call.getArgument(2);
            pending.setRunId(UUID.fromString(invocation.runId()));
            pending.setTool(invocation.qualifiedName());
            pending.setToolCallId(((ToolCall) call.getArgument(4)).id());
            pending.setPayload(invocation.argumentsJson());
            return pending;
        });

        AgentRunner.Outcome started = real.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        List<Map<String, Object>> calls = trace.stream()
                .filter(step -> "tool_call".equals(step.getKind()))
                .map(RunStep::getDetail)
                .toList();
        assertThat(calls).extracting(detail -> detail.get("tool"), detail -> detail.get("status"), detail -> detail.get("mode"))
                .as("the read and write ran against practice data")
                .startsWith(tuple(server + "." + read, "SUCCEEDED", "sandbox"));
        if (write != null) {
            assertThat(calls.get(1)).containsEntry("tool", server + "." + write).containsEntry("status", "SUCCEEDED")
                    .containsEntry("sideEffect", "WRITE");
        }
        if (gated == null) {
            assertThat(started.status()).isEqualTo("completed");
            verify(approvals, never()).raise(any(), any(), any(), any(), any(), any());
            return;
        }
        assertThat(started.status()).isEqualTo("waiting_approval");
        assertThat(calls).noneMatch(detail -> (server + "." + gated).equals(detail.get("tool")));
        assertThat(trace).filteredOn(step -> "approval".equals(step.getKind())).hasSize(1);

        // A person approves and the run is resumed.
        parkedRun(started.runId());
        pending.setStatus("approved");
        when(approvals.find(ORG, pending.getId())).thenReturn(Optional.of(pending));
        when(router.route(any(), any(), any())).thenReturn(answer("Sent."));

        AgentRunner.Outcome resumed = real.resume(ORG, started.runId());
        assertThat(resumed.status()).isEqualTo("completed");
        // A second resume - a double click, or the sweep arriving late - is refused plainly and makes nothing.
        assertThatThrownBy(() -> real.resume(ORG, started.runId())).hasMessage("That run is not waiting for a person.");

        List<Map<String, Object>> gatedCalls = trace.stream()
                .filter(step -> "tool_call".equals(step.getKind()))
                .map(RunStep::getDetail)
                .filter(detail -> (server + "." + gated).equals(detail.get("tool")))
                .toList();
        assertThat(gatedCalls).as("the approved call is made exactly once").singleElement().satisfies(detail -> {
            assertThat(detail).containsEntry("status", "SUCCEEDED").containsEntry("mode", "sandbox");
            assertThat((String) detail.get("summary")).isNotBlank().doesNotContain("Exception");
        });
    }

    private AgentRunner runnerWith(ToolGateway gateway, ToolGrants grants) {
        return new AgentRunner(
                runs,
                steps,
                agents,
                versions,
                grants,
                router,
                registry,
                gateway,
                policies,
                approvals,
                credentials,
                audit,
                usage,
                progress,
                voiceClips,
                questions,
                new AskPersonTool(new ObjectMapper()),
                announcer,
                knowledge,
                mock(os.aiworkforce.orchestrator.repository.Tasks.class),
                mock(os.aiworkforce.orchestrator.repository.Goals.class),
                mock(os.aiworkforce.orchestrator.schedule.Schedules.class),
                zones,
                redactor,
                transactions);
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
                .extracting(step -> step.getDetail().get("summary"), step -> step.getDetail().get("modelContent"))
                .containsExactly(tuple("Sent the welcome email.", PRACTICE + "{\"ok\":true}"));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        // What the tool returned, not only the one-line summary, so the model knows what it made.
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .containsExactly(PRACTICE + "{\"ok\":true}");
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
    @DisplayName("an approval sent back with feedback resumes the run with the note as the call's result, and makes no call")
    void resumeAfterSentBackCarriesTheNote() {
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
        approval.setSentBack(true);
        approval.setDecisionNote("Use a softer tone.");
        when(approvals.find(ORG, approvalId)).thenReturn(Optional.of(approval));
        when(router.route(any(), any(), any())).thenReturn(answer("I have softened it."));

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getDetail().get("status")).isEqualTo("REJECTED");
                    assertThat(String.valueOf(step.getDetail().get("modelContent")))
                            .contains("Use a softer tone.")
                            .contains("Do not repeat this action unchanged");
                });
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .anyMatch(content -> content.contains("Use a softer tone."));
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
        when(usage.costForRun(ORG, runId)).thenReturn(new BigDecimal("0.0042"));
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
        when(usage.costForRun(any(), any())).thenReturn(new BigDecimal("0.0007"));
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
    @DisplayName("the reaper fails an abandoned run's task through the same rules, and says why on the trace")
    void reaperReportsToTask() {
        Run run = parkedRun(UUID.randomUUID());
        run.setStatus("running");
        when(runs.findAbandoned(any(), any())).thenReturn(List.of(run));
        when(runs.markAbandoned(eq(run.getId()), any(), any(), any())).thenReturn(1);

        assertThat(runner.reapAbandoned(50)).isEqualTo(1);

        assertThat(run.getStatus()).isEqualTo("abandoned");
        verify(progress).onRunFinished(run, "abandoned", null, "The worker running this task stopped responding.");
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps).save(trace.capture());
        assertThat(trace.getValue().getKind()).isEqualTo("error");
        assertThat(trace.getValue().getDetail())
                .containsEntry(
                        "detail",
                        "The worker running this task stopped responding (for example a service restart)."
                                + " Use Try again.");
        verify(tools).releaseRun(run.getId().toString());
    }

    @Test
    @DisplayName("the reaper records run.abandon in the audit log, as failed")
    @SuppressWarnings("unchecked")
    void reaperEmitsRunAbandon() {
        Run run = parkedRun(UUID.randomUUID());
        run.setStatus("running");
        when(runs.findAbandoned(any(), any())).thenReturn(List.of(run));
        when(runs.markAbandoned(eq(run.getId()), any(), any(), any())).thenReturn(1);

        runner.reapAbandoned(50);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        any(),
                        eq("run.abandon"),
                        eq("run"),
                        eq(run.getId().toString()),
                        eq("failed"),
                        detail.capture());
        assertThat(detail.getValue()).containsEntry("status", "abandoned");
    }

    @Test
    @DisplayName("a run whose lease was renewed after the reaper read it is left alone")
    void reaperLosesToARenewal() {
        Run run = parkedRun(UUID.randomUUID());
        run.setStatus("running");
        when(runs.findAbandoned(any(), any())).thenReturn(List.of(run));
        when(runs.markAbandoned(eq(run.getId()), any(), any(), any())).thenReturn(0);

        assertThat(runner.reapAbandoned(50)).isZero();

        assertThat(run.getStatus()).isEqualTo("running");
        verify(steps, never()).save(any());
        verify(progress, never()).onRunFinished(any(), any(), any(), any());
        verifyNoInteractions(audit);
        verify(tools, never()).releaseRun(any());
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

    private MemoryClient memoryClient;

    private void withMemory() {
        memoryClient = mock(MemoryClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                runner, "agentMemory", new AgentMemoryTool(memoryClient, new ObjectMapper()));
    }

    @Test
    @DisplayName("memory.remember keeps a note for this agent, records it in the trace and tells the model it is kept")
    void rememberKeepsANoteForThisAgent() {
        withMemory();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(memoryClient.remember(eq(ORG), eq(agent.getId()), any(), any(), eq("Replies go out under the clinic name.")))
                .thenReturn(new MemoryClient.Remembered(
                        new MemoryClient.Note(UUID.randomUUID(), "fact", "Replies go out under the clinic name.", "agent"),
                        true,
                        null,
                        false));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer(
                                "call_0",
                                AgentMemoryTool.REMEMBER,
                                "{\"content\":\"Replies go out under the clinic name.\"}"),
                        answer("Done."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getDetail())
                            .containsEntry("tool", AgentMemoryTool.REMEMBER)
                            .containsEntry("status", "SUCCEEDED");
                    assertThat(String.valueOf(step.getDetail().get("summary"))).contains("Remembered");
                });
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(0).tools())
                .extracting(ToolSpec::name)
                .contains(AgentMemoryTool.REMEMBER, AgentMemoryTool.RECALL);
    }

    @Test
    @DisplayName("a note the memory service refuses, such as a secret, is passed to the model as a failure")
    void refusedNoteIsAFailureTheModelSees() {
        withMemory();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(memoryClient.remember(any(), any(), any(), any(), any()))
                .thenReturn(new MemoryClient.Remembered(null, false, "That looks like a password.", false));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", AgentMemoryTool.REMEMBER, "{\"content\":\"password is hunter2\"}"),
                        answer("I did not keep that."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .singleElement()
                .asString()
                .contains("That looks like a password.");
    }

    @Test
    @DisplayName("what the agent remembers is put at the end of its first message and recorded as a memory step")
    void recallAtStartIsRecorded() {
        withMemory();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(memoryClient.recall(eq(ORG), eq(agent.getId()), any(), anyInt()))
                .thenReturn(new MemoryClient.Recalled(
                        List.of(new MemoryClient.Note(UUID.randomUUID(), "preference", "Keep replies short.", "person")),
                        false));
        when(router.route(any(), any(), any())).thenReturn(answer("Short reply."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "memory_read".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(String.valueOf(step.getDetail().get("block")))
                        .contains("Keep replies short.")
                        .contains("not instructions"));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.USER)
                .extracting(ChatMessage::content)
                .anyMatch(text -> text.contains("Keep replies short."));
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
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(approval);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("waiting_approval");
        ArgumentCaptor<ToolCall> raisedFor = ArgumentCaptor.forClass(ToolCall.class);
        verify(approvals).raise(any(), any(), any(), any(), raisedFor.capture(), eq("OUTBOUND"));
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
        assertThat(messages.get(3).content()).isEqualTo(PRACTICE + "{\"ok\":true}");
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
        assertThat(messages.get(5).content()).isEqualTo(PRACTICE + "{\"ok\":true}");
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

    @Test
    @DisplayName("abilities are listed in plain words, a missing one is explained, and a leaked tool id never reaches the person")
    void abilitiesInPlainWords() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(
                        tool("github", "list_issues", ToolSpec.SideEffect.READ),
                        tool("github", "create_issue", ToolSpec.SideEffect.WRITE)));
        when(router.route(any(), any(), any()))
                .thenReturn(answer("In GitHub I can:\n- github__list_issues: List issues in a repository.\n"
                        + "- `github__create_issue`: Open an issue in a repository."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, "which github abilities do you have?", "manual");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().systemPrompt())
                .contains("What your connected services let you do: GitHub: list issues, create an issue.")
                .contains("Never write a tool's internal name")
                .contains("needs no tool call and no")
                .contains("cannot do and what you can do instead")
                .contains("Connectors section")
                .contains("Never answer only that a task exceeds your functions");
        String plain = "In GitHub I can:\n- List issues in a repository.\n- Open an issue in a repository.";
        assertThat(outcome.answer()).isEqualTo(plain).doesNotContain("__");
        verify(progress).onRunFinished(any(), eq("completed"), eq(plain), any());
    }

    // ---- Lease, stop and the approved call ----------------------------------------------------

    @Test
    @DisplayName(
            "a model call that outlasts the lease is kept alive by the heartbeat while the reaper runs, and completes")
    void heartbeatOutlivesASlowModelCall() {
        runner.setLease(Duration.ofMillis(400));
        RunRow row = new RunRow();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(1_200);
            return answer("Done after a long think.");
        });
        ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
        reaper.scheduleAtFixedRate(() -> runner.reapAbandoned(50), 20, 20, TimeUnit.MILLISECONDS);

        AgentRunner.Outcome outcome;
        try {
            outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");
        } finally {
            reaper.shutdownNow();
        }

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(row.status).isEqualTo("completed");
        assertThat(row.abandoned.get()).isZero();
        assertThat(row.renewals.get()).isPositive();
        assertThat(savedKinds()).doesNotContain("error");
    }

    @Test
    @DisplayName("a stop during a model call ends the run as cancelled, with no error step and no approval raised")
    void stopDuringModelCall() {
        RunRow row = new RunRow();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.AwaitApproval("Send the welcome email", "approval:decide"));
        when(router.route(any(), any(), any())).thenAnswer(call -> {
            row.stop();
            return toolCallAnswer("call_0", "gmail.send_message", "{}");
        });

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        assertThat(outcome.status()).isEqualTo("cancelled");
        assertThat(row.status).isEqualTo("cancelled");
        assertThat(savedKinds()).doesNotContain("error", "approval");
        verify(approvals, never()).raise(any(), any(), any(), any(), any(), any());
        verify(tools, never()).invoke(any(), any(), any());
        verify(progress, never()).onRunFinished(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a stop between tool calls leaves the turn's later calls unmade, recorded as not run")
    void stopBetweenToolCalls() {
        RunRow row = new RunRow();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(router.route(any(), any(), any()))
                .thenReturn(new ChatResponse(
                        "",
                        List.of(
                                new ToolCall("call_0", "gmail.draft_message", "{}"),
                                new ToolCall("call_1", "slack.post_message", "{}"),
                                new ToolCall("call_2", "gmail.send_message", "{}")),
                        FinishReason.TOOL_CALLS,
                        TokenUsage.of(120, 30),
                        "sandbox",
                        "sandbox-echo",
                        Duration.ofMillis(40),
                        List.of(),
                        Map.of()));
        when(tools.invoke(any(), any(), any())).thenAnswer(call -> {
            row.stop();
            return Mono.just(ToolResult.succeeded("{\"id\":\"d1\"}", "Drafted.", Duration.ZERO));
        });

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("cancelled");
        verify(tools, times(1)).invoke(any(), any(), any());
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("toolCallId"), step -> step.getDetail().get("summary"))
                .containsExactly(
                        tuple("t0_0_call_0", "Drafted."),
                        tuple("t0_1_call_1", AgentRunner.STOPPED_NOT_RUN),
                        tuple("t0_2_call_2", AgentRunner.STOPPED_NOT_RUN));
        assertThat(savedKinds()).doesNotContain("error");
    }

    @Test
    @DisplayName("a stop that lands while the gateway decides leaves no approval behind on the stopped run")
    void stopBeforeParkingRaisesNoApproval() {
        RunRow row = new RunRow();
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> {
                    row.stop();
                    return new ApprovalDecision.AwaitApproval("Send the welcome email", "approval:decide");
                });
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("call_0", "gmail.send_message", "{}"));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("cancelled");
        verify(approvals, never()).raise(any(), any(), any(), any(), any(), any());
        assertThat(savedKinds()).doesNotContain("approval", "error");
        verify(progress, never()).onRunParked(any());
    }

    @Test
    @DisplayName("an action that deletes something is raised as DESTRUCTIVE, and the trace says so")
    void destructiveToolRecordsItsActionClass() {
        ToolDefinition refund = new ToolDefinition(
                "stripe",
                "refund_payment",
                "Refunds a payment.",
                "{}",
                ToolSpec.SideEffect.DESTRUCTIVE,
                List.of(),
                false,
                null,
                null);
        when(tools.availableTools(any())).thenReturn(List.of(refund));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.AwaitApproval(
                        "Permanently remove something using stripe.refund_payment", "approval:decide"));
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("call_0", "stripe.refund_payment", "{}"));
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(approval);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("waiting_approval");
        verify(approvals).raise(any(), any(), any(), any(), any(), eq("DESTRUCTIVE"));
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        assertThat(trace.getAllValues())
                .filteredOn(step -> "approval".equals(step.getKind()))
                .extracting(step -> step.getDetail().get("actionClass"))
                .containsExactly("DESTRUCTIVE");
        assertThat(AgentRunner.actionClassOf(new ToolCall("x", "gmail.send_message", "{}"), List.of(refund)))
                .isEqualTo("OUTBOUND");
    }

    @Test
    @DisplayName("an approved call that throws is recorded as unknown, and the run fails saying what to check")
    void approvedCallThatThrowsFailsTheRunHonestly() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        UUID approvalId = approved(runId, "gmail.send_message", "call_1");
        List<RunStep> trace = new ArrayList<>(List.of(
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
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Timeout on blocking read for 60000 MILLISECONDS")));

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        String reason =
                "The approved action may or may not have been carried out; check gmail.send_message before retrying.";
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getFailureReason()).isEqualTo(reason);
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("toolCallId", "call_1")
                        .containsEntry("status", "INDETERMINATE"));
        assertThat(trace).extracting(RunStep::getKind).doesNotContain("error");
        verify(router, never()).route(any(), any(), any());
        verify(progress).onRunFinished(run, "failed", null, reason);
    }

    // ---- Starting without waiting ------------------------------------------------------------

    @Test
    @DisplayName("prepare refuses a paused agent before anything is written")
    void prepareRefusesPausedAgent() {
        agent.setStatus("paused");

        assertThatThrownBy(() -> runner.prepare(ORG, agent.getId(), null, INSTRUCTION, "manual"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.POLICY_VIOLATION));
        verifyNoInteractions(runs, steps);
    }

    @Test
    @DisplayName("prepare saves the run and its instruction, and drives nothing")
    void prepareSavesWithoutDriving() {
        when(steps.highestPosition(any())).thenReturn(-1);

        UUID runId = runner.prepare(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(runId).isNotNull();
        verify(runs).saveAndFlush(any());
        assertThat(savedKinds()).containsExactly("note");
        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("drive takes a prepared run from its saved instruction to its answer")
    void driveRunsAPreparedRun() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("running");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(
                        RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION))));
        when(steps.highestPosition(runId)).thenReturn(0);
        when(router.route(any(), any(), any())).thenReturn(answer("Drafted and sent."));

        AgentRunner.Outcome outcome = runner.drive(ORG, runId);

        assertThat(outcome.status()).isEqualTo("completed");
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .extracting(ChatMessage::role)
                .containsExactly(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER);
        assertThat(request.getValue().messages().get(1).content()).isEqualTo(INSTRUCTION);
    }

    @Test
    @DisplayName("drive leaves alone a run that a drive already took past its first step")
    void driveDoesNotDriveTwice() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("running");
        run.setStepCount(1);

        AgentRunner.Outcome outcome = runner.drive(ORG, runId);

        assertThat(outcome.status()).isEqualTo("running");
        verifyNoInteractions(router);
    }

    // ---- Step metadata, and where a result came from ----------------------------------------

    private static ToolDefinition tool(String server, String name, ToolSpec.SideEffect effect) {
        return new ToolDefinition(server, name, "A tool.", "{}", effect, List.of(), false, null, null);
    }

    /** A connector that talks to a real service, as the gateway describes one. */
    private McpServerAdapter liveAdapter(String server) {
        McpServerAdapter adapter = mock(McpServerAdapter.class);
        lenient().when(adapter.isSandbox()).thenReturn(false);
        lenient().when(tools.adapter(server)).thenReturn(Optional.of(adapter));
        return adapter;
    }

    /** The tool_call steps this test's run saved, in order. */
    private List<RunStep> savedToolCalls() {
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        return trace.getAllValues().stream()
                .filter(step -> "tool_call".equals(step.getKind()))
                .toList();
    }

    private List<RunStep> savedSteps(String kind) {
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        return trace.getAllValues().stream().filter(step -> kind.equals(step.getKind())).toList();
    }

    @Test
    @DisplayName("a tool call's step says what the tool does and whether it was a live service or practice data")
    void toolCallStepCarriesSideEffectAndMode() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Priya\"}", "Found one.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "crm.find_contact", "{}"), answer("Found her."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(savedToolCalls()).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("sideEffect", "READ")
                .containsEntry("mode", "sandbox"));
    }

    @Test
    @DisplayName("a connected server with a stored token is called live, with that token, and says so")
    void connectedServerIsCalledLive() {
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Connected("xoxb-token"));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("slack", "list_channels", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), eq("xoxb-token")))
                .thenReturn(Mono.just(
                        ToolResult.succeeded("{\"count\":0,\"items\":[]}", "No channels.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "slack.list_channels", "{}"), answer("None."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(savedToolCalls()).singleElement().satisfies(step -> {
            assertThat(step.getDetail()).containsEntry("mode", "live").containsEntry("sideEffect", "READ");
            assertThat(step.getDetail().get("modelContent")).isEqualTo("{\"count\":0,\"items\":[]}");
        });
    }

    @Test
    @DisplayName("a server nobody connected is answered from practice data, and the model is told it is not real")
    void notConnectedServerIsPracticeData() {
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("slack", "list_channels", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), isNull()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"count\":1}", "One channel.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "slack.list_channels", "{}"), answer("One."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(savedToolCalls()).singleElement().satisfies(step -> {
            assertThat(step.getDetail()).containsEntry("mode", "sandbox");
            assertThat(step.getDetail().get("modelContent")).isEqualTo(PRACTICE + "{\"count\":1}");
        });
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .containsExactly(PRACTICE + "{\"count\":1}");
    }

    @Test
    @DisplayName("a connection store that cannot be asked fails the call without making it, and the run carries on")
    void unavailableStoreFailsTheCallWithoutMakingIt() {
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Unavailable("timed out"));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("slack", "list_channels", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "slack.list_channels", "{}"), answer("I could not read them."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(savedToolCalls()).singleElement().satisfies(step -> {
            assertThat(step.getDetail())
                    .containsEntry("status", "FAILED")
                    .containsEntry("summary", ToolCredentialResolver.UNAVAILABLE_MESSAGE)
                    .containsEntry("mode", "live");
            assertThat(String.valueOf(step.getDetail().get("modelContent")))
                    .contains("nothing was read or sent")
                    .doesNotContain(PRACTICE);
        });
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .singleElement()
                .asString()
                .contains("Try again shortly");
    }

    @Test
    @DisplayName(
            "a call that throws while it is made is recorded as unknown, so a write is not retried as if it had"
                    + " not gone")
    void callThatThrowsIsRecordedAsUnknown() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "create_contact", ToolSpec.SideEffect.WRITE)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Timeout on blocking read")));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "crm.create_contact", "{}"), answer("It may not have saved."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(savedToolCalls()).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("status", "INDETERMINATE")
                .containsEntry("sideEffect", "WRITE"));
    }

    // ---- The approved call: the same three answers ------------------------------------------------

    /** A run parked on an approval for slack.post_message, with the trace and approval a resume reads. */
    private List<RunStep> parkedOnSlackPost(UUID runId) {
        parkedRun(runId);
        UUID approvalId = UUID.randomUUID();
        List<RunStep> trace = new ArrayList<>(List.of(
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
                                "slack.post_message"))));
        stubStepsBackedBy(runId, trace);
        Approval approval = new Approval();
        approval.setId(approvalId);
        approval.setOrgId(ORG);
        approval.setRunId(runId);
        approval.setAgentId(agent.getId());
        approval.setTool("slack.post_message");
        approval.setToolCallId("call_1");
        approval.setActionClass("OUTBOUND");
        approval.setStatus("approved");
        approval.setPayload("{\"text\":\"Hello\"}");
        when(approvals.find(ORG, approvalId)).thenReturn(Optional.of(approval));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));
        return trace;
    }

    @Test
    @DisplayName("an approved call to a connected server is made live, with its token, and the step says so")
    void approvedCallConnectedIsLive() {
        UUID runId = UUID.randomUUID();
        List<RunStep> trace = parkedOnSlackPost(runId);
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Connected("xoxb-token"));
        when(tools.invoke(any(), any(), eq("xoxb-token")))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Posted.", Duration.ZERO)));

        runner.resume(ORG, runId);

        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("mode", "live")
                        .containsEntry("sideEffect", "OUTBOUND")
                        .containsEntry("modelContent", "{\"ok\":true}"));
    }

    @Test
    @DisplayName("an approved call to a server nobody connected runs against practice data, and the step says so")
    void approvedCallNotConnectedIsPracticeData() {
        UUID runId = UUID.randomUUID();
        List<RunStep> trace = parkedOnSlackPost(runId);
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
        when(tools.invoke(any(), any(), isNull()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Posted.", Duration.ZERO)));

        runner.resume(ORG, runId);

        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("mode", "sandbox")
                        .containsEntry("modelContent", PRACTICE + "{\"ok\":true}"));
    }

    @Test
    @DisplayName(
            "an approved call whose connection store cannot be asked is not made, and is recorded as failed so"
                    + " it counts as answered")
    void approvedCallWithUnavailableStoreIsRecordedAsFailed() {
        UUID runId = UUID.randomUUID();
        List<RunStep> trace = parkedOnSlackPost(runId);
        liveAdapter("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Unavailable("HTTP 503"));

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("toolCallId", "call_1")
                        .containsEntry("status", "FAILED")
                        .containsEntry("summary", ToolCredentialResolver.UNAVAILABLE_MESSAGE));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .singleElement()
                .asString()
                .contains("nothing was read or sent");
    }

    // ---- A connection that was live and is not any more ---------------------------------------------

    private void slackPostCall() {
        liveAdapter("slack");
        when(credentials.isLive("slack")).thenReturn(true);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("slack", "post_message", ToolSpec.SideEffect.OUTBOUND)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "slack.post_message", "{\"text\":\"Hi\"}"), answer("It was not sent."));
    }

    @Test
    @DisplayName("a connector that was live when the run started and is disconnected now fails, never practice data")
    void liveAtStartThenDisconnectedFails() {
        slackPostCall();
        when(runs.liveServersOf(any())).thenReturn("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(savedToolCalls()).singleElement().satisfies(step -> {
            assertThat(step.getDetail())
                    .containsEntry("status", "FAILED")
                    .containsEntry("mode", "live")
                    .containsEntry("summary", "Slack was disconnected, so nothing was sent.");
            assertThat(String.valueOf(step.getDetail().get("modelContent"))).doesNotContain(PRACTICE);
        });
    }

    @Test
    @DisplayName("an earlier live call in the same run also counts as live")
    void liveEarlierInTheRunThenDisconnectedFails() {
        slackPostCall();
        when(runs.liveServersOf(any())).thenReturn(null);
        when(steps.findByRunIdOrderByPosition(any()))
                .thenReturn(List.of(RunStep.of(
                        ORG, UUID.randomUUID(), 3, "tool_call", Map.of("tool", "slack.list_channels", "mode", "live"))));
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(savedToolCalls()).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("summary", "Slack was disconnected, so nothing was sent."));
    }

    @Test
    @DisplayName("a connector that was never live in the run still answers from practice data")
    void neverLiveStaysPracticeData() {
        slackPostCall();
        when(runs.liveServersOf(any())).thenReturn("");
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
        when(tools.invoke(any(), any(), isNull()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Posted.", Duration.ZERO)));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(savedToolCalls()).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("mode", "sandbox"));
    }

    @Test
    @DisplayName("a connection that needs reconnecting fails with the reconnect sentence and is not called")
    void reconnectRequiredFails() {
        slackPostCall();
        when(runs.liveServersOf(any())).thenReturn("slack");
        when(credentials.resolve(ORG, "slack"))
                .thenReturn(new ToolCredentialResolver.ReconnectRequired("Slack no longer accepts the sign-in."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(savedToolCalls()).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("status", "FAILED")
                .containsEntry("mode", "live")
                .containsEntry("summary", "Slack needs to be reconnected by an administrator."));
    }

    @Test
    @DisplayName("the live connectors are noted once, as the run begins")
    void liveConnectorsAreSnapshotAtStart() {
        slackPostCall();
        when(runs.liveServersOf(any())).thenReturn(null);
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Connected("xoxb-token"));
        when(tools.invoke(any(), any(), eq("xoxb-token")))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Posted.", Duration.ZERO)));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(runs).recordLiveServers(any(), eq("slack"));
    }

    @Test
    @DisplayName("an approved call raised against a live connection that is gone fails, and the approval shows it")
    void approvedCallAfterDisconnectFails() {
        UUID runId = UUID.randomUUID();
        List<RunStep> trace = parkedOnSlackPost(runId);
        UUID approvalId = UUID.fromString((String) trace.get(1).getDetail().get("approvalId"));
        approvals.find(ORG, approvalId).orElseThrow().setMode("live");
        liveAdapter("slack");
        when(credentials.isLive("slack")).thenReturn(true);
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);

        AgentRunner.Outcome outcome = runner.resume(ORG, runId);

        verify(tools, never()).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail())
                        .containsEntry("toolCallId", "call_1")
                        .containsEntry("status", "FAILED")
                        .containsEntry("mode", "live")
                        .containsEntry("summary", "Slack was disconnected, so nothing was sent."));
        verify(approvals).recordOutcome(approvalId, "FAILED: Slack was disconnected, so nothing was sent.");
    }

    @Test
    @DisplayName("an approved call raised against practice data still runs against practice data")
    void approvedSandboxCallStaysSandbox() {
        UUID runId = UUID.randomUUID();
        List<RunStep> trace = parkedOnSlackPost(runId);
        UUID approvalId = UUID.fromString((String) trace.get(1).getDetail().get("approvalId"));
        approvals.find(ORG, approvalId).orElseThrow().setMode("sandbox");
        liveAdapter("slack");
        when(credentials.isLive("slack")).thenReturn(true);
        when(credentials.resolve(ORG, "slack")).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
        when(tools.invoke(any(), any(), isNull()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"ok\":true}", "Posted.", Duration.ZERO)));

        runner.resume(ORG, runId);

        assertThat(trace)
                .filteredOn(step -> "tool_call".equals(step.getKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.getDetail()).containsEntry("mode", "sandbox"));
        verify(approvals).recordOutcome(approvalId, "SUCCEEDED: Posted.");
    }

    @Test
    @DisplayName("an approval is raised with the mode its call was going to")
    void approvalRecordsItsMode() {
        slackPostCall();
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.AwaitApproval("Posting needs a person.", "approval:decide"));
        when(runs.liveServersOf(any())).thenReturn("slack");
        when(credentials.resolve(ORG, "slack")).thenReturn(new ToolCredentialResolver.Connected("xoxb-token"));
        Approval raised = new Approval();
        raised.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(raised);

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(raised.getMode()).isEqualTo("live");
    }

    // ---- The step limit -----------------------------------------------------------------------

    @Test
    @DisplayName("with two steps left the agent is told to wrap up, and on the last step to answer now")
    void wrapUpNoteWhenStepsRunLow() {
        version.setMaxSteps(3);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Priya\"}", "Found.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("call_0", "crm.find_contact", "{\"q\":\"a\"}"),
                        toolCallAnswer("call_0", "crm.find_contact", "{\"q\":\"b\"}"),
                        answer("Here you go."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(3)).route(requests.capture(), any(), any());
        List<ChatRequest> sent = requests.getAllValues();
        assertThat(sent.get(0).messages().getFirst().content()).doesNotContain("steps left");
        assertThat(sent.get(1).messages().getFirst().content())
                .endsWith(AgentRunner.WRAP_UP_NOTE)
                .contains(version.getSystemPrompt());
        assertThat(sent.get(2).messages().getFirst().content()).endsWith(AgentRunner.LAST_STEP_NOTE);
        assertThat(sent.get(1).messages())
                .extracting(ChatMessage::role)
                .startsWith(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER);
    }

    @Test
    @DisplayName("tools asked for on the last step are not run, and the turn's text is kept as an incomplete answer")
    void lastStepToolCallsAreNotRunAndTheTextIsKept() {
        version.setMaxSteps(2);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Priya\"}", "Found.", Duration.ZERO)));
        ChatResponse lastTurn = new ChatResponse(
                "Two of the three contacts are checked so far.",
                List.of(new ToolCall("call_0", "crm.find_contact", "{\"q\":\"third\"}")),
                FinishReason.TOOL_CALLS,
                TokenUsage.of(120, 30),
                "sandbox",
                "sandbox-echo",
                Duration.ofMillis(40),
                List.of(),
                Map.of());
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "crm.find_contact", "{\"q\":\"first\"}"), lastTurn);
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        String reason = "The agent reached its step limit of 2 without finishing.";
        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.answer()).isEqualTo("Two of the three contacts are checked so far.");
        verify(progress)
                .onRunFinished(saved.getValue(), "failed", "Two of the three contacts are checked so far.", reason);
        verify(tools, times(1)).invoke(any(), any(), any());
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"), step -> step.getDetail().get("summary"))
                .containsExactly(tuple("SUCCEEDED", "Found."), tuple("BLOCKED", "Not run: step limit reached"));
        assertThat(savedSteps("error")).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("code", "step_limit")
                .containsEntry("detail", reason));
    }

    @Test
    @DisplayName("a run that used all its steps without a last turn is still reported as hitting the step limit")
    void stepLimitWithoutAnswerRecordsTheCode() {
        version.setMaxSteps(1);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("call_0", "crm.find_contact", "{}"));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(outcome.status()).isEqualTo("failed");
        verify(tools, never()).invoke(any(), any(), any());
        verify(progress)
                .onRunFinished(
                        saved.getValue(), "failed", null, "The agent reached its step limit of 1 without finishing.");
        assertThat(savedSteps("error")).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("code", "step_limit"));
    }

    // ---- An answer cut off by the output limit -------------------------------------------------

    private static ChatResponse cutOff(String text) {
        return new ChatResponse(
                text,
                List.of(),
                FinishReason.LENGTH,
                TokenUsage.of(120, 30),
                "openrouter",
                "big-model",
                Duration.ofMillis(40),
                List.of(),
                Map.of());
    }

    private void modelAllowsOutputOf(int maxOutputTokens) {
        when(registry.model(ORG.toString(), "openrouter", "big-model"))
                .thenReturn(Optional.of(new ModelSpec(
                        "openrouter",
                        "big-model",
                        "Big model",
                        128_000,
                        maxOutputTokens,
                        true,
                        true,
                        true,
                        false,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        null)));
    }

    @Test
    @DisplayName(
            "a reply cut off by the output limit is asked again at the model's maximum, then continued, and the"
                    + " parts are joined")
    void cutOffAnswerIsRetriedThenContinuedAndJoined() {
        version.setMaxSteps(6);
        version.setMaxOutputTokens(1024);
        modelAllowsOutputOf(8192);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        cutOff("A first attempt that is thrown away"),
                        cutOff("Section one, and section two begins "),
                        new ChatResponse(
                                "and then it ends.",
                                List.of(),
                                FinishReason.STOP,
                                TokenUsage.of(120, 30),
                                "openrouter",
                                "big-model",
                                Duration.ofMillis(40),
                                List.of(),
                                Map.of()));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.answer()).isEqualTo("Section one, and section two begins and then it ends.");
        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        verify(progress)
                .onRunFinished(
                        saved.getValue(), "completed", "Section one, and section two begins and then it ends.", null);
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(3)).route(requests.capture(), any(), any());
        List<ChatRequest> sent = requests.getAllValues();
        assertThat(sent.get(0).maxOutputTokens()).isEqualTo(1024);
        // The retry asks for the whole conversation again, with the model's own maximum.
        assertThat(sent.get(1).maxOutputTokens()).isEqualTo(8192);
        assertThat(sent.get(1).messages()).hasSize(2);
        // The continuation carries what was written and asks for the rest.
        List<ChatMessage> third = sent.get(2).messages();
        assertThat(third.get(third.size() - 2).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(third.get(third.size() - 2).content()).isEqualTo("Section one, and section two begins ");
        assertThat(third.getLast().role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(third.getLast().content()).isEqualTo("Continue exactly where you stopped.");
        // Each of them is a step, and the two that did not finish the answer are marked.
        assertThat(savedSteps("model_call"))
                .extracting(step -> step.getDetail().get("truncated"), step -> step.getDetail().get("next"))
                .containsExactly(tuple(true, "retry"), tuple(true, "continue"), tuple(null, null));
        assertThat(saved.getValue().getStepCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("an answer still cut off after two continuations fails without a retry and keeps the joined text")
    void stillCutOffAfterTwoContinuationsFailsAndKeepsTheText() {
        version.setMaxSteps(10);
        version.setMaxOutputTokens(1024);
        modelAllowsOutputOf(8192);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(cutOff("thrown away"), cutOff("One, "), cutOff("two, "), cutOff("three,"));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        verify(router, times(4)).route(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("failed");
        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        verify(progress)
                .onRunFinished(
                        saved.getValue(),
                        "failed",
                        "One, two, three,",
                        "The model's answer was cut short by the output limit.");
        assertThat(savedSteps("error")).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("code", "output_limit"));
    }

    @Test
    @DisplayName(
            "a cut-off reply from a model already at its maximum is continued without asking again at the same size")
    void cutOffAtTheModelMaximumIsContinuedStraightAway() {
        version.setMaxSteps(6);
        version.setMaxOutputTokens(8192);
        modelAllowsOutputOf(8192);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any()))
                .thenReturn(
                        cutOff("First half, "),
                        new ChatResponse(
                                "second half.",
                                List.of(),
                                FinishReason.STOP,
                                TokenUsage.of(120, 30),
                                "openrouter",
                                "big-model",
                                Duration.ofMillis(40),
                                List.of(),
                                Map.of()));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.answer()).isEqualTo("First half, second half.");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        assertThat(requests.getAllValues().get(1).messages().getLast().content())
                .isEqualTo("Continue exactly where you stopped.");
    }

    @Test
    @DisplayName("a cut-off answer on the last step is not continued: it fails and keeps what there is")
    void cutOffOnTheLastStepIsKept() {
        version.setMaxSteps(1);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(cutOff("Half a report"));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(router, times(1)).route(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.answer()).isEqualTo("Half a report");
    }

    @Test
    @DisplayName("a rebuilt conversation leaves out an answer that was cut off and then asked for again")
    void rebuildSkipsCutOffReplies() {
        UUID runId = UUID.randomUUID();
        Run run = parkedRun(runId);
        run.setStatus("running");
        when(steps.findByRunIdOrderByPosition(runId))
                .thenReturn(List.of(
                        RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                        RunStep.of(
                                ORG,
                                runId,
                                1,
                                "model_call",
                                Map.of("content", "Half of it", "truncated", true, "next", "retry"))));
        when(steps.highestPosition(runId)).thenReturn(1);
        when(router.route(any(), any(), any())).thenReturn(answer("Whole."));

        // drive() takes a run still at its first step; give it one by hand.
        run.setStepCount(0);
        runner.drive(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .extracting(ChatMessage::role)
                .containsExactly(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER);
    }

    // ---- Loops --------------------------------------------------------------------------------

    @Test
    @DisplayName("an identical read is answered once from the earlier result, and refused from the third time on")
    void identicalReadsAreAnsweredThenRefused() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Priya\"}", "Found one.", Duration.ZERO)));
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);

        // The second call writes its arguments in another order: the same call. Three refused
        // repeats follow the one that is answered, so the model is asked five times.
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\",\"limit\":1}"),
                        toolCallAnswer("c", "crm.find_contact", "{ \"limit\": 1, \"q\": \"priya\" }"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\",\"limit\":1}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\",\"limit\":1}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\",\"limit\":1}"),
                        answer("Never reached."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        verify(tools, times(1)).invoke(any(), any(), any());
        verify(router, times(5)).route(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("failed");
        verify(runs, atLeastOnce()).saveAndFlush(saved.capture());
        verify(progress).onRunFinished(saved.getValue(), "failed", null, "The agent was repeating the same action.");
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"), step -> step.getDetail().get("summary"))
                .containsExactly(
                        tuple("SUCCEEDED", "Found one."),
                        tuple(
                                "SUCCEEDED",
                                "Repeated identical read; the earlier result was returned without calling the tool"
                                        + " again."),
                        tuple("BLOCKED", "Repeated identical call; change approach or finish."),
                        tuple("BLOCKED", "Repeated identical call; change approach or finish."),
                        tuple("BLOCKED", "Repeated identical call; change approach or finish."));
        // The second answer is the first result with the note, not a new call.
        assertThat(savedToolCalls().get(1).getDetail().get("modelContent"))
                .isEqualTo("You already have this result; use it or change approach. " + PRACTICE
                        + "{\"name\":\"Priya\"}");
        assertThat(savedSteps("error")).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("code", "loop_detected")
                .containsEntry("detail", "The agent was repeating the same action."));
    }

    @Test
    @DisplayName("a write is never replayed: the second identical call is refused without being made")
    void identicalWriteIsNeverReplayed() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "create_contact", ToolSpec.SideEffect.WRITE)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"id\":\"c1\"}", "Created.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Priya\"}"),
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Priya\"}"),
                        answer("Created the contact once."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, times(1)).invoke(any(), any(), any());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"))
                .containsExactly("SUCCEEDED", "BLOCKED");
    }

    @Test
    @DisplayName("a call that was refused by policy is not repeated into a loop: the second try is refused too")
    void refusedCallRepeatedIsRefusedWithoutAskingAgain() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.Refuse("This agent has not been granted gmail.send_message."));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "gmail.send_message", "{}"),
                        toolCallAnswer("c", "gmail.send_message", "{}"),
                        answer("I cannot send it."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, times(1)).evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly(
                        "This agent has not been granted gmail.send_message.",
                        "Repeated identical call; change approach or finish.");
    }

    @Test
    @DisplayName("reads that differ are not repeats, and a read asked again after other calls is not a loop")
    void differentReadsAreNotRepeats() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{}", "Found.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"a\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"b\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"c\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"d\"}"),
                        // Four calls on, the first one has left the window of three.
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"a\"}"),
                        answer("Done."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(tools, times(5)).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("a read asked again after the agent changed something runs for real, and is not a replay")
    void readAfterAWriteIsMadeAgain() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(
                        tool("crm", "find_contact", ToolSpec.SideEffect.READ),
                        tool("crm", "create_contact", ToolSpec.SideEffect.WRITE)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(
                        Mono.just(ToolResult.succeeded("{\"count\":0}", "None found.", Duration.ZERO)),
                        Mono.just(ToolResult.succeeded("{\"id\":\"c1\"}", "Created.", Duration.ZERO)),
                        Mono.just(ToolResult.succeeded("{\"count\":1}", "Found one.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Priya\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        answer("Created and confirmed."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(tools, times(3)).invoke(any(), any(), any());
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"), step -> step.getDetail().get("summary"))
                .containsExactly(
                        tuple("SUCCEEDED", "None found."),
                        tuple("SUCCEEDED", "Created."),
                        tuple("SUCCEEDED", "Found one."));
        assertThat((String) savedToolCalls().get(2).getDetail().get("modelContent"))
                .doesNotContain("You already have this result")
                .contains("{\"count\":1}");
    }

    @Test
    @DisplayName("a write is still refused when repeated after reads were forgotten; a failed write forgets nothing")
    void changesStayRememberedAndFailedWritesForgetNothing() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(
                        tool("crm", "find_contact", ToolSpec.SideEffect.READ),
                        tool("crm", "create_contact", ToolSpec.SideEffect.WRITE)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(
                        Mono.just(ToolResult.succeeded("{\"id\":\"c1\"}", "Created.", Duration.ZERO)),
                        Mono.just(ToolResult.succeeded("{\"count\":1}", "Found one.", Duration.ZERO)),
                        Mono.just(ToolResult.failed("Rejected.")));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Priya\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        // The same change again: refused, not made.
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Priya\"}"),
                        // A different change that fails; the read before it is still remembered.
                        toolCallAnswer("c", "crm.create_contact", "{\"name\":\"Sam\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        answer("Done."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        verify(tools, times(3)).invoke(any(), any(), any());
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "BLOCKED", "FAILED", "SUCCEEDED");
        assertThat(savedToolCalls().get(4).getDetail())
                .containsEntry("repeated", true)
                .extracting(detail -> detail.get("modelContent"))
                .asString()
                .startsWith("You already have this result");
    }

    @Test
    @DisplayName("a read that failed or did not answer is made again; only a read that succeeded is replayed")
    void failedReadsAreMadeAgain() {
        version.setMaxSteps(10);
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("crm", "find_contact", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(
                        Mono.just(ToolResult.indeterminate("Timed out.", Duration.ZERO)),
                        Mono.just(ToolResult.failed("The service returned an error.")),
                        Mono.just(ToolResult.succeeded("{\"count\":1}", "Found one.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        toolCallAnswer("c", "crm.find_contact", "{\"q\":\"priya\"}"),
                        answer("Found her."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        // Three real calls; the fourth is the replay of the one that succeeded.
        verify(tools, times(3)).invoke(any(), any(), any());
        assertThat(savedToolCalls())
                .extracting(step -> step.getDetail().get("status"))
                .containsExactly("INDETERMINATE", "FAILED", "SUCCEEDED", "SUCCEEDED");
        assertThat(savedToolCalls().get(0).getDetail()).doesNotContainKey("repeated");
        assertThat(savedToolCalls().get(1).getDetail()).doesNotContainKey("repeated");
        assertThat(savedToolCalls().get(3).getDetail()).containsEntry("repeated", true);
    }

    @Test
    @DisplayName("arguments are keyed with their keys in order, so the same call written differently is the same call")
    void argumentsAreCanonical() {
        assertThat(AgentRunner.callKey("a.b", "{\"b\":1,\"a\":{\"y\":2,\"x\":[3,{\"d\":4,\"c\":5}]}}"))
                .isEqualTo(AgentRunner.callKey(
                        "a.b", "{ \"a\": {\"x\": [3, {\"c\":5, \"d\":4}], \"y\":2}, \"b\": 1 }"));
        assertThat(AgentRunner.callKey("a.b", null)).isEqualTo(AgentRunner.callKey("a.b", "{}"));
        assertThat(AgentRunner.callKey("a.b", "{\"q\":1}")).isNotEqualTo(AgentRunner.callKey("a.c", "{\"q\":1}"));
        assertThat(AgentRunner.callKey("a.b", "{\"q\":1}")).isNotEqualTo(AgentRunner.callKey("a.b", "{\"q\":2}"));
        // Malformed text is its own key, rather than an error.
        assertThat(AgentRunner.callKey("a.b", "{not json")).isEqualTo("a.b {not json");
    }

    // ---- What the model is shown of a result -------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String listOf(int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            items.append(i == 0 ? "" : ",")
                    .append("{\"id\":")
                    .append(i)
                    .append(",\"title\":\"A fairly long issue title that takes space number ")
                    .append(i)
                    .append("\"}");
        }
        return "{\"count\":" + count + ",\"items\":[" + items + "]}";
    }

    @Test
    @DisplayName("a long list loses its trailing items, says how many it shows, and is still valid JSON")
    void capForModelShortensAListAtItemBoundaries() throws Exception {
        String capped = AgentRunner.capForModel(listOf(500));

        assertThat(capped.length()).isLessThanOrEqualTo(AgentRunner.MODEL_RESULT_LIMIT);
        JsonNode parsed = MAPPER.readTree(capped);
        int shown = parsed.get("shown").asInt();
        assertThat(parsed.get("truncated").asBoolean()).isTrue();
        assertThat(shown).isBetween(1, 499);
        assertThat(parsed.get("total").asInt()).isEqualTo(500);
        assertThat(parsed.get("count").asInt()).isEqualTo(500);
        assertThat(parsed.get("items")).hasSize(shown);
        assertThat(parsed.get("items").get(0).get("id").asInt()).isZero();
        assertThat(parsed.get("note").asText()).isEqualTo("Narrow the query or request the next page.");
        // As many as fit: one more would not have.
        assertThat(AgentRunner.capForModel(listOf(shown + 1)).length())
                .isLessThanOrEqualTo(AgentRunner.MODEL_RESULT_LIMIT);
    }

    @Test
    @DisplayName("anything else that is too long is wrapped as JSON text, never cut with a bare ellipsis")
    void capForModelWrapsOtherContent() throws Exception {
        String quoted = "He said \"hello\"\n\t".repeat(3_000);

        String capped = AgentRunner.capForModel(quoted);

        assertThat(capped.length()).isLessThanOrEqualTo(AgentRunner.MODEL_RESULT_LIMIT);
        JsonNode parsed = MAPPER.readTree(capped);
        assertThat(parsed.get("truncated").asBoolean()).isTrue();
        assertThat(quoted).startsWith(parsed.get("text").asText());
        assertThat(capped).doesNotContain("…");
        // Text that is not even a list shape, in a JSON object with no items.
        String blob = "{\"blob\":\"" + "x".repeat(20_000) + "\"}";
        assertThat(MAPPER.readTree(AgentRunner.capForModel(blob)).get("truncated").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("a result within the limit, and one already capped, come back unchanged")
    void capForModelIsIdempotent() {
        assertThat(AgentRunner.capForModel("{\"ok\":true}")).isEqualTo("{\"ok\":true}");
        assertThat(AgentRunner.capForModel(null)).isEmpty();
        String once = AgentRunner.capForModel(listOf(800));
        assertThat(AgentRunner.capForModel(once)).isEqualTo(once);
        String text = AgentRunner.capForModel("z".repeat(50_000));
        assertThat(AgentRunner.capForModel(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("what the model is told live and what the trace saved are the same text, and within the limit")
    void liveAndSavedResultsAreIdentical() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.availableTools(any()))
                .thenReturn(List.of(tool("github", "list_issues", ToolSpec.SideEffect.READ)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded(listOf(600), "600 issues.", Duration.ZERO)));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("call_0", "github.list_issues", "{}"), answer("Counted them."));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        String saved = (String) savedToolCalls().getFirst().getDetail().get("modelContent");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(2)).route(requests.capture(), any(), any());
        String live = requests.getAllValues().get(1).messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.TOOL)
                .map(ChatMessage::content)
                .findFirst()
                .orElseThrow();
        assertThat(live).isEqualTo(saved);
        assertThat(live.length()).isLessThanOrEqualTo(AgentRunner.MODEL_RESULT_LIMIT);
        assertThat(live).contains("\"truncated\":true").contains("\"total\":600");
    }

    @Test
    @DisplayName(
            "practice data stays within the limit with its note in front, and is unchanged when a conversation"
                    + " is rebuilt")
    void practiceDataNoteCountsAgainstTheLimit() {
        ToolResult big = ToolResult.succeeded(listOf(600), "600 issues.", Duration.ZERO);

        String content = AgentRunner.modelContentOf(big, true);

        assertThat(content).startsWith(PRACTICE);
        assertThat(content.length()).isLessThanOrEqualTo(AgentRunner.MODEL_RESULT_LIMIT);
        // What a rebuild reads back is not capped a second time.
        assertThat(AgentRunner.capForModel(content)).isEqualTo(content);
        // A failure is not practice data.
        assertThat(AgentRunner.modelContentOf(ToolResult.failed("No such record."), true))
                .doesNotContain("Practice data");
    }

    // ---- What an earlier attempt already did ---------------------------------------------------------

    private Run earlierRunFor(UUID taskId, List<RunStep> trace) {
        Run earlier = new Run();
        earlier.setId(UUID.randomUUID());
        earlier.setOrgId(ORG);
        earlier.setTaskId(taskId);
        earlier.setAgentId(agent.getId());
        earlier.setStatus("failed");
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(List.of(taskId))).thenReturn(List.of(earlier));
        when(steps.findByRunIdOrderByPosition(earlier.getId())).thenReturn(trace);
        return earlier;
    }

    private static RunStep doneStep(
            UUID runId, int position, String tool, String sideEffect, String status, String summary) {
        return RunStep.of(
                ORG,
                runId,
                position,
                "tool_call",
                Map.of(
                        "toolCallId",
                        "c" + position,
                        "tool",
                        tool,
                        "status",
                        status,
                        "sideEffect",
                        sideEffect,
                        "summary",
                        summary));
    }

    @Test
    @DisplayName("a retried task is told what its earlier attempt already did, ahead of the instruction")
    void retriedTaskIsToldWhatWasAlreadyDone() {
        UUID taskId = UUID.randomUUID();
        UUID earlierId = UUID.randomUUID();
        earlierRunFor(
                taskId,
                List.of(
                        doneStep(earlierId, 1, "slack.post_message", "OUTBOUND", "SUCCEEDED", "Posted to  #general."),
                        doneStep(earlierId, 2, "crm.find_contact", "READ", "SUCCEEDED", "Found one."),
                        doneStep(earlierId, 3, "github.create_issue", "WRITE", "FAILED", "Rejected."),
                        doneStep(earlierId, 4, "gmail.send_message", "OUTBOUND", "INDETERMINATE", "No confirmation.")));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(answer("Finished the rest."));

        runner.start(ORG, agent.getId(), taskId, INSTRUCTION, "task");

        String expected = "In the previous attempt these were already done; do not repeat them:\n"
                + "- slack.post_message: Posted to #general.\n"
                + "In the previous attempt these may or may not have happened, because the outcome was never confirmed;"
                + " check before doing them again:\n"
                + "- gmail.send_message: No confirmation.\n"
                + "\n"
                + INSTRUCTION;
        assertThat(savedSteps("note")).first().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("type", "instruction")
                .containsEntry("content", expected));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages().get(1).content()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an earlier attempt that only read, or a first attempt, leaves the instruction as it was")
    void instructionIsUnchangedWhenNothingWasChanged() {
        UUID taskId = UUID.randomUUID();
        UUID earlierId = UUID.randomUUID();
        earlierRunFor(taskId, List.of(doneStep(earlierId, 1, "crm.find_contact", "READ", "SUCCEEDED", "Found one.")));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.start(ORG, agent.getId(), taskId, INSTRUCTION, "task");

        assertThat(savedSteps("note")).first().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("content", INSTRUCTION));
        assertThat(AgentRunner.withPriorChanges(INSTRUCTION, AgentRunner.PriorAttempts.NONE)).isEqualTo(INSTRUCTION);
    }

    @Test
    @DisplayName("an approval raised by a retried run names the attempt and what the last one already ran")
    void approvalOnARetriedRunSaysWhatRanBefore() {
        UUID taskId = UUID.randomUUID();
        UUID earlierId = UUID.randomUUID();
        earlierRunFor(
                taskId, List.of(doneStep(earlierId, 1, "slack.post_message", "OUTBOUND", "SUCCEEDED", "Posted.")));
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.AwaitApproval(
                        "Send something outside the workspace", "approval:decide"));
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("c", "gmail.send_message", "{}"));
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(approval);

        runner.start(ORG, agent.getId(), taskId, INSTRUCTION, "task");

        ArgumentCaptor<ApprovalDecision.AwaitApproval> await =
                ArgumentCaptor.forClass(ApprovalDecision.AwaitApproval.class);
        verify(approvals).raise(any(), any(), any(), await.capture(), any(), any());
        assertThat(await.getValue().reason())
                .isEqualTo("Attempt 2. The previous attempt already ran slack.post_message. "
                        + "Send something outside the workspace");
        assertThat(await.getValue().approverPermission()).isEqualTo("approval:decide");
        assertThat(savedSteps("approval")).singleElement().satisfies(step -> assertThat(step.getDetail().get("summary"))
                .asString()
                .startsWith("Attempt 2. The previous attempt already ran slack.post_message."));
    }

    @Test
    @DisplayName("a first attempt raises its approval with the gateway's own summary")
    void firstAttemptApprovalIsUnchanged() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.AwaitApproval(
                        "Send something outside the workspace", "approval:decide"));
        when(router.route(any(), any(), any())).thenReturn(toolCallAnswer("c", "gmail.send_message", "{}"));
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(approval);

        runner.start(ORG, agent.getId(), UUID.randomUUID(), INSTRUCTION, "task");

        ArgumentCaptor<ApprovalDecision.AwaitApproval> await =
                ArgumentCaptor.forClass(ApprovalDecision.AwaitApproval.class);
        verify(approvals).raise(any(), any(), any(), await.capture(), any(), any());
        assertThat(await.getValue().reason()).isEqualTo("Send something outside the workspace");
    }

    // ---- Errors and audit ------------------------------------------------------------------------------

    @Test
    @DisplayName("an error step keeps the attempts the router made when no model could be reached")
    void errorStepKeepsTheRouterAttempts() {
        when(steps.highestPosition(any())).thenReturn(-1);
        List<String> attempts = List.of("openrouter/a skipped: no credential", "groq/b failed: timeout");
        when(router.route(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.NO_MODEL_AVAILABLE).with("attempts", attempts));

        runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(savedSteps("error")).singleElement().satisfies(step -> assertThat(step.getDetail())
                .containsEntry("code", "no_model_available")
                .containsEntry("attempts", attempts));
    }

    @Test
    @DisplayName("a policy refusal is audited as tool.refuse, denied, once its step has been saved")
    @SuppressWarnings("unchecked")
    void policyRefusalIsAudited() {
        when(steps.highestPosition(any())).thenReturn(-1);
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ApprovalDecision.Refuse("This agent has not been granted gmail.send_message."));
        when(router.route(any(), any(), any()))
                .thenReturn(toolCallAnswer("c", "gmail.send_message", "{}"), answer("I cannot send it."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        any(),
                        eq("tool.refuse"),
                        eq("run"),
                        eq(outcome.runId().toString()),
                        eq("denied"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("tool", "gmail.send_message")
                .containsEntry("reason", "This agent has not been granted gmail.send_message.");
    }

    /** The kinds of every step this test's run saved, in order. */
    private List<String> savedKinds() {
        ArgumentCaptor<RunStep> trace = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(trace.capture());
        return trace.getAllValues().stream().map(RunStep::getKind).toList();
    }

    /**
     * One run's row behind the mocked repository, acting as the database would: a save must carry
     * the version the row has, a stop and the reaper's update bump it, and the heartbeat's renewal
     * changes only the lease of a run that is still running.
     */
    private final class RunRow {

        volatile String status = "running";
        volatile Instant lease;
        volatile long version;
        volatile UUID id;
        final AtomicInteger renewals = new AtomicInteger();
        final AtomicInteger abandoned = new AtomicInteger();

        RunRow() {
            when(runs.saveAndFlush(any())).thenAnswer(call -> save(call.getArgument(0)));
            when(runs.findStatusById(any())).thenAnswer(call -> Optional.of(status));
            when(runs.findById(any())).thenAnswer(call -> Optional.of(snapshot()));
            when(runs.lockByIdAndOrgId(any(), any())).thenAnswer(call -> Optional.of(snapshot()));
            when(runs.renewLease(any(), any(), any())).thenAnswer(call -> renew(call.getArgument(2)));
            when(runs.findAbandoned(any(), any())).thenAnswer(call -> expiredBy(call.getArgument(0)));
            when(runs.markAbandoned(any(), any(), any(), any())).thenAnswer(call -> abandon(call.getArgument(3)));
        }

        synchronized Run save(Run run) {
            if (run.getVersion() != version) {
                throw new ObjectOptimisticLockingFailureException(Run.class, run.getId());
            }
            id = run.getId();
            status = run.getStatus();
            lease = run.getLeaseExpiresAt();
            version++;
            return snapshot();
        }

        synchronized void stop() {
            status = "cancelled";
            lease = null;
            version++;
        }

        synchronized int renew(Instant until) {
            if (!"running".equals(status)) {
                return 0;
            }
            lease = until;
            renewals.incrementAndGet();
            return 1;
        }

        synchronized List<Run> expiredBy(Instant now) {
            return "running".equals(status) && lease != null && lease.isBefore(now) ? List.of(snapshot()) : List.of();
        }

        synchronized int abandon(Instant now) {
            if (!"running".equals(status) || lease == null || !lease.isBefore(now)) {
                return 0;
            }
            status = "abandoned";
            lease = null;
            version++;
            abandoned.incrementAndGet();
            return 1;
        }

        synchronized Run snapshot() {
            Run copy = new Run();
            copy.setId(id == null ? UUID.randomUUID() : id);
            copy.setOrgId(ORG);
            copy.setAgentId(agent.getId());
            copy.setAgentVersionId(version().getId());
            copy.setStatus(status);
            copy.setLeaseExpiresAt(lease);
            ReflectionTestUtils.setField(copy, "version", version);
            return copy;
        }
    }

    private AgentVersion version() {
        return version;
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
