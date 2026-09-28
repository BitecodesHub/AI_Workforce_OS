package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

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
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.GoalService;

class BoardServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private Goals goals;
    private Tasks tasks;
    private Runs runs;
    private Agents agents;
    private Usage usage;
    private ApprovalService approvals;
    private GoalService goalService;
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
        zones = mock(WorkspaceZoneLookup.class);
        board = new BoardService(goals, tasks, runs, agents, usage, approvals, goalService, zones);

        when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(usage.spendSince(eq(ORG), any())).thenReturn(BigDecimal.TEN);
        lenient().when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any()))
                .thenReturn(new PageImpl<>(List.of()));
        lenient().when(runs.findFirstByTaskIdOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        lenient().when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of());
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
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
        agent.setKey("agent");
        agent.setName("Agent");
        agent.setStatus(status);
        return agent;
    }

    @Test
    @DisplayName("a pending task with a completed predecessor is ready")
    void readyTaskReason() {
        Goal g = goal("running");
        Task done = task(g, 0, "completed", null);
        Task ready = task(g, 1, "pending", null);
        stubGoalAndTasks(g, List.of(done, ready));
        stubClaimable(List.of(ready));

        BoardService.Board result = board.board(ORG);

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
        stubClaimable(List.of(next));

        BoardService.Board result = board.board(ORG);

        queueEntryFor(result, next.getId()).ifPresentOrElse(
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
        stubClaimable(List.of(held));

        BoardService.Board result = board.board(ORG);

        assertThat(result.queue().getFirst().reason()).isEqualTo("agent_paused");
        assertThat(result.stats().held()).isEqualTo(1);
        assertThat(result.stats().queued()).isEqualTo(1);
    }

    @Test
    @DisplayName("stopping everything cancels every active goal and any run left over with no goal")
    void stopAllCancelsGoalsAndOrphanRuns() {
        Goal active = goal("running");
        Task openTask = task(active, 0, "running", null);
        when(goals.findByOrgIdOrderByCreatedAtDesc(eq(ORG), any()))
                .thenReturn(new PageImpl<>(List.of(active)));
        when(tasks.findByGoalIdOrderByPosition(active.getId())).thenReturn(List.of(openTask));

        Run orphan = new Run();
        orphan.setId(UUID.randomUUID());
        orphan.setOrgId(ORG);
        orphan.setTaskId(null);
        orphan.setStatus("running");
        when(runs.findByOrgIdAndStatusOrderByStartedAtDesc(eq(ORG), eq("running"), any()))
                .thenReturn(new PageImpl<>(new ArrayList<>(List.of(orphan))));
        when(runs.findByOrgIdAndStatusOrderByStartedAtDesc(eq(ORG), eq("waiting_approval"), any()))
                .thenReturn(new PageImpl<>(List.of()));
        Approval pending = new Approval();
        pending.setId(UUID.randomUUID());
        when(approvals.pending(ORG)).thenReturn(List.of(pending));

        BoardService.StopAllResult result = board.stopAll(ORG);

        assertThat(result.approvalsWithdrawn()).isEqualTo(1);
        assertThat(result.tasksCancelled()).isEqualTo(1);
        assertThat(result.runsCancelled()).isEqualTo(1);
        verify(goalService).cancel(ORG, active.getId());
        assertThat(orphan.getStatus()).isEqualTo("cancelled");
        verify(runs).save(orphan);
        verify(approvals).cancelForRun(orphan.getId());
    }

    private void stubGoalAndTasks(Goal g, List<Task> taskList) {
        when(goals.findByOrgIdOrderByCreatedAtDesc(eq(ORG), any())).thenReturn(new PageImpl<>(List.of(g)));
        when(tasks.findByGoalIdOrderByPosition(g.getId())).thenReturn(taskList);
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(taskList);
        lenient().when(goals.findById(g.getId())).thenReturn(Optional.of(g));
    }

    private void stubClaimable(List<Task> claimable) {
        when(tasks.findClaimable(eq(ORG), any())).thenReturn(claimable);
    }

    private static Optional<BoardService.QueueEntry> queueEntryFor(BoardService.Board board, UUID taskId) {
        return board.queue().stream().filter(entry -> entry.taskId().equals(taskId)).findFirst();
    }
}
