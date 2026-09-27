package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static os.aiworkforce.orchestrator.service.WorkFixture.attempt;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;

/**
 * What a run's outcome means for its task and goal.
 *
 * <p>Each test is one of the ways a task used to be left behind: waiting for an approval that had
 * been decided, running after its run had ended, or holding its goal open after its last attempt
 * failed.
 */
class TaskProgressTest {

    private WorkFixture work;
    private TaskProgress progress;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        progress = new TaskProgress(work.tasks, work.goals);
    }

    @Nested
    @DisplayName("when a run finishes")
    class Finished {

        @Test
        @DisplayName("a completed run completes its task with the answer and closes the goal")
        void completed() {
            Goal goal = work.goal("running");
            Task task = attempt(work.task(goal, 0, "running"), 1, 2);
            task.setFailureReason("An earlier attempt failed.");

            progress.onRunFinished(runFor(task, "completed"), "completed", "Drafted the welcome email.", null);

            assertThat(task.getStatus()).isEqualTo("completed");
            assertThat(task.getResult()).isEqualTo("Drafted the welcome email.");
            assertThat(task.getFailureReason()).isNull();
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(goal.getStatus()).isEqualTo("completed");
            assertThat(goal.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("a failed run with attempts left puts the task back to wait, and the goal stays open")
        void failedWithRetry() {
            Goal goal = work.goal("running");
            Task task = attempt(work.task(goal, 0, "running"), 1, 2);
            Task next = work.task(goal, 1, "pending");

            progress.onRunFinished(runFor(task, "failed"), "failed", null, "The model's answer was cut short.");

            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getFailureReason()).isEqualTo("The model's answer was cut short.");
            assertThat(task.getCompletedAt()).isNull();
            assertThat(next.getStatus()).isEqualTo("pending");
            assertThat(goal.getStatus()).isEqualTo("running");
            verify(work.goals, never()).save(any());
        }

        @Test
        @DisplayName("a failed last attempt fails the task, skips the tasks after it, and fails the goal")
        void failedWithoutRetry() {
            Goal goal = work.goal("running");
            Task done = work.task(goal, 0, "completed");
            Task task = attempt(work.task(goal, 1, "running"), 2, 2);
            Task later = work.task(goal, 2, "pending");
            Task ready = work.task(goal, 3, "ready");

            progress.onRunFinished(runFor(task, "failed"), "failed", null, null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).isEqualTo("The agent did not complete this task.");
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(done.getStatus()).isEqualTo("completed");
            assertThat(later.getStatus()).isEqualTo("skipped");
            assertThat(later.getFailureReason()).isEqualTo(TaskProgress.EARLIER_TASK_UNFINISHED);
            assertThat(ready.getStatus()).isEqualTo("skipped");
            assertThat(goal.getStatus()).isEqualTo("failed");
        }

        @Test
        @DisplayName("an abandoned run fails its task without a retry, whatever attempts are left")
        void abandoned() {
            Goal goal = work.goal("running");
            Task task = attempt(work.task(goal, 0, "running"), 1, 3);

            progress.onRunFinished(
                    runFor(task, "abandoned"), "abandoned", null, "The worker running this task stopped responding.");

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).isEqualTo("The worker running this task stopped responding.");
            assertThat(goal.getStatus()).isEqualTo("failed");
        }

        @Test
        @DisplayName("a cancelled run cancels its task, skips the rest, and cancels the goal")
        void cancelled() {
            Goal goal = work.goal("running");
            Task done = work.task(goal, 0, "completed");
            Task task = work.task(goal, 1, "waiting_approval");
            Task later = work.task(goal, 2, "pending");

            progress.onRunFinished(
                    runFor(task, "cancelled"), "cancelled", null, "An approver rejected the action this run needed.");

            assertThat(task.getStatus()).isEqualTo("cancelled");
            assertThat(task.getFailureReason()).isEqualTo("An approver rejected the action this run needed.");
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(later.getStatus()).isEqualTo("skipped");
            assertThat(done.getStatus()).isEqualTo("completed");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("a completed run leaves the goal open while a later task is still to run")
        void laterTaskKeepsGoalOpen() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");
            Task later = work.task(goal, 1, "pending");

            progress.onRunFinished(runFor(task, "completed"), "completed", "Done.", null);

            assertThat(task.getStatus()).isEqualTo("completed");
            assertThat(later.getStatus()).isEqualTo("pending");
            assertThat(goal.getStatus()).isEqualTo("running");
        }
    }

    @Nested
    @DisplayName("is safe to call more than once")
    class Idempotent {

        @Test
        @DisplayName("a task that already finished is left exactly as it was")
        void terminalTaskUnchanged() {
            Goal goal = work.goal("completed");
            Task task = work.task(goal, 0, "completed");
            task.setResult("The first answer.");

            progress.onRunFinished(runFor(task, "failed"), "failed", null, "Late report.");

            assertThat(task.getStatus()).isEqualTo("completed");
            assertThat(task.getResult()).isEqualTo("The first answer.");
            assertThat(task.getFailureReason()).isNull();
            verify(work.tasks, never()).save(any());
            verify(work.goals, never()).save(any());
        }

        @Test
        @DisplayName("a task already put back for another attempt ignores a late report about the old run")
        void pendingTaskUnchanged() {
            Goal goal = work.goal("running");
            Task task = attempt(work.task(goal, 0, "pending"), 1, 2);

            progress.onRunFinished(runFor(task, "cancelled"), "cancelled", null, "Late.");

            assertThat(task.getStatus()).isEqualTo("pending");
            verify(work.tasks, never()).save(any());
        }

        @Test
        @DisplayName("a run started directly rather than for a task changes nothing")
        void runWithoutTask() {
            Run manual = runFor(null, "completed");

            progress.onRunFinished(manual, "completed", "Answer.", null);
            progress.onRunParked(manual);
            progress.onRunResumed(manual);

            verifyNoInteractions(work.tasks, work.goals);
        }

        @Test
        @DisplayName("a goal that already finished keeps its status when a run ends late")
        void finishedGoalKeepsStatus() {
            Goal goal = work.goal("cancelled");
            Task task = attempt(work.task(goal, 0, "running"), 2, 2);

            progress.onRunFinished(runFor(task, "failed"), "failed", null, null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
            verify(work.goals, never()).save(any());
        }

        @Test
        @DisplayName("a status that is not a finished state changes nothing")
        void notFinished() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");

            progress.onRunFinished(runFor(task, "waiting_approval"), "waiting_approval", null, null);

            assertThat(task.getStatus()).isEqualTo("running");
            verify(work.tasks, never()).save(any());
        }
    }

    @Nested
    @DisplayName("when a run parks and resumes")
    class ParkAndResume {

        @Test
        @DisplayName("a parked run puts its task in waiting for approval")
        void parked() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");

            progress.onRunParked(runFor(task, "waiting_approval"));

            assertThat(task.getStatus()).isEqualTo("waiting_approval");
            assertThat(goal.getStatus()).isEqualTo("running");
        }

        @Test
        @DisplayName("a resumed run puts its task back to running")
        void resumed() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_approval");

            progress.onRunResumed(runFor(task, "running"));

            assertThat(task.getStatus()).isEqualTo("running");
        }

        @Test
        @DisplayName("a cancelled task is not brought back by a resume")
        void resumeIgnoresFinishedTask() {
            Goal goal = work.goal("cancelled");
            Task task = work.task(goal, 0, "cancelled");

            progress.onRunResumed(runFor(task, "running"));

            assertThat(task.getStatus()).isEqualTo("cancelled");
            verify(work.tasks, never()).save(any());
        }
    }

    @Nested
    @DisplayName("closing a goal")
    class ClosingGoal {

        @Test
        @DisplayName("failed outranks cancelled")
        void failedOutranksCancelled() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "cancelled");
            work.task(goal, 1, "failed");
            work.task(goal, 2, "completed");

            progress.closeGoalIfFinished(goal.getId());

            assertThat(goal.getStatus()).isEqualTo("failed");
        }

        @Test
        @DisplayName("cancelled outranks completed")
        void cancelledOutranksCompleted() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "completed");
            work.task(goal, 1, "cancelled");
            work.task(goal, 2, "skipped");

            progress.closeGoalIfFinished(goal.getId());

            assertThat(goal.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("completed and skipped tasks complete the goal")
        void completedWithSkips() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "completed");
            work.task(goal, 1, "skipped");

            progress.closeGoalIfFinished(goal.getId());

            assertThat(goal.getStatus()).isEqualTo("completed");
        }

        @Test
        @DisplayName("a goal with a task still waiting for approval stays open")
        void openWhileWaiting() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "completed");
            work.task(goal, 1, "waiting_approval");

            progress.closeGoalIfFinished(goal.getId());

            assertThat(goal.getStatus()).isEqualTo("running");
            verify(work.goals, never()).save(any());
        }
    }

    @Test
    @DisplayName("a run that could not start applies the retry rule, then fails the goal on the last attempt")
    void startFailed() {
        Goal goal = work.goal("running");
        Task task = attempt(work.task(goal, 0, "running"), 1, 2);

        progress.onStartFailed(task, "That agent is paused.");
        assertThat(task.getStatus()).isEqualTo("pending");
        assertThat(goal.getStatus()).isEqualTo("running");

        task.setStatus("running");
        task.setAttempt(2);
        progress.onStartFailed(task, "That agent is paused.");
        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getFailureReason()).isEqualTo("That agent is paused.");
        assertThat(goal.getStatus()).isEqualTo("failed");
    }
}
