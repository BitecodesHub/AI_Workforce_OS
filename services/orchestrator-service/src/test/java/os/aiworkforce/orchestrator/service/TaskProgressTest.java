// @find: tests for task progress, task progress, goal progress, run finished, parked, resumed, goal closing, retries
// @what: Unit and integration tests (43 cases) for task progress, for example: completed; failed with retry; failed without retry; abandoned.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.attempt;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.RunSteps;

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
        progress = new TaskProgress(work.tasks, work.goals, java.util.List.of());
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
        @DisplayName("a run parked for an answer puts its task in waiting for input")
        void parkedForInputSetsTaskWaitingInput() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");

            progress.onRunParked(runFor(task, "waiting_input"));

            assertThat(task.getStatus()).isEqualTo("waiting_input");
            assertThat(goal.getStatus()).isEqualTo("running");
        }

        @Test
        @DisplayName("a run that ends while its task waits for an answer still settles the task")
        void runFinishingWhileWaitingInputSettlesTask() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_input");

            progress.onRunFinished(runFor(task, "cancelled"), "cancelled", null, "A person stopped this run.");

            assertThat(task.getStatus()).isEqualTo("cancelled");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
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

    @Nested
    @DisplayName("notifying goal lifecycle listeners")
    class Listeners {

        @Test
        @DisplayName(
                "a listener hears a task finish and the goal close, and a failing listener does not stop the other")
        void listenersNotified() {
            List<String> taskCalls = new java.util.ArrayList<>();
            List<UUID> goalCalls = new java.util.ArrayList<>();
            GoalLifecycleListener recording = new GoalLifecycleListener() {
                @Override
                public void onTaskFinished(Goal goal, Task task, String status) {
                    taskCalls.add(status);
                }

                @Override
                public void onGoalFinished(Goal goal) {
                    goalCalls.add(goal.getId());
                }
            };
            GoalLifecycleListener broken = new GoalLifecycleListener() {
                @Override
                public void onTaskFinished(Goal goal, Task task, String status) {
                    throw new IllegalStateException("boom");
                }

                @Override
                public void onGoalFinished(Goal goal) {
                    throw new IllegalStateException("boom");
                }
            };
            TaskProgress withListeners = new TaskProgress(work.tasks, work.goals, List.of(broken, recording));
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");

            withListeners.onRunFinished(runFor(task, "completed"), "completed", "Done.", null);

            assertThat(taskCalls).containsExactly("completed");
            assertThat(goalCalls).containsExactly(goal.getId());
        }

        @Test
        @DisplayName("inside a transaction, listeners hear nothing until it commits")
        void listenersRunAfterCommitWhenSynchronisationIsActive() {
            List<String> heard = new java.util.ArrayList<>();
            GoalLifecycleListener recording = new GoalLifecycleListener() {
                @Override
                public void onTaskFinished(Goal goal, Task task, String status) {
                    heard.add("task:" + status);
                }

                @Override
                public void onGoalFinished(Goal goal) {
                    heard.add("goal:" + goal.getStatus());
                }
            };
            TaskProgress withListeners = new TaskProgress(work.tasks, work.goals, List.of(recording));
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");

            TransactionSynchronizationManager.initSynchronization();
            try {
                withListeners.onRunFinished(runFor(task, "completed"), "completed", "Done.", null);
                assertThat(heard).isEmpty();

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertThat(heard).containsExactly("task:completed", "goal:completed");
        }

        @Test
        @DisplayName("a listener that throws does so in its own transaction, and never reaches the finish")
        void aThrowingListenerDoesNotMarkTheFinishRollbackOnly() {
            List<String> heard = new java.util.ArrayList<>();
            GoalLifecycleListener broken = new GoalLifecycleListener() {
                @Override
                public void onTaskFinished(Goal goal, Task task, String status) {
                    throw new IllegalStateException("lock timeout on the conversation");
                }
            };
            GoalLifecycleListener recording = new GoalLifecycleListener() {
                @Override
                public void onTaskFinished(Goal goal, Task task, String status) {
                    heard.add(status);
                }
            };
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            SimpleTransactionStatus listenerStatus = new SimpleTransactionStatus();
            when(transactions.getTransaction(any())).thenReturn(listenerStatus);
            TaskProgress withListeners = new TaskProgress(work.tasks, work.goals, List.of(broken, recording));
            withListeners.setTransactionManager(transactions);
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");
            // A later step keeps the goal open, so only the task's own notification is sent.
            work.task(goal, 1, "pending");

            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThatCode(() -> withListeners.onRunFinished(runFor(task, "completed"), "completed", "Done.", null))
                        .doesNotThrowAnyException();
                // Nothing a listener does happens inside the finish itself, so nothing a listener
                // does can mark it rollback-only.
                verify(transactions, never()).getTransaction(any());

                assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                                .forEach(TransactionSynchronization::afterCommit))
                        .doesNotThrowAnyException();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertThat(task.getStatus()).isEqualTo("completed");
            assertThat(heard).containsExactly("completed");
            verify(transactions, times(2))
                    .getTransaction(argThat(definition ->
                            definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
            verify(transactions).rollback(listenerStatus);
        }
    }

    @Nested
    @DisplayName("announcing a settled task")
    class Settled {

        private final List<Object> published = new java.util.ArrayList<>();

        @BeforeEach
        void listen() {
            progress.setEventPublisher(published::add);
        }

        @Test
        @DisplayName("a completed task announces its workspace once the change commits, and not before")
        void completedAnnouncesAfterCommit() {
            Task task = work.task(work.goal("running"), 0, "running");

            TransactionSynchronizationManager.initSynchronization();
            try {
                progress.onRunFinished(runFor(task, "completed"), "completed", "Done.", null);
                assertThat(published).isEmpty();

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertThat(published).containsExactly(new TaskSettledEvent(WorkFixture.ORG));
        }

        @Test
        @DisplayName("a task put back for another attempt is announced, so the retry starts at once")
        void retryIsAnnounced() {
            Task task = attempt(work.task(work.goal("running"), 0, "running"), 1, 2);

            progress.onRunFinished(runFor(task, "failed"), "failed", null, "The provider timed out.");

            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(published).containsExactly(new TaskSettledEvent(WorkFixture.ORG));
        }

        @Test
        @DisplayName("a task whose run could not start is announced too")
        void startFailureIsAnnounced() {
            Task task = attempt(work.task(work.goal("running"), 0, "running"), 1, 2);

            progress.onStartFailed(task, "That agent is paused.");

            assertThat(published).containsExactly(new TaskSettledEvent(WorkFixture.ORG));
        }

        @Test
        @DisplayName("a parked run, or a late report about a task that already moved on, announces nothing")
        void nothingSettledNothingAnnounced() {
            Task waiting = work.task(work.goal("running"), 0, "running");
            Run parked = runFor(waiting, "waiting_approval");
            progress.onRunParked(parked);

            Task done = work.task(work.goal("running"), 0, "completed");
            progress.onRunFinished(runFor(done, "failed"), "failed", null, "Too late.");

            assertThat(published).isEmpty();
        }
    }

    /**
     * A retry starts the instruction again with an empty trace, so it would send again whatever the
     * failed run had already sent. The retry rule reads the run's own trace to see.
     */
    @Nested
    @DisplayName("when a failed run is a candidate for another attempt")
    class SafeToRepeat {

        private RunSteps steps;
        private Approvals approvals;
        private Goal goal;
        private Task task;
        private Run run;

        @BeforeEach
        void arrange() {
            steps = mock(RunSteps.class);
            approvals = mock(Approvals.class);
            progress = new TaskProgress(work.tasks, work.goals, List.of(), steps, approvals);
            goal = work.goal("running");
            task = attempt(work.task(goal, 0, "running"), 1, 2);
            run = runFor(task, "failed");
        }

        private RunStep toolCall(String tool, String sideEffect, String status) {
            return RunStep.of(
                    WorkFixture.ORG,
                    run.getId(),
                    1,
                    "tool_call",
                    Map.of("toolCallId", "c1", "tool", tool, "status", status, "sideEffect", sideEffect));
        }

        private RunStep error(String code) {
            return RunStep.of(WorkFixture.ORG, run.getId(), 9, "error", Map.of("code", code, "detail", "Stopped."));
        }

        private void trace(RunStep... recorded) {
            when(steps.findByRunIdOrderByPosition(run.getId())).thenReturn(List.of(recorded));
        }

        private void fail(String reason, String answer) {
            progress.onRunFinished(run, "failed", answer, reason);
        }

        @Test
        @DisplayName("a run that made a successful write fails its task without a retry, and says which tools")
        void successfulWriteIsNotRetried() {
            trace(toolCall("github.create_issue", "WRITE", "SUCCEEDED"));

            fail("The model returned no answer.", null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(task.getFailureReason())
                    .isEqualTo("This step had already made changes (github.create_issue) before it stopped, so it was"
                            + " not retried automatically. Check the trace, then use Try again if it should continue.");
            assertThat(goal.getStatus()).isEqualTo("failed");
        }

        @Test
        @DisplayName("a run whose only calls were reads, or that made none, is put back to wait")
        void readsAndNoStepsAreRetried() {
            trace(toolCall("crm.find_contact", "READ", "SUCCEEDED"));
            fail("The model returned no answer.", null);
            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getFailureReason()).isEqualTo("The model returned no answer.");
            assertThat(task.getCompletedAt()).isNull();

            task.setStatus("running");
            trace();
            fail("The model returned no answer.", null);
            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("a call whose outcome is unknown counts as a change, because it may have gone out")
        void indeterminateOutboundIsNotRetried() {
            trace(toolCall("gmail.send_message", "OUTBOUND", "INDETERMINATE"));

            fail(
                    "The approved action may or may not have been carried out; check gmail.send_message before"
                            + " retrying.",
                    null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).contains("(gmail.send_message)");
        }

        @Test
        @DisplayName("a write that failed, or was blocked, changed nothing and does not stop a retry")
        void failedAndBlockedWritesAreRetried() {
            trace(
                    toolCall("github.create_issue", "WRITE", "FAILED"),
                    toolCall("gmail.send_message", "OUTBOUND", "BLOCKED"));

            fail("The model returned no answer.", null);

            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("a run with an approval granted is not retried, even when no call was recorded after it")
        void approvedApprovalIsNotRetried() {
            trace();
            Approval approved = new Approval();
            approved.setTool("slack.post_message");
            approved.setStatus("approved");
            when(approvals.findByRunIdAndStatus(run.getId(), "approved")).thenReturn(List.of(approved));

            fail("The platform failed.", null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).contains("(slack.post_message)");
        }

        @Test
        @DisplayName("an approved action whose call was answered as failed never went out and does not stop a retry")
        void approvedActionThatNeverWentOutIsRetried() {
            RunStep answered = toolCall("gmail.send_message", "OUTBOUND", "FAILED");
            trace(answered);
            Approval approved = new Approval();
            approved.setTool("gmail.send_message");
            approved.setToolCallId("c1");
            approved.setStatus("approved");
            when(approvals.findByRunIdAndStatus(run.getId(), "approved")).thenReturn(List.of(approved));

            fail("The platform failed.", null);

            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getFailureReason()).isEqualTo("The platform failed.");
        }

        @Test
        @DisplayName("an approved action answered as succeeded, or not answered at all, still counts as a change")
        void approvedActionThatMayHaveGoneOutStillCounts() {
            trace(toolCall("gmail.send_message", "OUTBOUND", "SUCCEEDED"));
            Approval sent = new Approval();
            sent.setTool("gmail.send_message");
            sent.setToolCallId("c1");
            sent.setStatus("approved");
            Approval unanswered = new Approval();
            unanswered.setTool("slack.post_message");
            unanswered.setToolCallId("c2");
            unanswered.setStatus("approved");
            when(approvals.findByRunIdAndStatus(run.getId(), "approved")).thenReturn(List.of(sent, unanswered));

            fail("The platform failed.", null);

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).contains("(gmail.send_message, slack.post_message)");
        }

        @Test
        @DisplayName("each tool is named once, and a long list is shortened")
        void toolsAreListedOnceAndShortened() {
            trace(
                    toolCall("a.one", "WRITE", "SUCCEEDED"),
                    toolCall("a.one", "WRITE", "SUCCEEDED"),
                    toolCall("a.two", "WRITE", "SUCCEEDED"),
                    toolCall("a.three", "WRITE", "SUCCEEDED"),
                    toolCall("a.four", "WRITE", "SUCCEEDED"),
                    toolCall("a.five", "WRITE", "SUCCEEDED"),
                    toolCall("a.six", "DESTRUCTIVE", "SUCCEEDED"),
                    toolCall("a.seven", "OUTBOUND", "SUCCEEDED"));

            fail("Stopped.", null);

            assertThat(task.getFailureReason())
                    .contains("(a.one, a.two, a.three, a.four, a.five and 2 more)")
                    .doesNotContain("a.six");
        }

        @Test
        @DisplayName("a step recorded before the side effect was kept says nothing either way")
        void stepWithoutSideEffectIsNotCounted() {
            trace(RunStep.of(
                    WorkFixture.ORG,
                    run.getId(),
                    1,
                    "tool_call",
                    Map.of("toolCallId", "c1", "tool", "gmail.send_message", "status", "SUCCEEDED")));

            fail("The model returned no answer.", null);

            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("the step limit, the output limit, a loop, the budget and a policy refusal are never retried")
        void limitsAreNeverRetried() {
            for (String code :
                    List.of("step_limit", "output_limit", "loop_detected", "budget_exceeded", "policy_violation")) {
                Task fresh = attempt(work.task(goal, 1, "running"), 1, 3);
                Run failed = runFor(fresh, "failed");
                when(steps.findByRunIdOrderByPosition(failed.getId()))
                        .thenReturn(List.of(RunStep.of(
                                WorkFixture.ORG,
                                failed.getId(),
                                3,
                                "error",
                                Map.of("code", code, "detail", "Stopped."))));

                progress.onRunFinished(failed, "failed", null, "Stopped for " + code + ".");

                assertThat(fresh.getStatus()).as(code).isEqualTo("failed");
                assertThat(fresh.getFailureReason()).as(code).isEqualTo("Stopped for " + code + ".");
            }
        }

        @Test
        @DisplayName("a failure a retry can fix, like no model being reachable, is still retried")
        void transientFailuresAreRetried() {
            trace(error("no_model_available"));

            fail("No language model in the routing policy is available.", null);

            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("the answer a failed run had written is kept on a task that will not be retried")
        void incompleteAnswerIsKeptOnTheTask() {
            trace(error("step_limit"));

            fail("The agent reached its step limit of 2 without finishing.", "Two of the three suppliers are checked.");

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getResult()).isEqualTo("Two of the three suppliers are checked.");
            assertThat(task.getFailureReason()).isEqualTo("The agent reached its step limit of 2 without finishing.");
        }

        @Test
        @DisplayName("an answer is not kept on a task that is about to run again, so the next attempt starts clean")
        void answerIsNotKeptOnARetriedTask() {
            trace();

            fail("The model returned no answer.", "Some text.");

            assertThat(task.getStatus()).isEqualTo("pending");
            assertThat(task.getResult()).isNull();
        }

        @Test
        @DisplayName("a task with no attempts left fails with the run's own reason, and keeps any answer")
        void lastAttemptKeepsItsOwnReason() {
            task.setAttempt(2);
            trace(toolCall("github.create_issue", "WRITE", "SUCCEEDED"));

            fail("The agent reached its step limit of 2 without finishing.", "Half of it.");

            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).isEqualTo("The agent reached its step limit of 2 without finishing.");
            assertThat(task.getResult()).isEqualTo("Half of it.");
        }

        @Test
        @DisplayName("an abandoned run is still never retried, and a completed one is unaffected by its trace")
        void otherOutcomesAreUnchanged() {
            trace(toolCall("github.create_issue", "WRITE", "SUCCEEDED"));

            progress.onRunFinished(runFor(task, "abandoned"), "abandoned", null, "The worker stopped.");
            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).isEqualTo("The worker stopped.");

            Task other = attempt(work.task(goal, 1, "running"), 1, 2);
            progress.onRunFinished(runFor(other, "completed"), "completed", "Done.", null);
            assertThat(other.getStatus()).isEqualTo("completed");
        }

        @Test
        @DisplayName("a run that could not start has no trace to read and follows the ordinary retry rule")
        void startFailureHasNoTrace() {
            progress.onStartFailed(task, "That agent is paused.");

            assertThat(task.getStatus()).isEqualTo("pending");
            verifyNoInteractions(steps, approvals);
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
