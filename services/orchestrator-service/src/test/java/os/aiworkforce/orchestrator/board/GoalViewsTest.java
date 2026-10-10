// @find: tests for goal views, board, goal view keeps task order and attaches each tasks latest run, task view with no run gives null status zero steps zero cost and no attempts, runs by task keeps the first run per task from the ordered query, runs by task skips the query when there are no task ids, a tasks cost is the sum of every run not just the latest, the step count and status are the latest attempts own, a run with no recorded cost counts as nothing, plain view builds the goals list shape from the same batched maps, GoalViewsTest, GoalViews
// @what: Tests for GoalViews in the orchestrator board package (8 test methods).
// @flow: Exercises GoalViews
package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.board.GoalViews.TaskRuns;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.web.GoalController;

class GoalViewsTest {

    private static final UUID ORG = UUID.randomUUID();

    private static Goal goal() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("A goal");
        goal.setStatus("running");
        return goal;
    }

    private static Task task(Goal goal, int position) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setTitle("Task " + position);
        task.setPosition(position);
        task.setStatus("pending");
        return task;
    }

    private static Run run(UUID taskId, String status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(taskId);
        run.setStatus(status);
        return run;
    }

    private static Run run(UUID taskId, String status, String cost, int steps) {
        Run run = run(taskId, status);
        run.setTotalCost(new BigDecimal(cost));
        run.setStepCount(steps);
        return run;
    }

    // @find: test goal view keeps task order and attaches each tasks latest run, goal views
    @Test
    void goalViewKeepsTaskOrderAndAttachesEachTasksLatestRun() {
        Goal goal = goal();
        Task first = task(goal, 0);
        Task second = task(goal, 1);
        Run firstRun = run(first.getId(), "completed");
        Map<UUID, TaskRuns> runsByTask = new LinkedHashMap<>();
        runsByTask.put(first.getId(), new TaskRuns(firstRun, BigDecimal.ONE, 1));

        BoardService.GoalView view = GoalViews.goalView(goal, List.of(first, second), runsByTask);

        assertThat(view.id()).isEqualTo(goal.getId());
        assertThat(view.tasks()).extracting(BoardService.TaskView::id).containsExactly(first.getId(), second.getId());
        assertThat(view.tasks().get(0).runId()).isEqualTo(firstRun.getId());
        assertThat(view.tasks().get(0).runStatus()).isEqualTo("completed");
        assertThat(view.tasks().get(1).runId()).isNull();
    }

    // @find: test task view with no run gives null status zero steps zero cost and no attempts, goal views
    @Test
    void taskViewWithNoRunGivesNullStatusZeroStepsZeroCostAndNoAttempts() {
        Task task = task(goal(), 0);

        BoardService.TaskView view = GoalViews.taskView(task, Map.of());

        assertThat(view.runId()).isNull();
        assertThat(view.runStatus()).isNull();
        assertThat(view.stepCount()).isZero();
        assertThat(view.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.attempts()).isZero();
    }

    // @find: test runs by task keeps the first run per task from the ordered query, goal views
    @Test
    void runsByTaskKeepsTheFirstRunPerTaskFromTheOrderedQuery() {
        Runs runs = mock(Runs.class);
        UUID taskId = UUID.randomUUID();
        Run newer = run(taskId, "running");
        Run older = run(taskId, "completed");
        // The repository orders task then most-recent-first; the first one seen per task is kept.
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(newer, older));

        Map<UUID, TaskRuns> byTask = GoalViews.runsByTask(runs, List.of(taskId));

        assertThat(byTask).hasSize(1);
        assertThat(byTask.get(taskId).latest()).isSameAs(newer);
        assertThat(GoalViews.latestRuns(byTask).get(taskId)).isSameAs(newer);
    }

    // @find: test runs by task skips the query when there are no task ids, goal views
    @Test
    void runsByTaskSkipsTheQueryWhenThereAreNoTaskIds() {
        Runs runs = mock(Runs.class);

        Map<UUID, TaskRuns> byTask = GoalViews.runsByTask(runs, List.of());

        assertThat(byTask).isEmpty();
        verifyNoInteractions(runs);
    }

    // @find: test a tasks cost is the sum of every run not just the latest, goal views
    @Test
    void aTasksCostIsTheSumOfEveryRunNotJustTheLatest() {
        Runs runs = mock(Runs.class);
        Task task = task(goal(), 0);
        // The first attempt failed after spending real money; the retry is still going.
        Run retry = run(task.getId(), "running", "0.15", 2);
        Run failed = run(task.getId(), "failed", "0.40", 7);
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(retry, failed));

        BoardService.TaskView view = GoalViews.taskView(task, GoalViews.runsByTask(runs, List.of(task.getId())));

        assertThat(view.cost()).isEqualByComparingTo("0.55");
        assertThat(view.attempts()).isEqualTo(2);
    }

    // @find: test the step count and status are the latest attempts own, goal views
    @Test
    void theStepCountAndStatusAreTheLatestAttemptsOwn() {
        Runs runs = mock(Runs.class);
        Task task = task(goal(), 0);
        Run retry = run(task.getId(), "completed", "0.10", 3);
        Run failed = run(task.getId(), "failed", "0.40", 12);
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(retry, failed));

        BoardService.TaskView view = GoalViews.taskView(task, GoalViews.runsByTask(runs, List.of(task.getId())));

        assertThat(view.stepCount()).isEqualTo(3);
        assertThat(view.runStatus()).isEqualTo("completed");
        assertThat(view.runId()).isEqualTo(retry.getId());
    }

    // @find: test a run with no recorded cost counts as nothing, goal views
    @Test
    void aRunWithNoRecordedCostCountsAsNothing() {
        Runs runs = mock(Runs.class);
        Task task = task(goal(), 0);
        Run unpriced = run(task.getId(), "completed");
        unpriced.setTotalCost(null);
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(unpriced));

        BoardService.TaskView view = GoalViews.taskView(task, GoalViews.runsByTask(runs, List.of(task.getId())));

        assertThat(view.cost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // @find: test plain view builds the goals list shape from the same batched maps, goal views
    @Test
    void plainViewBuildsTheGoalsListShapeFromTheSameBatchedMaps() {
        Goal goal = goal();
        Task first = task(goal, 0);
        Task second = task(goal, 1);
        Run firstRun = run(first.getId(), "completed", "0.10", 3);
        Map<UUID, TaskRuns> runsByTask = Map.of(first.getId(), new TaskRuns(firstRun, new BigDecimal("0.10"), 1));

        GoalController.GoalView view = GoalViews.plainView(goal, List.of(first, second), runsByTask);

        assertThat(view.id()).isEqualTo(goal.getId());
        assertThat(view.tasks()).extracting(GoalController.TaskView::id).containsExactly(first.getId(), second.getId());
        assertThat(view.tasks().get(0).runId()).isEqualTo(firstRun.getId());
        assertThat(view.tasks().get(1).runId()).isNull();
    }
}
