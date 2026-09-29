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

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Runs;

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

    @Test
    void goalViewKeepsTaskOrderAndAttachesEachTasksLatestRun() {
        Goal goal = goal();
        Task first = task(goal, 0);
        Task second = task(goal, 1);
        Run firstRun = run(first.getId(), "completed");
        Map<UUID, Run> latestRunByTask = new LinkedHashMap<>();
        latestRunByTask.put(first.getId(), firstRun);

        BoardService.GoalView view = GoalViews.goalView(goal, List.of(first, second), latestRunByTask);

        assertThat(view.id()).isEqualTo(goal.getId());
        assertThat(view.tasks()).extracting(BoardService.TaskView::id).containsExactly(first.getId(), second.getId());
        assertThat(view.tasks().get(0).runId()).isEqualTo(firstRun.getId());
        assertThat(view.tasks().get(0).runStatus()).isEqualTo("completed");
        assertThat(view.tasks().get(1).runId()).isNull();
    }

    @Test
    void taskViewWithNoRunGivesNullStatusZeroStepsAndZeroCost() {
        Task task = task(goal(), 0);

        BoardService.TaskView view = GoalViews.taskView(task, Map.of());

        assertThat(view.runId()).isNull();
        assertThat(view.runStatus()).isNull();
        assertThat(view.stepCount()).isZero();
        assertThat(view.cost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void latestRunByTaskKeepsTheFirstRunPerTaskFromTheOrderedQuery() {
        Runs runs = mock(Runs.class);
        UUID taskId = UUID.randomUUID();
        Run newer = run(taskId, "running");
        Run older = run(taskId, "completed");
        // The repository orders task then most-recent-first; the first one seen per task is kept.
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(newer, older));

        Map<UUID, Run> latest = GoalViews.latestRunByTask(runs, List.of(taskId));

        assertThat(latest).hasSize(1);
        assertThat(latest.get(taskId)).isSameAs(newer);
    }

    @Test
    void latestRunByTaskSkipsTheQueryWhenThereAreNoTaskIds() {
        Runs runs = mock(Runs.class);

        Map<UUID, Run> latest = GoalViews.latestRunByTask(runs, List.of());

        assertThat(latest).isEmpty();
        verifyNoInteractions(runs);
    }
}
