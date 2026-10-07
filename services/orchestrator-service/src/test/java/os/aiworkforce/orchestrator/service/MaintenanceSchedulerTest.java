package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The goal sweep: repair first, then hand every workspace to the executor - never running an agent
 * itself - never stop early, never overlap. The question sweeps: expire one at a time, resume what
 * was decided, never throw.
 */
class MaintenanceSchedulerTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    private GoalService goals;
    private ApprovalService approvals;
    private QuestionService questions;
    private RunExecutor executor;
    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        goals = mock(GoalService.class);
        approvals = mock(ApprovalService.class);
        questions = mock(QuestionService.class);
        executor = mock(RunExecutor.class);
        scheduler = new MaintenanceScheduler(mock(AgentRunner.class), approvals, goals, questions, executor, null);
    }

    @Test
    @DisplayName("each overdue question is expired on its own and its run resumed; one lost to an answer is not")
    void expireQuestionsResumesEachRun() {
        UUID expired = UUID.randomUUID();
        UUID answeredMeanwhile = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(questions.overdueIds(anyInt())).thenReturn(List.of(expired, answeredMeanwhile));
        when(questions.expireOne(expired)).thenReturn(Optional.of(new RunRef(FIRST, runId)));
        when(questions.expireOne(answeredMeanwhile)).thenReturn(Optional.empty());

        scheduler.expireQuestions();

        verify(executor, times(1)).submitResume(FIRST, runId);
        verify(executor, times(1)).submitResume(any(), any());
    }

    @Test
    @DisplayName("one question failing to expire does not stop the rest of the batch")
    void oneFailingExpiryDoesNotStopTheBatch() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(questions.overdueIds(anyInt())).thenReturn(List.of(broken, fine));
        when(questions.expireOne(broken)).thenThrow(new IllegalStateException("lock timeout"));
        when(questions.expireOne(fine)).thenReturn(Optional.of(new RunRef(SECOND, runId)));
        when(questions.strandedPendingIds(anyInt())).thenReturn(List.of());

        scheduler.expireQuestions();

        verify(executor).submitResume(SECOND, runId);
        verify(questions).strandedPendingIds(anyInt());
    }

    @Test
    @DisplayName("pending questions whose work has stopped are closed")
    void strandedQuestionsAreClosed() {
        UUID stranded = UUID.randomUUID();
        when(questions.overdueIds(anyInt())).thenReturn(List.of());
        when(questions.strandedPendingIds(anyInt())).thenReturn(List.of(stranded));

        scheduler.expireQuestions();

        verify(questions).closeStranded(stranded);
        verify(executor, never()).submitResume(any(), any());
    }

    @Test
    @DisplayName("runs answered or approved but still parked are resumed")
    void resumeDecidedRunsSubmitsAnsweredAndApproved() {
        UUID answered = UUID.randomUUID();
        UUID approved = UUID.randomUUID();
        when(questions.awaitingResume(any(), anyInt())).thenReturn(List.of(new RunRef(FIRST, answered)));
        when(approvals.approvedAwaitingResume(any(), anyInt())).thenReturn(List.of(new RunRef(SECOND, approved)));

        scheduler.resumeDecidedRuns();

        verify(executor).submitResume(FIRST, answered);
        verify(executor).submitResume(SECOND, approved);
    }

    @Test
    @DisplayName("a sweep that fails is logged and never thrown to the scheduler")
    void aFailingSweepDoesNotThrow() {
        when(questions.overdueIds(anyInt())).thenThrow(new IllegalStateException("database unavailable"));
        when(questions.awaitingResume(any(), anyInt())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> scheduler.expireQuestions()).doesNotThrowAnyException();
        assertThatCode(() -> scheduler.resumeDecidedRuns()).doesNotThrowAnyException();
        verify(executor, never()).submitResume(any(), any());
    }

    @Test
    @DisplayName("repairs stranded tasks before dispatching any new work")
    void repairsBeforeAdvancing() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));

        scheduler.advanceGoals();

        InOrder order = inOrder(goals, executor);
        order.verify(goals).reconcileStrandedTasks(anyInt());
        order.verify(goals).workspacesWithWaitingTasks();
        order.verify(executor).submitNextTasks(FIRST);
    }

    @Test
    @DisplayName("hands each workspace to the executor and never runs an agent on the sweep's own thread")
    void dispatchesWithoutRunning() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST, SECOND));

        scheduler.advanceGoals();

        verify(executor).submitNextTasks(FIRST);
        verify(executor).submitNextTasks(SECOND);
        verify(goals, never()).runNextTask(any());
        verify(goals, never()).claimNextTask(any());
    }

    @Test
    @DisplayName("dispatches every workspace, and a failure in one does not stop the next")
    void oneFailureDoesNotStopTheRest() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST, SECOND));
        doThrow(new IllegalStateException("executor shut down")).when(executor).submitNextTasks(FIRST);

        scheduler.advanceGoals();

        verify(executor).submitNextTasks(FIRST);
        verify(executor).submitNextTasks(SECOND);
    }

    @Test
    @DisplayName("still advances goals when the repair fails")
    void repairFailureDoesNotBlockAdvance() {
        when(goals.reconcileStrandedTasks(anyInt())).thenThrow(new IllegalStateException("lock timeout"));
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));

        scheduler.advanceGoals();

        verify(executor).submitNextTasks(FIRST);
    }

    @Test
    @DisplayName("skips a tick that arrives while the previous sweep is still listing and dispatching")
    void overlappingTickSkipped() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of(FIRST));
        // The next tick fires while the first sweep is still dispatching.
        doAnswer(call -> {
                    scheduler.advanceGoals();
                    return null;
                })
                .when(executor)
                .submitNextTasks(FIRST);

        scheduler.advanceGoals();

        verify(goals, times(1)).reconcileStrandedTasks(anyInt());
        verify(executor, times(1)).submitNextTasks(FIRST);
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
        verify(executor).submitNextTasks(FIRST);
    }

    @Test
    @DisplayName("dispatches nothing when no workspace has a task waiting")
    void nothingWaiting() {
        when(goals.workspacesWithWaitingTasks()).thenReturn(List.of());

        scheduler.advanceGoals();

        verify(executor, never()).submitNextTasks(any());
    }
}
