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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
        lenient().when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any())).thenReturn(new PageImpl<>(List.of()));
        lenient().when(runs.findFirstByTaskIdOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        lenient().when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of());
        lenient()
                .when(runs.findByOrgIdAndTaskIdIsNullAndStatusIn(eq(ORG), any()))
                .thenReturn(List.of());
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
        lenient().when(goals.findByOrgIdOrderByCreatedAtDesc(eq(ORG), any())).thenReturn(new PageImpl<>(List.of()));
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
        when(goals.findByOrgIdOrderByCreatedAtDesc(eq(ORG), any())).thenReturn(new PageImpl<>(List.of(g)));
        when(tasks.findByGoalIdOrderByPosition(g.getId())).thenReturn(taskList);
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(taskList);
        lenient().when(goals.findById(g.getId())).thenReturn(Optional.of(g));
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

    @Test
    @DisplayName("the siblings fallback query runs only for a goal whose tasks were not already fetched")
    void siblingsFallbackQueriesOnlyWhenMissing() {
        Goal inWindow = goal("running");
        Task fetched = task(inWindow, 0, "pending", null);
        stubGoalAndTasks(inWindow, List.of(fetched));

        Goal outsideWindow = goal("running");
        Task notFetched = task(outsideWindow, 0, "pending", null);
        when(goals.findById(outsideWindow.getId())).thenReturn(Optional.of(outsideWindow));
        when(tasks.findByGoalIdOrderByPosition(outsideWindow.getId())).thenReturn(List.of(notFetched));

        stubQueued(List.of(fetched, notFetched));

        BoardService.Board result = board.board(ORG, BoardService.Window.H2, ACTOR);

        assertThat(result.queue()).hasSize(2);
        verify(tasks, times(1)).findByGoalIdOrderByPosition(outsideWindow.getId());
        verify(tasks, never()).findByGoalIdOrderByPosition(inWindow.getId());
    }

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

    @Test
    @DisplayName("a wider window fetches more goals and reaches further back for the timeline")
    void windowWidensGoalsAndTimeline() {
        ArgumentCaptor<Pageable> goalPageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> runPageable = ArgumentCaptor.forClass(Pageable.class);

        board.board(ORG, BoardService.Window.H2, ACTOR);
        board.board(ORG, BoardService.Window.H24, ACTOR);

        verify(goals, times(2)).findByOrgIdOrderByCreatedAtDesc(eq(ORG), goalPageable.capture());
        verify(runs, times(2)).findByOrgIdOrderByStartedAtDesc(eq(ORG), runPageable.capture());

        assertThat(goalPageable.getAllValues().stream()
                        .map(Pageable::getPageSize)
                        .toList())
                .containsExactly(200, 500);
        assertThat(runPageable.getAllValues().stream()
                        .map(Pageable::getPageSize)
                        .toList())
                .containsExactly(300, 1000);
    }

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

    @Test
    @DisplayName("a goal that failed today shows even when it falls outside the window")
    void failedTodayIgnoresTheWindow() {
        Goal old = goal("failed");
        old.setCompletedAt(Instant.now());
        when(goals.findFinishedSince(eq(ORG), eq("failed"), any(), any())).thenReturn(List.of(old));
        when(goals.findByOrgIdOrderByCreatedAtDesc(eq(ORG), any())).thenReturn(new PageImpl<>(List.of()));

        BoardService.Board result = board.board(ORG, BoardService.Window.H1, ACTOR);

        assertThat(result.failedToday()).extracting(BoardService.GoalView::id).containsExactly(old.getId());
        assertThat(result.goals()).isEmpty();
    }

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
        order.verify(schedules).pause(ORG, schedule.getId());
        order.verify(goalService).cancelIfActive(eq(ORG), eq(goalId), anyString());
    }

    @Test
    @DisplayName("pausing schedules needs permission to create work, and refusing it stops nothing")
    void stopAllPauseSchedulesNeedsTaskCreate() {
        ApiException thrown = catchThrowableOfType(() -> board.stopAll(ORG, true, ACTOR), ApiException.class);

        assertThat(thrown.details()).containsEntry("requiredPermission", Permission.Codes.TASK_CREATE);
        verifyNoInteractions(goalService);
        verify(schedules, never()).pause(any(), any());
    }
}
