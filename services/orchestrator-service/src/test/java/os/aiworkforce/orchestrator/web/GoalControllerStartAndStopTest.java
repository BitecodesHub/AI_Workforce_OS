// @find: tests for goal controller start and stop, start goal, stop goal, cancel, retry, POST /api/goals, /api/goals/{id}/cancel
// @what: Unit and integration tests (4 cases) for goal controller start and stop, for example: create starts without running; cancel checks before stopping; cancel refused stops nothing; cancel is open to readers.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Creating a goal answers with the goal as saved, its work started in the background; nothing runs
 * inside the request, least of all somebody else's queued task. Stopping one is open to whoever
 * asked for it, through the service's one rule.
 */
class GoalControllerStartAndStopTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Goals goals;
    private Tasks tasks;
    private GoalService service;
    private GoalController controller;
    private Actor actor;

    @BeforeEach
    void setUp() {
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        service = mock(GoalService.class);
        controller = new GoalController(goals, tasks, mock(Runs.class), service);
        actor = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:create"), 0L);
        RequestContext.setActor(actor);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("create answers 201 with the goal as saved, dispatched after commit and never run in the request")
    void createStartsWithoutRunning() throws Exception {
        Goal goal = goal("running");
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setGoalId(goal.getId());
        task.setStatus("pending");
        when(service.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);
        when(goals.findById(goal.getId())).thenReturn(Optional.of(goal));
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(task));

        GoalController.GoalView view = controller.create(new GoalController.CreateGoalRequest(
                "Send the weekly report",
                null,
                List.of(new GoalController.TaskInput(
                        UUID.randomUUID(), "Send the weekly report", "Send it to the team.", null))));

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(service).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().source()).isEqualTo("manual");
        assertThat(spec.getValue().tasks()).singleElement().satisfies(newTask -> assertThat(newTask.instruction())
                .isEqualTo("Send it to the team."));
        verify(service, never()).runNextTask(any());
        verify(service, never()).claimNextTask(any());
        verify(service, never()).startClaimed(any(), any());
        assertThat(view.tasks()).extracting(GoalController.TaskView::status).containsExactly("pending");
        ResponseStatus status = GoalController.class
                .getMethod("create", GoalController.CreateGoalRequest.class)
                .getAnnotation(ResponseStatus.class);
        assertThat(status.value()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("cancel checks the caller may stop this goal before it stops anything")
    void cancelChecksBeforeStopping() {
        Goal goal = goal("running");
        when(goals.findByIdAndOrgId(goal.getId(), ORG)).thenReturn(Optional.of(goal));

        controller.cancel(goal.getId());

        InOrder order = inOrder(service);
        order.verify(service).requireCanStop(goal, actor);
        order.verify(service).cancel(ORG, goal.getId());
    }

    @Test
    @DisplayName("cancel by someone who neither asked for the goal nor can cancel work is a 403, and stops nothing")
    void cancelRefusedStopsNothing() {
        Goal goal = goal("running");
        when(goals.findByIdAndOrgId(goal.getId(), ORG)).thenReturn(Optional.of(goal));
        doThrow(new ApiException(
                                ErrorCode.PERMISSION_DENIED,
                                "Only the person who asked for this work, or someone who can cancel work, can stop it.")
                        .with("requiredPermission", "task:cancel"))
                .when(service)
                .requireCanStop(goal, actor);

        assertThatThrownBy(() -> controller.cancel(goal.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        verify(service, never()).cancel(any(), any());
        verify(service, never()).cancel(any(), any(), anyString());
    }

    @Test
    @DisplayName("cancel needs only task:read at the door; the service decides who may stop the goal")
    void cancelIsOpenToReaders() throws Exception {
        RequiresPermission required = GoalController.class
                .getMethod("cancel", UUID.class)
                .getAnnotation(RequiresPermission.class);

        assertThat(required.value()).containsExactly(Permission.Codes.TASK_READ);
    }

    private static Goal goal(String status) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("Send the weekly report");
        goal.setStatus(status);
        return goal;
    }
}
