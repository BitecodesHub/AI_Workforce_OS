// @find: tests for goal controller list, list goals, GET /api/goals, filters, paging
// @what: Unit and integration tests (9 cases) for goal controller list, for example: a page costs afixed number of queries; each task carries its latest run; an empty page reads nothing else; no filter asks for everything.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The goals list: filters that run in the database, and a page that costs a fixed number of
 * queries however many goals and tasks it holds.
 */
class GoalControllerListTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Goals goals;
    private Tasks tasks;
    private Runs runs;
    private GoalController controller;

    @BeforeEach
    void setUp() {
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        controller = new GoalController(goals, tasks, runs, mock(GoalService.class));
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:read"), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static Goal goal(String title) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle(title);
        goal.setStatus("completed");
        return goal;
    }

    private static Task task(Goal goal, int position) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setTitle("Task " + position);
        task.setPosition(position);
        task.setStatus("completed");
        return task;
    }

    private static Run run(Task task) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(task.getId());
        run.setStatus("completed");
        return run;
    }

    @Test
    @DisplayName("a page of goals reads every goal's tasks in one query and every task's runs in one more")
    void aPageCostsAFixedNumberOfQueries() {
        List<Goal> page = List.of(goal("One"), goal("Two"), goal("Three"));
        List<Task> allTasks = page.stream()
                .flatMap(goal -> java.util.stream.Stream.of(task(goal, 0), task(goal, 1)))
                .toList();
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(page);
        when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(allTasks);
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any()))
                .thenReturn(allTasks.stream().map(GoalControllerListTest::run).toList());

        List<GoalController.GoalView> views = controller.list(0, 50, null, null, null);

        assertThat(views).hasSize(3);
        assertThat(views).allSatisfy(view -> assertThat(view.tasks()).hasSize(2));
        verify(tasks, times(1)).findByGoalIdInOrderByPositionAsc(any());
        verify(runs, times(1)).findByTaskIdInOrderByTaskIdAscStartedAtDesc(any());
        verify(tasks, never()).findByGoalIdOrderByPosition(any());
        verify(runs, never()).findFirstByTaskIdOrderByStartedAtDesc(any());
    }

    @Test
    @DisplayName("each task carries its latest run's id, and a task that has not run carries none")
    void eachTaskCarriesItsLatestRun() {
        Goal goal = goal("One");
        Task ran = task(goal, 0);
        Task waiting = task(goal, 1);
        Run newer = run(ran);
        Run older = run(ran);
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(List.of(goal));
        when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of(ran, waiting));
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(newer, older));

        GoalController.GoalView view = controller.list(0, 50, null, null, null).getFirst();

        assertThat(view.tasks().get(0).runId()).isEqualTo(newer.getId());
        assertThat(view.tasks().get(1).runId()).isNull();
    }

    @Test
    @DisplayName("an empty page reads no tasks and no runs")
    void anEmptyPageReadsNothingElse() {
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(List.of());

        assertThat(controller.list(3, 50, null, null, null)).isEmpty();

        verifyNoInteractions(tasks, runs);
    }

    @Test
    @DisplayName("with no filter the database is asked for every status, source and schedule")
    void noFilterAsksForEverything() {
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(List.of());

        controller.list(0, 50, null, "  ", null);

        verify(goals).findFiltered(eq(ORG), eq(""), eq(""), eq(true), any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("status, source and schedule filters reach the database query")
    void filtersReachTheQuery() {
        UUID schedule = UUID.randomUUID();
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(List.of());

        controller.list(0, 50, " failed ", "schedule", schedule);

        verify(goals).findFiltered(eq(ORG), eq("failed"), eq("schedule"), eq(false), eq(schedule), any(Pageable.class));
    }

    @Test
    @DisplayName("an unknown status or source is a validation error that names the field, and reads nothing")
    void unknownFiltersAreRefused() {
        assertThatThrownBy(() -> controller.list(0, 50, "exploded", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).isNotBlank();
                });
        assertThatThrownBy(() -> controller.list(0, 50, null, "carrier-pigeon", null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(goals, tasks, runs);
    }

    @Test
    @DisplayName("every status a goal can be in is accepted as a filter")
    void everyGoalStatusIsAccepted() {
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(List.of());

        for (String status : List.of("planning", "running", "waiting", "completed", "failed", "cancelled")) {
            controller.list(0, 50, status, null, null);
        }

        verify(goals, times(6)).findFiltered(eq(ORG), anyString(), eq(""), eq(true), any(), any());
    }

    @Test
    @DisplayName("the page is clamped to 1 to 100 rows, and a negative page reads as the first")
    void pageSizeAndNumberAreClamped() {
        when(goals.findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        controller.list(-4, 5_000, null, null, null);
        controller.list(2, 0, null, null, null);

        verify(goals, times(2))
                .findFiltered(eq(ORG), anyString(), anyString(), anyBoolean(), any(), pageable.capture());
        assertThat(pageable.getAllValues().get(0).getPageNumber()).isZero();
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(pageable.getAllValues().get(1).getPageNumber()).isEqualTo(2);
        assertThat(pageable.getAllValues().get(1).getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("one goal reads its tasks and their runs the same way, and a goal in another workspace is a 404")
    void oneGoalUsesTheSameMapper() {
        Goal goal = goal("One");
        Task task = task(goal, 0);
        Run run = run(task);
        when(goals.findByIdAndOrgId(goal.getId(), ORG)).thenReturn(Optional.of(goal));
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(task));
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(run));

        GoalController.GoalView view = controller.get(goal.getId());

        assertThat(view.tasks().getFirst().runId()).isEqualTo(run.getId());
        verify(runs, never()).findFirstByTaskIdOrderByStartedAtDesc(any());

        UUID elsewhere = UUID.randomUUID();
        when(goals.findByIdAndOrgId(elsewhere, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.get(elsewhere))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
