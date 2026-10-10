// @find: tests for board service, board, ready task reason, waiting on earlier task reason, agent paused reason, held task reads agent paused, queue looks up missing goals in one batch, an old waiting goal is not pushed off the board by newer finished ones, active goals are filtered to the workspace, task cost adds up every run, BoardServiceTest, BoardService
// @what: Tests for BoardService in the orchestrator board package (23 test methods).
// @flow: Exercises BoardService
package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.schedule.Schedule;
import os.aiworkforce.orchestrator.schedule.ScheduleService;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;

class BoardServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Actor ACTOR =
            Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("run:read", "approval:decide"), 0L);

    private Goals goals;
    private Tasks tasks;
    private Runs runs;
    private Agents agents;
    private Usage usage;
    private ApprovalService approvals;
    private GoalService goalService;
    private QuestionService questions;
    private ScheduleService schedules;
    private WorkspaceZoneLookup zones;
    private BoardService board;

    @BeforeEach
    void setUp() {
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        agents = mock(Agents.class);
        usage = mock(Usage.class);
        approvals = mock(ApprovalService.class);
        goalService = mock(GoalService.class);
        questions = mock(QuestionService.class);
        schedules = mock(ScheduleService.class);
        zones = mock(WorkspaceZoneLookup.class);
        board = new BoardService(
                goals, tasks, runs, agents, usage, approvals, goalService, questions, schedules, zones);

        when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(usage.spendSince(eq(ORG), any())).thenReturn(BigDecimal.TEN);
        lenient().when(runs.findRecent(eq(ORG), any())).thenReturn(List.of());
        lenient().when(runs.findFirstByTaskIdOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        lenient().when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of());
        lenient()
                .when(runs.findByOrgIdAndTaskIdIsNullAndStatusIn(eq(ORG), any()))
                .thenReturn(List.of());
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
        lenient().when(goals.findRecent(eq(ORG), any())).thenReturn(List.of());
        lenient().when(goals.activeIds(ORG)).thenReturn(List.of());
        lenient()
                .when(goals.findFinishedSince(eq(ORG), anyString(), any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(goals.countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(eq(ORG), anyString(), any()))
                .thenReturn(0L);
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of());
        lenient().when(tasks.findQueued(eq(ORG), any())).thenReturn(List.of());
        lenient().when(approvals.pending(ORG)).thenReturn(List.of());
        lenient().when(questions.pending(eq(ORG), anyInt())).thenReturn(List.of());
        lenient().when(questions.views(any(), any())).thenReturn(List.of());
        lenient().when(schedules.list(ORG)).thenReturn(List.of());
    }

    private static Goal goal(String status) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("A goal");
        goal.setStatus(status);
        return goal;
    }

    private static Task task(Goal goal, int position, String status, UUID agentId) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setAgentId(agentId);
        task.setTitle("Task " + position);
        task.setPosition(position);
        task.setStatus(status);
        return task;
    }

    private static Agent agent(String status) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey("agent-" + agent.getId());
        agent.setName("Agent");
        agent.setStatus(status);
        return agent;
    }

    private void stubGoalAndTasks(Goal g, List<Task> taskList) {
        when(goals.findRecent(eq(ORG), any())).thenReturn(List.of(g));
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(taskList);
    }

    private void stubQueued(List<Task> queued) {
        when(tasks.findQueued(eq(ORG), any())).thenReturn(queued);
    }

    private static Optional<BoardService.QueueEntry> queueEntryFor(BoardService.Board board, UUID taskId) {
        return board.queue().stream()
                .filter(entry -> entry.taskId().equals(taskId))
                .findFirst();
    }

    // ---- Reading the board -------------------------------------------------------------------

    // @find: test ready task reason, board service
    @Test
    @DisplayName("a pending task with a completed predecessor is ready")
    void readyTaskReason() {
        Goal g = goal("running");
        Task done = task(g, 0, "completed", null);
        Task ready = task(g, 1, "pending", null);
        stubGoalAndTasks(g, List.of(done, ready));
        stubQueued(List.of(ready));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.queue()).hasSize(1);
        assertThat(result.queue().getFirst().reason()).isEqualTo("ready");
        assertThat(result.queue().getFirst().goalId()).isEqualTo(g.getId());
        assertThat(result.stats().held()).isZero();
    }

    // @find: test waiting on earlier task reason, board service
    @Test
    @DisplayName("a task depending on an unfinished task waits on it")
    void waitingOnEarlierTaskReason() {
        Goal g = goal("running");
        Task predecessor = task(g, 0, "pending", null);
        Task next = task(g, 1, "pending", null);
        next.setDependsOn(List.of(predecessor.getId()));
        stubGoalAndTasks(g, List.of(predecessor, next));
        stubQueued(List.of(next));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        queueEntryFor(result, next.getId())
                .ifPresentOrElse(
                        entry -> assertThat(entry.reason()).isEqualTo("waiting_on_earlier_task"),
                        () -> org.junit.jupiter.api.Assertions.fail("expected task in queue"));
    }

    // @find: test agent paused reason, board service
    @Test
    @DisplayName("a task whose agent is paused is held, whatever its dependencies")
    void agentPausedReason() {
        Agent paused = agent("paused");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(paused));

        Goal g = goal("running");
        Task held = task(g, 0, "pending", paused.getId());
        stubGoalAndTasks(g, List.of(held));
        stubQueued(List.of(held));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.queue().getFirst().reason()).isEqualTo("agent_paused");
        assertThat(result.stats().held()).isEqualTo(1);
        assertThat(result.stats().queued()).isEqualTo(1);
    }

    // @find: test held task reads agent paused, board service
    @Test
    @DisplayName("only the task behind a paused agent is held, among several queued tasks")
    void heldTaskReadsAgentPaused() {
        Agent paused = agent("paused");
        Agent active = agent("active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(paused, active));

        // Both at position 0, so neither waits on the other: only the paused agent holds one back.
        Goal g = goal("running");
        Task held = task(g, 0, "pending", paused.getId());
        Task ready = task(g, 0, "pending", active.getId());
        stubGoalAndTasks(g, List.of(held, ready));
        stubQueued(List.of(held, ready));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.stats().queued()).isEqualTo(2);
        assertThat(result.stats().held()).isEqualTo(1);
        assertThat(queueEntryFor(result, held.getId()).orElseThrow().reason()).isEqualTo("agent_paused");
        assertThat(queueEntryFor(result, ready.getId()).orElseThrow().reason()).isEqualTo("ready");
    }

    // @find: test queue looks up missing goals in one batch, board service
    @Test
    @DisplayName("queued tasks whose goals were not read with the board are looked up in one batch, not one by one")
    void queueLooksUpMissingGoalsInOneBatch() {
        Goal inWindow = goal("running");
        Task fetched = task(inWindow, 0, "pending", null);
        stubGoalAndTasks(inWindow, List.of(fetched));

        Goal firstOutside = goal("running");
        Task firstNotFetched = task(firstOutside, 0, "pending", null);
        Goal secondOutside = goal("running");
        Task secondNotFetched = task(secondOutside, 0, "pending", null);
        when(goals.findAllById(Set.of(firstOutside.getId(), secondOutside.getId())))
                .thenReturn(List.of(firstOutside, secondOutside));
        when(tasks.findByGoalIdInOrderByPositionAsc(Set.of(firstOutside.getId(), secondOutside.getId())))
                .thenReturn(List.of(firstNotFetched, secondNotFetched));

        stubQueued(List.of(fetched, firstNotFetched, secondNotFetched));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.queue()).hasSize(3);
        verify(goals, times(1)).findAllById(Set.of(firstOutside.getId(), secondOutside.getId()));
        verify(goals, never()).findById(any());
        verify(tasks, never()).findByGoalIdOrderByPosition(any());
    }

    // @find: test an old waiting goal is not pushed off the board by newer finished ones, board service
    @Test
    @DisplayName("a goal waiting for approval stays on the board however many newer goals have finished")
    void anOldWaitingGoalIsNotPushedOffTheBoardByNewerFinishedOnes() {
        Agent agent = agent("active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));

        // The newest 200 goals are all finished; the one still waiting on a person is older than them.
        List<Goal> newerFinished = Stream.generate(() -> goal("completed"))
                .limit(250)
                .peek(finished -> {
                    finished.setCompletedAt(Instant.now());
                    ReflectionTestUtils.setField(finished, "createdAt", Instant.now());
                })
                .toList();
        when(goals.findRecent(eq(ORG), any())).thenReturn(newerFinished.subList(0, 200));

        Goal waiting = goal("running");
        ReflectionTestUtils.setField(waiting, "createdAt", Instant.now().minus(3, ChronoUnit.DAYS));
        Task parked = task(waiting, 0, "waiting_approval", agent.getId());
        when(goals.activeIds(ORG)).thenReturn(List.of(waiting.getId()));
        when(goals.findAllById(List.of(waiting.getId()))).thenReturn(List.of(waiting));
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of(parked));
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(parked.getId());
        run.setStatus("waiting_approval");
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(run));

        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(run.getId());
        approval.setTaskId(parked.getId());
        approval.setAgentId(agent.getId());
        approval.setTool("email.send");
        approval.setActionClass("OUTBOUND");
        approval.setSummary("Send the email");
        approval.setRequestedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        when(approvals.pending(ORG)).thenReturn(List.of(approval));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.goals()).extracting(BoardService.GoalView::id).contains(waiting.getId());
        assertThat(result.stats().waitingApproval()).isEqualTo(1);
        BoardService.AgentSummary summary = result.agents().stream()
                .filter(a -> a.id().equals(agent.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.waitingRunIds()).containsExactly(run.getId());
        // The approval in the inbox and the card it belongs to now agree.
        assertThat(result.approvals())
                .extracting(BoardService.ApprovalSummary::goalId)
                .containsExactly(waiting.getId());
        // And no goal was looked up one at a time to get there.
        verify(goals, never()).findById(any());
    }

    // @find: test active goals are filtered to the workspace, board service
    @Test
    @DisplayName("a goal from another workspace is never added to the board as an active goal")
    void activeGoalsAreFilteredToTheWorkspace() {
        Goal foreign = goal("running");
        foreign.setOrgId(UUID.randomUUID());
        when(goals.activeIds(ORG)).thenReturn(List.of(foreign.getId()));
        when(goals.findAllById(List.of(foreign.getId()))).thenReturn(List.of(foreign));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.goals()).isEmpty();
    }

    // @find: test task cost adds up every run, board service
    @Test
    @DisplayName("a task's cost is what every one of its runs cost, and its attempts are the runs it has had")
    void taskCostAddsUpEveryRun() {
        Goal g = goal("running");
        Task retried = task(g, 0, "running", null);
        stubGoalAndTasks(g, List.of(retried));

        Run first = new Run();
        first.setId(UUID.randomUUID());
        first.setOrgId(ORG);
        first.setTaskId(retried.getId());
        first.setStatus("failed");
        first.setTotalCost(new BigDecimal("0.40"));
        first.setStepCount(7);
        Run second = new Run();
        second.setId(UUID.randomUUID());
        second.setOrgId(ORG);
        second.setTaskId(retried.getId());
        second.setStatus("running");
        second.setTotalCost(new BigDecimal("0.15"));
        second.setStepCount(2);
        // Most recent first, as the repository orders them.
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(second, first));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        BoardService.TaskView view = result.goals().getFirst().tasks().getFirst();
        assertThat(view.cost()).isEqualByComparingTo("0.55");
        assertThat(view.attempts()).isEqualTo(2);
        assertThat(view.runId()).isEqualTo(second.getId());
        assertThat(view.stepCount()).isEqualTo(2);
    }

    // @find: test board reads lists rather than pages, board service
    @Test
    @DisplayName("the board's goal and timeline reads are plain lists, which never add a count query")
    void boardReadsListsRatherThanPages() {
        board.board(ORG, BoardService.Window.H2, ACTOR);

        verify(goals).findRecent(eq(ORG), any());
        verify(runs).findRecent(eq(ORG), any());
        verify(runs, never()).findByOrgIdOrderByStartedAtDesc(any(), any());
    }

    // @find: test direct runs count and light their agent, board service
    @Test
    @DisplayName("a direct run with no task counts toward its agent and toward stats.directRuns")
    void directRunsCountAndLightTheirAgent() {
        Agent agent = agent("active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));
        Run direct = new Run();
        direct.setId(UUID.randomUUID());
        direct.setOrgId(ORG);
        direct.setTaskId(null);
        direct.setAgentId(agent.getId());
        direct.setStatus("running");
        when(runs.findByOrgIdAndTaskIdIsNullAndStatusIn(eq(ORG), any())).thenReturn(List.of(direct));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.stats().directRuns()).isEqualTo(1);
        assertThat(result.stats().running()).isEqualTo(1);
        BoardService.AgentSummary summary = result.agents().stream()
                .filter(a -> a.id().equals(agent.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.runningRunIds()).containsExactly(direct.getId());
        assertThat(summary.fallback()).isFalse();
    }

    // @find: test waiting input counts and asks, board service
    @Test
    @DisplayName("a task waiting for an answer counts toward stats.waitingInput and the agent's askingRunIds")
    void waitingInputCountsAndAsks() {
        Agent agent = agent("active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));
        Goal g = goal("running");
        Task asking = task(g, 0, "waiting_input", agent.getId());
        stubGoalAndTasks(g, List.of(asking));

        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(asking.getId());
        run.setStatus("waiting_input");
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(run));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.stats().waitingInput()).isEqualTo(1);
        BoardService.AgentSummary summary = result.agents().stream()
                .filter(a -> a.id().equals(agent.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.askingRunIds()).containsExactly(run.getId());
    }

    // @find: test questions and approvals included, board service
    @Test
    @DisplayName("the board carries pending questions and approvals for the caller")
    void questionsAndApprovalsIncluded() {
        QuestionService.QuestionView questionView = new QuestionService.QuestionView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                UUID.randomUUID(),
                null,
                "pending",
                List.of(),
                null,
                null,
                null,
                null,
                UUID.randomUUID(),
                Instant.now(),
                Instant.now().plusSeconds(3600),
                null,
                true,
                true,
                "waiting_input");
        when(questions.views(any(), eq(ACTOR))).thenReturn(List.of(questionView));

        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setTool("email.send");
        approval.setActionClass("OUTBOUND");
        approval.setSummary("Send the email");
        approval.setRequestedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        when(approvals.pending(ORG)).thenReturn(List.of(approval));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.questions()).containsExactly(questionView);
        assertThat(result.approvals()).hasSize(1);
        assertThat(result.approvals().getFirst().id()).isEqualTo(approval.getId());
    }

    // @find: test approvals carry requester and can decide, board service
    @Test
    @DisplayName("an approval carries its goal's requester, and whether the caller can decide it")
    void approvalsCarryRequesterAndCanDecide() {
        Agent agent = agent("active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));
        UUID requester = UUID.randomUUID();
        Goal g = goal("running");
        g.setRequestedBy(requester);
        Task t = task(g, 0, "waiting_approval", agent.getId());
        stubGoalAndTasks(g, List.of(t));

        Approval decidable = new Approval();
        decidable.setId(UUID.randomUUID());
        decidable.setOrgId(ORG);
        decidable.setRunId(UUID.randomUUID());
        decidable.setTaskId(t.getId());
        decidable.setAgentId(agent.getId());
        decidable.setTool("email.send");
        decidable.setActionClass("OUTBOUND");
        decidable.setSummary("Send the email");
        decidable.setRequestedAt(Instant.now());
        decidable.setExpiresAt(Instant.now().plusSeconds(3600));
        // requiredPermission defaults to "approval:decide", which ACTOR holds.

        Approval notDecidable = new Approval();
        notDecidable.setId(UUID.randomUUID());
        notDecidable.setOrgId(ORG);
        notDecidable.setRunId(UUID.randomUUID());
        notDecidable.setTaskId(t.getId());
        notDecidable.setAgentId(agent.getId());
        notDecidable.setTool("payment.send");
        notDecidable.setActionClass("PAYMENT");
        notDecidable.setSummary("Send the payment");
        notDecidable.setRequiredPermission("payment:approve");
        notDecidable.setRequestedAt(Instant.now());
        notDecidable.setExpiresAt(Instant.now().plusSeconds(3600));

        when(approvals.pending(ORG)).thenReturn(List.of(decidable, notDecidable));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        BoardService.ApprovalSummary decidedSummary = result.approvals().stream()
                .filter(a -> a.id().equals(decidable.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(decidedSummary.requestedBy()).isEqualTo(requester);
        assertThat(decidedSummary.canDecide()).isTrue();

        BoardService.ApprovalSummary undecidedSummary = result.approvals().stream()
                .filter(a -> a.id().equals(notDecidable.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(undecidedSummary.canDecide()).isFalse();
    }

    // @find: test window widens goals and timeline, board service
    @Test
    @DisplayName("a wider window fetches more goals and reaches further back for the timeline")
    void windowWidensGoalsAndTimeline() {
        ArgumentCaptor<Pageable> goalPageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> runPageable = ArgumentCaptor.forClass(Pageable.class);

        board.board(ORG, BoardService.Window.H2, ACTOR);
        board.board(ORG, BoardService.Window.H24, ACTOR);

        verify(goals, times(2)).findRecent(eq(ORG), goalPageable.capture());
        verify(runs, times(2)).findRecent(eq(ORG), runPageable.capture());

        assertThat(goalPageable.getAllValues().stream()
                        .map(Pageable::getPageSize)
                        .toList())
                .containsExactly(200, 500);
        assertThat(runPageable.getAllValues().stream()
                        .map(Pageable::getPageSize)
                        .toList())
                .containsExactly(300, 1000);
    }

    // @find: test today window starts at local midnight, board service
    @Test
    @DisplayName("the today window starts at local midnight in the workspace's own timezone")
    void todayWindowStartsAtLocalMidnight() {
        ArgumentCaptor<Instant> midnightCaptor = ArgumentCaptor.forClass(Instant.class);

        BoardService.Board result = board.board(ORG, BoardService.Window.TODAY, ACTOR);

        verify(usage).spendSince(eq(ORG), midnightCaptor.capture());
        Instant expectedMidnight = Instant.now()
                .atZone(ZoneId.of("Australia/Melbourne"))
                .toLocalDate()
                .atStartOfDay(ZoneId.of("Australia/Melbourne"))
                .toInstant();
        assertThat(midnightCaptor.getValue()).isCloseTo(expectedMidnight, within(2, ChronoUnit.SECONDS));
        assertThat(result.window()).isEqualTo("TODAY");
        assertThat(result.windowMinutes()).isGreaterThanOrEqualTo(1);
    }

    // @find: test failed today ignores the window, board service
    @Test
    @DisplayName("a goal that failed today shows even when it falls outside the window")
    void failedTodayIgnoresTheWindow() {
        Goal old = goal("failed");
        // Three hours ago: after midnight, but before the one-hour window starts.
        old.setCompletedAt(Instant.now().minus(3, ChronoUnit.HOURS));
        when(goals.findFinishedSince(eq(ORG), eq("failed"), any(), any())).thenReturn(List.of(old));

        BoardService.Board result = board.board(ORG, BoardService.Window.H1, ACTOR);

        assertThat(result.failedToday()).extracting(BoardService.GoalView::id).containsExactly(old.getId());
        assertThat(result.goals()).isEmpty();
    }

    // @find: test goal counts for today come from goals, board service
    @Test
    @DisplayName("the today tiles count goals, read straight from the repository")
    void goalCountsForTodayComeFromGoals() {
        when(goals.countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(eq(ORG), eq("completed"), any()))
                .thenReturn(5L);
        when(goals.countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(eq(ORG), eq("failed"), any()))
                .thenReturn(3L);

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.stats().goalsCompletedToday()).isEqualTo(5);
        assertThat(result.stats().goalsFailedToday()).isEqualTo(3);
    }

    // ---- Stopping everything -------------------------------------------------------------------

    // @find: test stop all cancels goals and orphan runs, board service
    @Test
    @DisplayName("stopping everything cancels every active goal and any run left over with no goal")
    void stopAllCancelsGoalsAndOrphanRuns() {
        UUID goalId = UUID.randomUUID();
        when(goals.activeIds(ORG)).thenReturn(List.of(goalId));
        when(goalService.cancelIfActive(eq(ORG), eq(goalId), anyString()))
                .thenReturn(Optional.of(new GoalService.CancelCounts(1, 0, 1, 0)));

        Run orphan = new Run();
        orphan.setId(UUID.randomUUID());
        orphan.setOrgId(ORG);
        orphan.setTaskId(null);
        orphan.setStatus("running");
        when(runs.findByOrgIdAndTaskIdIsNullAndStatusIn(eq(ORG), any())).thenReturn(List.of(orphan));
        when(goalService.stopRunIfActive(eq(ORG), eq(orphan.getId()), anyString()))
                .thenReturn(Optional.of(new GoalService.CancelCounts(0, 1, 0, 0)));

        BoardService.StopAllResult result = board.stopAll(ORG, false, ACTOR);

        assertThat(result.runsCancelled()).isEqualTo(1);
        assertThat(result.tasksCancelled()).isEqualTo(1);
        assertThat(result.approvalsWithdrawn()).isEqualTo(1);
        assertThat(result.questionsWithdrawn()).isZero();
        assertThat(result.schedulesPaused()).isZero();
        assertThat(result.goalsSkipped()).isZero();
        assertThat(result.runsSkipped()).isZero();
    }

    // @find: test stop all reads every active goal, board service
    @Test
    @DisplayName("stopping everything reads every active goal, not only the first page")
    void stopAllReadsEveryActiveGoal() {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(250).toList();
        when(goals.activeIds(ORG)).thenReturn(ids);
        when(goalService.cancelIfActive(eq(ORG), any(), anyString()))
                .thenReturn(Optional.of(new GoalService.CancelCounts(0, 0, 0, 0)));

        board.stopAll(ORG, false, ACTOR);

        verify(goalService, times(250)).cancelIfActive(eq(ORG), any(), anyString());
    }

    // @find: test stop all skips agoal that finished meanwhile, board service
    @Test
    @DisplayName("a goal that finished on its own is skipped, not treated as an error")
    void stopAllSkipsAGoalThatFinishedMeanwhile() {
        UUID goalId = UUID.randomUUID();
        when(goals.activeIds(ORG)).thenReturn(List.of(goalId));
        when(goalService.cancelIfActive(eq(ORG), eq(goalId), anyString())).thenReturn(Optional.empty());

        BoardService.StopAllResult result = board.stopAll(ORG, false, ACTOR);

        assertThat(result.goalsSkipped()).isEqualTo(1);
        assertThat(result.runsCancelled()).isZero();
    }

    // @find: test stop all counts questions, board service
    @Test
    @DisplayName("stopping everything totals the questions withdrawn alongside approvals")
    void stopAllCountsQuestions() {
        UUID goalId = UUID.randomUUID();
        when(goals.activeIds(ORG)).thenReturn(List.of(goalId));
        when(goalService.cancelIfActive(eq(ORG), eq(goalId), anyString()))
                .thenReturn(Optional.of(new GoalService.CancelCounts(1, 1, 1, 2)));

        BoardService.StopAllResult result = board.stopAll(ORG, false, ACTOR);

        assertThat(result.questionsWithdrawn()).isEqualTo(2);
        assertThat(result.approvalsWithdrawn()).isEqualTo(1);
    }

    // @find: test stop all pauses schedules first, board service
    @Test
    @DisplayName("stopping everything pauses schedules before it cancels any goal")
    void stopAllPausesSchedulesFirst() {
        Actor withTaskCreate =
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:create"), 0L);
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setOrgId(ORG);
        schedule.setEnabled(true);
        when(schedules.list(ORG)).thenReturn(List.of(schedule));

        UUID goalId = UUID.randomUUID();
        when(goals.activeIds(ORG)).thenReturn(List.of(goalId));
        when(goalService.cancelIfActive(eq(ORG), eq(goalId), anyString()))
                .thenReturn(Optional.of(new GoalService.CancelCounts(0, 0, 0, 0)));

        board.stopAll(ORG, true, withTaskCreate);

        InOrder order = inOrder(schedules, goalService);
        order.verify(schedules).pauseInternal(eq(ORG), eq(schedule.getId()), eq(ScheduleService.STOPPED_EVERYTHING_REASON));
        order.verify(goalService).cancelIfActive(eq(ORG), eq(goalId), anyString());
    }

    // @find: test stop all pause schedules needs task create, board service
    @Test
    @DisplayName("pausing schedules needs permission to create work, and refusing it stops nothing")
    void stopAllPauseSchedulesNeedsTaskCreate() {
        ApiException thrown = catchThrowableOfType(() -> board.stopAll(ORG, true, ACTOR), ApiException.class);

        assertThat(thrown.details()).containsEntry("requiredPermission", Permission.Codes.TASK_CREATE);
        verifyNoInteractions(goalService);
        verify(schedules, never()).pauseInternal(any(), any(), any());
    }
}
