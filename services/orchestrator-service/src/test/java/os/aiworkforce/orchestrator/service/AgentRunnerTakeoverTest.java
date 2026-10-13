// @find: tests for agent runner takeover, agent waits while a person has the browser, resumes on hand back, time not counted, step given back, hand back note in conversation
// @what: Unit tests for the run loop and a person taking over its web browser: no model or tool step while they hold it, the hand-back note before the next turn and kept until told, the time waited and a wasted turn given back (also after a park), the whole wait in the trace, reads made before the page changed read again, and holds ended when the run ends or stops.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.policy.ApprovalDecision;
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
import os.aiworkforce.orchestrator.takeover.RunTakeover;
import os.aiworkforce.orchestrator.takeover.RunTakeovers;
import os.aiworkforce.orchestrator.takeover.TakeoverActions;
import os.aiworkforce.orchestrator.takeover.TakeoverBrowser;
import os.aiworkforce.orchestrator.takeover.TakeoverService;
import os.aiworkforce.orchestrator.takeover.TakeoverTestKit;
import os.aiworkforce.orchestrator.voice.VoiceClipService;

/**
 * While a person holds a run's web browser the agent takes no step; when they hand it back, the
 * agent is told what they did before its next turn, and neither the time it waited nor a turn the
 * takeover made pointless counts against the run.
 */
class AgentRunnerTakeoverTest {

    private static final String INSTRUCTION = "Sign in to the supplier portal and download this month's invoice.";

    private Runs runs;
    private RunSteps steps;
    private ModelRouter router;
    private ToolGateway tools;
    private AgentRunner runner;
    private Agent agent;
    private AgentVersion version;

    private final AtomicReference<RunTakeover> takeover = new AtomicReference<>();
    private RunTakeovers takeovers;
    private ApprovalService approvals;
    private AuditClient takeoverAudit;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        router = mock(ModelRouter.class);
        tools = mock(ToolGateway.class);
        Agents agents = mock(Agents.class);
        AgentVersions versions = mock(AgentVersions.class);
        ToolCredentialResolver credentials = mock(ToolCredentialResolver.class);
        lenient().when(credentials.resolve(any(), any())).thenReturn(ToolCredentialResolver.NOT_CONNECTED);
        Usage usage = mock(Usage.class);
        when(usage.costForRun(any(), any())).thenReturn(BigDecimal.ZERO);
        VoiceClipService voiceClips = mock(VoiceClipService.class);
        QuestionService questions = mock(QuestionService.class);
        lenient().when(questions.policyFor(any())).thenReturn(new QuestionService.AskPolicy(true, false, null));
        os.aiworkforce.platform.observability.Redactor redactor = mock(os.aiworkforce.platform.observability.Redactor.class);
        lenient().when(redactor.text(any())).thenAnswer(call -> call.getArgument(0));
        approvals = mock(ApprovalService.class);
        takeoverAudit = mock(AuditClient.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        lenient().when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        runner = new AgentRunner(
                runs,
                steps,
                agents,
                versions,
                mock(ToolGrants.class),
                router,
                mock(ProviderRegistry.class),
                tools,
                mock(RoutingPolicyResolver.class),
                approvals,
                credentials,
                mock(AuditClient.class),
                usage,
                mock(TaskProgress.class),
                voiceClips,
                questions,
                new AskPersonTool(new ObjectMapper()),
                mock(LifecycleAnnouncer.class),
                mock(KnowledgeSearchTool.class),
                mock(os.aiworkforce.orchestrator.repository.Tasks.class),
                mock(os.aiworkforce.orchestrator.repository.Goals.class),
                mock(os.aiworkforce.orchestrator.schedule.Schedules.class),
                mock(os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup.class),
                redactor,
                transactions);

        agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey("finance");
        agent.setName("Finance");
        version = new AgentVersion();
        version.setId(UUID.randomUUID());
        version.setAgentId(agent.getId());
        version.setOrgId(ORG);
        version.setRevision(1);
        version.setSystemPrompt("You look after supplier invoices.");
        version.setMaxSteps(5);
        agent.setCurrentVersionId(version.getId());
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(steps.highestPosition(any())).thenReturn(-1);

        takeovers = mock(RunTakeovers.class);
        when(takeovers.findById(any())).thenAnswer(call -> Optional.ofNullable(takeover.get()));
        when(takeovers.save(any())).thenAnswer(call -> {
            takeover.set(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    /** A takeover service over the in-memory row, with the given clock and wait. */
    private TakeoverService takeoverService(Clock clock, TakeoverTestKit.Wait wait) {
        return TakeoverTestKit.service(
                takeovers,
                runs,
                mock(os.aiworkforce.orchestrator.repository.Tasks.class),
                mock(os.aiworkforce.orchestrator.repository.Goals.class),
                mock(TakeoverBrowser.class),
                takeoverAudit,
                clock,
                wait,
                Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("no model step is taken while a person holds the browser; the agent resumes when it is handed back, told what they did")
    void waitsThenResumesWithTheNote() {
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
        AtomicInteger pauses = new AtomicInteger();
        runner.useTakeovers(takeoverService(clock, pause -> {
            // The model is not asked anything while the person holds the browser.
            verifyNoInteractions(router);
            clock.advance(pause);
            if (pauses.incrementAndGet() == 2) {
                RunTakeover held = takeover.get();
                held.setActions(TakeoverActions.record(Map.of(), "type", "https://portal.example.com/sign-in", "Password"));
                held.setNote("Signed in for you.");
                held.close(RunTakeover.RELEASED, "u-person", clock.instant());
            }
        }));
        when(router.route(any(), any(), any())).thenReturn(answer("Downloaded the invoice."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(pauses.get()).isEqualTo(2);
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        List<ChatMessage> messages = request.getValue().messages();
        ChatMessage told = messages.getLast();
        assertThat(told.role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(told.content())
                .contains("a person took over the web browser and then handed it back to you")
                .contains("\"Password\"")
                .contains("Their note for you: \"Signed in for you.\"");
        assertThat(savedNotes()).extracting(step -> step.getDetail().get("type")).contains("taken_over", "handed_back");
        assertThat(savedNotes())
                .filteredOn(step -> "handed_back".equals(step.getDetail().get("type")))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getDetail().get("content")).isEqualTo(told.content());
                    assertThat((String) step.getDetail().get("summary")).contains("1 typing step");
                });
        assertThat(takeover.get().getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("the time a person holds the browser does not count against the run's time limit")
    void takeoverTimeIsNotCounted() {
        runner.setDefaultMaxRunDuration(Duration.ofMillis(1500));
        takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", Instant.now()));
        AtomicInteger pauses = new AtomicInteger();
        runner.useTakeovers(takeoverService(Clock.systemUTC(), pause -> {
            Thread.sleep(700);
            if (pauses.incrementAndGet() == 3) {
                takeover.get().close(RunTakeover.RELEASED, "u-person", Instant.now());
            }
        }));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        // Over two seconds waited against a limit of one and a half: without the time given back the
        // run would have been stopped at its time limit before the model was asked.
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.answer()).isEqualTo("Done.");
        verify(router, times(1)).route(any(), any(), any());
    }

    @Test
    @DisplayName("a person changing the page mid-turn leaves the rest of the turn not run, and the step it cost is given back")
    void staleTurnIsSkippedAndItsStepGivenBack() {
        version.setMaxSteps(2);
        TakeoverService service = mock(TakeoverService.class);
        AtomicInteger checks = new AtomicInteger();
        when(service.awaitHandBack(any(), any(), any(), any())).thenAnswer(call -> checks.incrementAndGet() == 2
                ? new TakeoverService.HandBack(true, Duration.ofMinutes(4), "A person used the browser.", "Handed back.", true)
                : TakeoverService.HandBack.NONE);
        runner.useTakeovers(service);
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("crm", "find_contact", "Finds.", "{}", ToolSpec.SideEffect.READ, List.of(), false, null, null)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Acme\"}", "Found Acme.", Duration.ZERO)));
        when(router.route(any(), any(), any())).thenReturn(
                toolCallAnswer("call_0", "crm.find_contact", "{}"),
                toolCallAnswer("call_1", "crm.find_contact", "{\"name\":\"Acme\"}"),
                answer("The invoice is downloaded."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(service).giveStepBack(any());
        verify(service).markTold(any());
        verify(tools, times(1)).invoke(any(), any(), any());
        assertThat(savedSteps("tool_call"))
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly(AgentRunner.TAKEOVER_NOT_RUN, "Found Acme.");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, times(3)).route(requests.capture(), any(), any());
        List<ChatMessage> second = requests.getAllValues().get(1).messages();
        assertThat(second.get(second.size() - 2).role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(second.get(second.size() - 2).content()).contains(AgentRunner.TAKEOVER_NOT_RUN);
        assertThat(second.getLast().role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(second.getLast().content()).isEqualTo("A person used the browser.");
    }

    @Test
    @DisplayName("the trace states the whole wait when a hand back mid-turn is read again before the agent is told")
    void wholeWaitIsTraced() {
        // Seen live on 13 Oct 2026: an eight minute takeover before a tool call was traced as "the
        // agent waited less than a minute", because the hand back was read a second time, with
        // nothing more waited, at the top of the loop before it was told.
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        runner.useTakeovers(takeoverService(clock, pause -> {
            clock.advance(Duration.ofMinutes(8));
            RunTakeover held = takeover.get();
            held.setActions(TakeoverActions.record(Map.of(), "navigate", "https://en.wikipedia.org/wiki/Charles_Babbage", null));
            held.close(RunTakeover.RELEASED, "u-person", clock.instant());
        }));
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("browser", "read_page", "Reads.", "{}", ToolSpec.SideEffect.READ, List.of(), false, null, null)));
        // The person asks for control while the model decides to read the page.
        when(router.route(any(), any(), any())).thenAnswer(call -> {
            if (takeover.get() == null) {
                takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
                return toolCallAnswer("call_0", "browser.read_page", "{\"from\":0}");
            }
            return answer("Read the page the person opened.");
        });

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(tools, times(0)).invoke(any(), any(), any());
        assertThat(savedNotes())
                .filteredOn(step -> "handed_back".equals(step.getDetail().get("type")))
                .singleElement()
                .satisfies(step -> assertThat((String) step.getDetail().get("summary"))
                        .contains("the agent waited 8 minutes")
                        .contains("1 address opened"));
    }

    @Test
    @DisplayName("a page read before a person changed the page is read for real after the hand back, not answered from memory")
    void readsBeforeATakeoverAreReadAgain() {
        // Seen live on 13 Oct 2026: after a person opened another article, the agent's read_page
        // was answered with the earlier article's text as a "repeated identical read".
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        runner.useTakeovers(takeoverService(clock, pause -> {
            clock.advance(pause);
            RunTakeover held = takeover.get();
            held.setActions(TakeoverActions.record(Map.of(), "navigate", "https://en.wikipedia.org/wiki/Charles_Babbage", null));
            held.close(RunTakeover.RELEASED, "u-person", clock.instant());
        }));
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("browser", "read_page", "Reads.", "{}", ToolSpec.SideEffect.READ, List.of(), false, null, null)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(ApprovalDecision.PROCEED);
        AtomicInteger reads = new AtomicInteger();
        when(tools.invoke(any(), any(), any())).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) {
                // The person asks for control while the first read is under way.
                takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
                return Mono.just(ToolResult.succeeded("{\"text\":\"Ada Lovelace was\"}", "Read A.", Duration.ZERO));
            }
            return Mono.just(ToolResult.succeeded("{\"text\":\"Charles Babbage was\"}", "Read B.", Duration.ZERO));
        });
        when(router.route(any(), any(), any())).thenReturn(
                toolCallAnswer("call_0", "browser.read_page", "{\"from\":0}"),
                toolCallAnswer("call_1", "browser.read_page", "{\"from\":0}"),
                answer("Charles Babbage was a mathematician."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        verify(tools, times(2)).invoke(any(), any(), any());
        assertThat(savedSteps("tool_call"))
                .extracting(step -> step.getDetail().get("summary"))
                .containsExactly("Read A.", "Read B.");
    }

    @Test
    @DisplayName("a run stopped while a person holds its browser steps back without asking the model again")
    void stoppedWhileHeld() {
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
        when(runs.findStatusById(any())).thenReturn(Optional.of("running"));
        runner.useTakeovers(takeoverService(clock, pause -> {
            clock.advance(pause);
            when(runs.findStatusById(any())).thenReturn(Optional.of("cancelled"));
        }));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("cancelled");
        verifyNoInteractions(router);
        assertThat(takeover.get().getStatus()).isEqualTo(RunTakeover.ENDED);
    }

    @Test
    @DisplayName("a rebuilt conversation carries the hand-back note where the agent was told it")
    void rebuildKeepsHandBack() {
        UUID runId = UUID.randomUUID();
        Run run = new Run();
        run.setId(runId);
        run.setOrgId(ORG);
        run.setAgentId(agent.getId());
        run.setAgentVersionId(version.getId());
        run.setStatus("running");
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByPosition(runId)).thenReturn(List.of(
                RunStep.of(ORG, runId, 0, "note", Map.of("type", "instruction", "content", INSTRUCTION)),
                RunStep.of(ORG, runId, 1, "note", Map.of("type", "taken_over", "summary", AgentRunner.TAKEN_OVER_NOTE)),
                RunStep.of(ORG, runId, 2, "note", Map.of(
                        "type", "handed_back", "summary", "Handed back.", "content", "A person signed in for you."))));
        when(router.route(any(), any(), any())).thenReturn(answer("Done."));

        runner.drive(ORG, runId);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).route(request.capture(), any(), any());
        assertThat(request.getValue().messages())
                .extracting(ChatMessage::role, ChatMessage::content)
                .endsWith(
                        org.assertj.core.groups.Tuple.tuple(ChatMessage.Role.USER, INSTRUCTION),
                        org.assertj.core.groups.Tuple.tuple(ChatMessage.Role.USER, "A person signed in for you."));
    }

    @Test
    @DisplayName("a hand-back note that arrives mid-turn is kept untold when the run parks first, so the next drive tells it")
    void midTurnNoteSurvivesAPark() {
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        runner.useTakeovers(takeoverService(clock, clock::advance));
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("crm", "add_note", "Adds a note.", "{}", ToolSpec.SideEffect.WRITE, List.of(), false, null, null),
                new ToolDefinition("gmail", "send_message", "Sends.", "{}", ToolSpec.SideEffect.OUTBOUND, List.of(), false, null, null)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(
                ApprovalDecision.PROCEED, new ApprovalDecision.AwaitApproval("Send the order", "approval:decide"));
        // While the first call runs, a person who had asked for control withdraws with a note.
        when(tools.invoke(any(), any(), any())).thenAnswer(call -> {
            RunTakeover row = RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant());
            row.setNote("Use purchase order 4471.");
            row.close(RunTakeover.RELEASED, "u-person", clock.instant());
            takeover.set(row);
            return Mono.just(ToolResult.succeeded("{\"id\":\"n1\"}", "Added a note.", Duration.ZERO));
        });
        os.aiworkforce.orchestrator.domain.Approval approval = new os.aiworkforce.orchestrator.domain.Approval();
        approval.setId(UUID.randomUUID());
        when(approvals.raise(any(), any(), any(), any(), any(), any())).thenReturn(approval);
        when(router.route(any(), any(), any())).thenReturn(new ChatResponse(
                "", List.of(new ToolCall("call_0", "crm.add_note", "{}"), new ToolCall("call_1", "gmail.send_message", "{}")),
                FinishReason.TOOL_CALLS, TokenUsage.of(120, 30), "sandbox", "sandbox-echo", Duration.ofMillis(40), List.of(),
                Map.of()));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("waiting_approval");
        assertThat(savedNotes()).noneMatch(step -> "handed_back".equals(step.getDetail().get("type")));
        assertThat(takeover.get().getDeliveredAt()).isNull();
        assertThat(takeover.get().getNote()).isEqualTo("Use purchase order 4471.");
        assertThat(takeoverService(clock, clock::advance).awaitHandBack(UUID.randomUUID(), () -> "running", () -> {}).note())
                .contains("Their note for you: \"Use purchase order 4471.\"");
    }

    @Test
    @DisplayName("a run that finishes while a person is getting control ends the request, audited, instead of leaving it open")
    void finishedWhileGettingControl() {
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        runner.useTakeovers(takeoverService(clock, clock::advance));
        // The person asks for control while the model is answering; the answer is final.
        when(router.route(any(), any(), any())).thenAnswer(call -> {
            takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
            return answer("Downloaded the invoice.");
        });

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(takeover.get().getStatus()).isEqualTo(RunTakeover.ENDED);
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(takeoverAudit).record(eq(ORG), any(), eq("run.takeover.release"), eq("run"), any(), eq("succeeded"),
                audited.capture());
        assertThat(audited.getValue()).containsEntry("reason", "run ended");
    }

    @Test
    @DisplayName("a run stopped during a model call while a person is getting control ends that request when the loop leaves")
    void stoppedDuringAModelCall() {
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        runner.useTakeovers(takeoverService(clock, clock::advance));
        when(runs.findStatusById(any())).thenReturn(Optional.of("running"));
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("crm", "find_contact", "Finds.", "{}", ToolSpec.SideEffect.READ, List.of(), false, null, null)));
        when(router.route(any(), any(), any())).thenAnswer(call -> {
            takeover.set(RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant()));
            when(runs.findStatusById(any())).thenReturn(Optional.of("cancelled"));
            return toolCallAnswer("call_0", "crm.find_contact", "{}");
        });

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        assertThat(outcome.status()).isEqualTo("cancelled");
        verify(tools, times(0)).invoke(any(), any(), any());
        assertThat(takeover.get().getStatus()).isEqualTo(RunTakeover.ENDED);
        verify(takeoverAudit).record(eq(ORG), any(), eq("run.takeover.release"), eq("run"), any(), eq("succeeded"), anyMap());
    }

    @Test
    @DisplayName("steps given back by an earlier takeover still count when the loop starts again, as after a park")
    void stepsGivenBackSurviveANewLoop() {
        version.setMaxSteps(1);
        TakeoverTestKit.MovableClock clock = new TakeoverTestKit.MovableClock(Instant.parse("2026-10-12T09:00:00Z"));
        RunTakeover earlier = RunTakeover.start(UUID.randomUUID(), ORG, "u-person", clock.instant());
        earlier.giveStepBack();
        earlier.close(RunTakeover.RELEASED, "u-person", clock.instant());
        earlier.setDeliveredAt(clock.instant());
        takeover.set(earlier);
        runner.useTakeovers(takeoverService(clock, clock::advance));
        when(tools.availableTools(any())).thenReturn(List.of(
                new ToolDefinition("crm", "find_contact", "Finds.", "{}", ToolSpec.SideEffect.READ, List.of(), false, null, null)));
        when(tools.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(ApprovalDecision.PROCEED);
        when(tools.invoke(any(), any(), any()))
                .thenReturn(Mono.just(ToolResult.succeeded("{\"name\":\"Acme\"}", "Found Acme.", Duration.ZERO)));
        when(router.route(any(), any(), any())).thenReturn(
                toolCallAnswer("call_0", "crm.find_contact", "{}"), answer("Acme is the supplier."));

        AgentRunner.Outcome outcome = runner.start(ORG, agent.getId(), null, INSTRUCTION, "manual");

        // With one step and none given back, the tool call would have been the last step and the run would fail.
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.answer()).isEqualTo("Acme is the supplier.");
        verify(tools, times(1)).invoke(any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private List<RunStep> savedNotes() {
        return savedSteps("note");
    }

    private List<RunStep> savedSteps(String kind) {
        ArgumentCaptor<RunStep> saved = ArgumentCaptor.forClass(RunStep.class);
        verify(steps, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream().filter(step -> kind.equals(step.getKind())).toList();
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content, List.of(), FinishReason.STOP, TokenUsage.of(120, 30), "sandbox", "sandbox-echo",
                Duration.ofMillis(40), List.of(), Map.of());
    }

    private static ChatResponse toolCallAnswer(String callId, String toolName, String argumentsJson) {
        return new ChatResponse(
                "", List.of(new ToolCall(callId, toolName, argumentsJson)), FinishReason.TOOL_CALLS,
                TokenUsage.of(120, 30), "sandbox", "sandbox-echo", Duration.ofMillis(40), List.of(), Map.of());
    }
}
