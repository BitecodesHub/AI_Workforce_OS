package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

/** Trying a goal again goes through the one rule the service holds, with the caller as the actor. */
class GoalControllerTest {

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
    @DisplayName("a retry passes the caller to the service and returns the goal as it now stands")
    void retryPassesTheActorAndReturnsTheGoal() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("Plan the team offsite");
        goal.setStatus("running");
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setGoalId(goal.getId());
        task.setStatus("pending");
        when(goals.findByIdAndOrgId(goal.getId(), ORG)).thenReturn(Optional.of(goal));
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(task));

        GoalController.GoalView view = controller.retry(goal.getId());

        verify(service).retry(ORG, goal.getId(), actor);
        assertThat(view.status()).isEqualTo("running");
        assertThat(view.tasks()).extracting(GoalController.TaskView::status).containsExactly("pending");
    }

    @Test
    @DisplayName("a retry of someone else's goal is refused by the service, and nothing is returned")
    void retryOfAnotherPersonsGoalIs403() {
        UUID goalId = UUID.randomUUID();
        when(service.retry(eq(ORG), eq(goalId), any()))
                .thenThrow(new ApiException(
                                ErrorCode.PERMISSION_DENIED,
                                "Only the person who asked for this work, or someone who can cancel work, can try it again.")
                        .with("requiredPermission", "task:cancel"));

        assertThatThrownBy(() -> controller.retry(goalId)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.details()).containsEntry("requiredPermission", "task:cancel");
        });
        verify(goals, never()).findByIdAndOrgId(any(), any());
    }
}
