package os.aiworkforce.orchestrator.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The goal sweep: repair first, then at most one task per workspace, never stop early, never overlap. */
class MaintenanceSchedulerTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    private GoalService goals;
    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        goals = mock(GoalService.class);
        scheduler = new MaintenanceScheduler(mock(AgentRunner.class), mock(ApprovalService.class), goals);
    }

    @Test
    @DisplayName("repairs stranded tasks before starting any new work")
    void repairsBeforeAdvancing() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));

        scheduler.advanceGoals();

        InOrder order = inOrder(goals);
        order.verify(goals).reconcileStrandedTasks(anyInt());
        order.verify(goals).workspacesWithWaitingTasks();
        order.verify(goals).runNextTask(FIRST);
    }

    @Test
    @DisplayName("starts one task in each workspace, and a failure in one does not stop the next")
    void oneFailureDoesNotStopTheRest() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST, SECOND));
        when(goals.runNextTask(FIRST)).thenThrow(new IllegalStateException("database unavailable"));

        scheduler.advanceGoals();

        verify(goals).runNextTask(FIRST);
        verify(goals).runNextTask(SECOND);
    }

    @Test
    @DisplayName("still advances goals when the repair fails")
    void repairFailureDoesNotBlockAdvance() {
        when(goals.reconcileStrandedTasks(anyInt())).thenThrow(new IllegalStateException("lock timeout"));
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));

        scheduler.advanceGoals();

        verify(goals).runNextTask(FIRST);
    }

    @Test
    @DisplayName("skips a tick that arrives while the previous sweep is still running an agent")
    void overlappingTickSkipped() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));
        // The next tick fires while the first sweep is still inside an agent run.
        when(goals.runNextTask(FIRST)).thenAnswer(call -> {
            scheduler.advanceGoals();
            return true;
        });

        scheduler.advanceGoals();

        verify(goals, times(1)).reconcileStrandedTasks(anyInt());
        verify(goals, times(1)).runNextTask(FIRST);
    }

    @Test
    @DisplayName("runs again on the next tick once the previous sweep has finished, even after a failure")
    void runsAgainAfterPreviousSweep() {
        when(goals.workspacesWithWaitingTasks())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of(FIRST));

        scheduler.advanceGoals();
        scheduler.advanceGoals();

        verify(goals, times(2)).reconcileStrandedTasks(anyInt());
        verify(goals).runNextTask(FIRST);
    }

    @Test
    @DisplayName("starts nothing when no workspace has a task waiting")
    void nothingWaiting() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of());

        scheduler.advanceGoals();

        verify(goals, never()).runNextTask(org.mockito.ArgumentMatchers.any());
    }
}
