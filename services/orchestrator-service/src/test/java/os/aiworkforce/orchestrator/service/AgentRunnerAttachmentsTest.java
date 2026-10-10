// @find: tests for agent runner attachments, agent run, attachments, files in a run, images, documents sent to agent, tool call
// @what: Unit and integration tests (2 cases) for agent runner attachments, for example: start gives files; rebuild gives same files.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.orchestrator.chat.AttachmentPrompt;
import os.aiworkforce.orchestrator.chat.ChatAttachments;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.voice.VoiceClipService;

/**
 * A run started from a chat message with files: the model is given the files' text under their
 * names, and pictures as image parts, in a turn of their own after the instruction; the run records
 * which files it read, and a run rebuilt after an approval is given the same turn again.
 */
class AgentRunnerAttachmentsTest {

    private static final String INSTRUCTION = "What does the report say about revenue?";

    private Runs runs;
    private RunSteps steps;
    private Agents agents;
    private AgentVersions versions;
    private ModelRouter router;
    private os.aiworkforce.orchestrator.repository.Tasks tasks;
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
    private ChatAttachments files;
    private ChatAttachments.Row pdf;
    private ChatAttachments.Row png;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        agents = mock(Agents.class);
        versions = mock(AgentVersions.class);
        router = mock(ModelRouter.class);
        tasks = mock(os.aiworkforce.orchestrator.repository.Tasks.class);
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
                tasks,
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

    private void withFiles() {
        files = mock(ChatAttachments.class);
        ReflectionTestUtils.setField(runner, "chatAttachments", files);
        UUID goalId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        Task task = new Task();
        task.setId(taskId);
        task.setOrgId(ORG);
        task.setGoalId(goalId);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        pdf = new ChatAttachments.Row(
                UUID.randomUUID(), ORG, UUID.randomUUID(), UUID.randomUUID(), goalId, "me", "q3.pdf", "application/pdf",
                "pdf", 4_096, "h", "ready", 2, null, null, null, Instant.now(), "Revenue rose eleven percent in Q3.");
        png = new ChatAttachments.Row(
                UUID.randomUUID(), ORG, UUID.randomUUID(), UUID.randomUUID(), goalId, "me", "chart.png", "image/png",
                "image", 3, "h", "ready", null, null, null, null, Instant.now(), null);
        when(files.forGoal(ORG, goalId)).thenReturn(List.of(pdf, png));
        when(files.withText(ORG, List.of(pdf.id(), png.id()))).thenReturn(List.of(pdf, png));
        when(files.content(ORG, png.id())).thenReturn(Optional.of(new byte[] {1, 2, 3}));
    }

    @Test
    @DisplayName("a new run is given its message's files after the instruction: text under names, pictures as images")
    void startGivesFiles() {
        withFiles();
        when(steps.highestPosition(any())).thenReturn(-1, 0, 1);
        when(router.route(any(), any(), any())).thenReturn(answer("Revenue rose 11% (q3.pdf)."));

        runner.start(ORG, agent.getId(), taskId, INSTRUCTION, "goal");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        assertThat(messages.get(1).content()).isEqualTo(INSTRUCTION);
        ChatMessage files = messages.get(2);
        assertThat(files.role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(files.content())
                .startsWith(AttachmentPrompt.HEADING)
                .contains("File 1: q3.pdf (PDF, 2 pages, 4 KB)")
                .contains("Revenue rose eleven percent in Q3.")
                .contains("File 2: chart.png (image");
        assertThat(files.images()).singleElement().satisfies(image -> {
            assertThat(image.name()).isEqualTo("chart.png");
            assertThat(image.dataUrl()).isEqualTo("data:image/png;base64,AQID");
        });

        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(step -> "attachments".equals(step.getDetail().get("type")))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getKind()).isEqualTo("note");
                    assertThat(step.getDetail().get("content").toString()).startsWith("Read 2 attached files: q3.pdf, chart.png.");
                    assertThat(step.getDetail().get("attachmentIds")).isEqualTo(List.of(pdf.id().toString(), png.id().toString()));
                });
    }

    @Test
    @DisplayName("a run rebuilt from its trace is given the same files again, read by id")
    void rebuildGivesSameFiles() {
        withFiles();
        UUID runId = UUID.randomUUID();
        os.aiworkforce.orchestrator.domain.Run run = new os.aiworkforce.orchestrator.domain.Run();
        run.setId(runId);
        run.setOrgId(ORG);
        run.setAgentId(agent.getId());
        run.setAgentVersionId(version.getId());
        run.setTaskId(taskId);
        run.setStatus("running");
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(ORG, runId, 1, "note", Map.of(
                        "type", "attachments",
                        "content", "Read 2 attached files.",
                        "attachmentIds", List.of(pdf.id().toString(), png.id().toString())))));

        List<ChatMessage> rebuilt = ReflectionTestUtils.invokeMethod(runner, "rebuildConversation", run, agent, version, false);

        assertThat(rebuilt).hasSize(3);
        assertThat(rebuilt.get(1).content()).isEqualTo(INSTRUCTION);
        assertThat(rebuilt.get(2).content()).contains("Revenue rose eleven percent in Q3.");
        assertThat(rebuilt.get(2).images()).hasSize(1);
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content, List.of(), FinishReason.STOP, TokenUsage.of(120, 30), "sandbox", "sandbox-echo",
                Duration.ofMillis(40), List.of(), Map.of());
    }
}
