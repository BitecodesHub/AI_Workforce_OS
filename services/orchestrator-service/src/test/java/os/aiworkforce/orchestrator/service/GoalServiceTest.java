package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;
import static os.aiworkforce.orchestrator.service.WorkFixture.attempt;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

class GoalServiceTest {

    private WorkFixture work;
    private Runs runs;
    private RunSteps steps;
    private AgentRunner runner;
    private Approvals approvalRows;
    private final List<Approval> allApprovals = new ArrayList<>();
    private ApprovalService approvals;
    private GoalService service;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        runner = mock(AgentRunner.class);
        approvalRows = mock(Approvals.class);
        lenient().when(approvalRows.findByRunIdAndStatus(any(), anyString())).thenAnswer(call -> allApprovals.stream()
                .filter(approval -> approval.getRunId().equals(call.getArgument(0))
                        && approval.getStatus().equals(call.getArgument(1)))
                .toList());
        lenient().when(approvalRows.findByIdAndOrgId(any(), any())).thenAnswer(call -> allApprovals.stream()
                .filter(approval -> approval.getId().equals(call.getArgument(0)))
                .findFirst());

        TaskProgress progress = new TaskProgress(work.tasks, work.goals);
        approvals = new ApprovalService(approvalRows, runs, new ObjectMapper(), mock(AuditClient.class), progress);
        service = new GoalService(work.goals, work.tasks, runs, steps, runner, approvals, progress);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Nested
    @DisplayName("cancelling a goal")
    class Cancel {

        @Test
        @DisplayName("stops the task's run and withdraws the approval it was waiting on")
        void stopsRunAndApproval() {
            Goal goal = work.goal("running");
            Task done = work.task(goal, 0, "completed");
            Task waiting = work.task(goal, 1, "waiting_approval");
            Task later = work.task(goal, 2, "pending");
            Run run = runFor(waiting, "waiting_approval");
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(waiting.getId())).thenReturn(Optional.of(run));
            Approval approval = pendingApproval(run);

            service.cancel(ORG, goal.getId());

            assertThat(run.getStatus()).isEqualTo("cancelled");
            assertThat(run.getFailureReason()).isEqualTo(GoalService.GOAL_CANCELLED);
            assertThat(run.getCompletedAt()).isNotNull();
            verify(runs).save(run);
            assertThat(approval.getStatus()).isEqualTo("cancelled");
            assertThat(allApprovals).noneMatch(Approval::isPending);
            assertThat(done.getStatus()).isEqualTo("completed");
            assertThat(waiting.getStatus()).isEqualTo("cancelled");
            assertThat(later.getStatus()).isEqualTo("cancelled");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("leaves an approval that can no longer be decided")
        void oldApprovalCannotBeDecided() {
            Goal goal = work.goal("running");
            Task waiting = work.task(goal, 0, "waiting_approval");
            Run run = runFor(waiting, "waiting_approval");
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(waiting.getId())).thenReturn(Optional.of(run));
            when(runs.findById(run.getId())).thenReturn(Optional.of(run));
            Approval approval = pendingApproval(run);

            service.cancel(ORG, goal.getId());

            RequestContext.setActor(Actor.user(
                    UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L));
            assertThatThrownBy(() -> approvals.decide(ORG, approval.getId(), true, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
            assertThat(run.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("does not rewrite a run that has already finished")
        void finishedRunUntouched() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");
            Run run = runFor(task, "completed");
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));

            service.cancel(ORG, goal.getId());

            assertThat(run.getStatus()).isEqualTo("completed");
            verify(runs, never()).save(any());
            assertThat(task.getStatus()).isEqualTo("cancelled");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("refuses a goal that has already finished")
        void finishedGoalRefused() {
            Goal goal = work.goal("completed");

            assertThatThrownBy(() -> service.cancel(ORG, goal.getId()))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        }
    }

    @Nested
    @DisplayName("running the next task")
    class RunNext {

        @Test
        @DisplayName("starts the claimed task with its own instruction and counts the attempt")
        void startsClaimedTask() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));

            assertThat(service.runNextTask(ORG)).isTrue();

            verify(runner).start(ORG, task.getAgentId(), task.getId(), task.getInstruction(), "task");
            assertThat(task.getStatus()).isEqualTo("running");
            assertThat(task.getAttempt()).isEqualTo(1);
            assertThat(task.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("moves on when another worker already holds the task")
        void skipsTaskHeldElsewhere() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.empty());

            assertThat(service.runNextTask(ORG)).isFalse();

            verify(runner, never()).start(any(), any(), any(), any(), any());
            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("waits while an earlier task in the goal is unfinished")
        void waitsForEarlierTask() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "waiting_approval");
            Task next = work.task(goal, 1, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(next));

            assertThat(service.runNextTask(ORG)).isFalse();

            verify(work.tasks, never()).claim(any());
            verify(runner, never()).start(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("records a run that could not start on the task, under the retry rule")
        void startFailureRecorded() {
            Goal goal = work.goal("running");
            Task task = attempt(work.task(goal, 0, "pending"), 1, 2);
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));
            when(runner.start(any(), any(), any(), any(), any()))
                    .thenThrow(new ApiException(ErrorCode.POLICY_VIOLATION, "That agent is paused."));

            assertThat(service.runNextTask(ORG)).isTrue();

            assertThat(task.getAttempt()).isEqualTo(2);
            assertThat(task.getStatus()).isEqualTo("failed");
            assertThat(task.getFailureReason()).isEqualTo("That agent is paused.");
            assertThat(goal.getStatus()).isEqualTo("failed");
        }
    }

    @Nested
    @DisplayName("repairing stranded tasks")
    class Reconcile {

        @Test
        @DisplayName("completes a task left waiting for approval after its run completed, with the run's answer")
        void completedRun() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_approval");
            Run run = runFor(task, "completed");
            when(work.tasks.findStranded(any())).thenReturn(List.of(task));
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));
            when(steps.findByRunIdOrderByPosition(run.getId())).thenReturn(List.of(
                    RunStep.of(ORG, run.getId(), 0, "note", Map.of("type", "instruction", "content", "Draft it.")),
                    RunStep.of(ORG, run.getId(), 1, "model_call", Map.of("content", "I will draft it.")),
                    RunStep.of(ORG, run.getId(), 2, "approval", Map.of("tool", "gmail.send_message")),
                    RunStep.of(ORG, run.getId(), 3, "model_call", Map.of("content", "The email was sent.")),
                    RunStep.of(ORG, run.getId(), 4, "note", Map.of("type", "other"))));

            assertThat(service.reconcileStrandedTasks(50)).isEqualTo(1);

            assertThat(task.getStatus()).isEqualTo("completed");
            assertThat(task.getResult()).isEqualTo("The email was sent.");
            assertThat(goal.getStatus()).isEqualTo("completed");
        }

        @Test
        @DisplayName("cancels a task whose run was cancelled, and closes its goal as cancelled")
        void cancelledRun() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_approval");
            Run run = runFor(task, "cancelled");
            run.setFailureReason("An approver rejected the action this run needed.");
            when(work.tasks.findStranded(any())).thenReturn(List.of(task));
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));

            assertThat(service.reconcileStrandedTasks(50)).isEqualTo(1);

            assertThat(task.getStatus()).isEqualTo("cancelled");
            assertThat(task.getFailureReason()).isEqualTo("An approver rejected the action this run needed.");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
            verify(steps, never()).findByRunIdOrderByPosition(any());
        }

        @Test
        @DisplayName("leaves a task whose latest run is still active")
        void activeRunLeftAlone() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_approval");
            when(work.tasks.findStranded(any())).thenReturn(List.of(task));
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId()))
                    .thenReturn(Optional.of(runFor(task, "waiting_approval")));

            assertThat(service.reconcileStrandedTasks(50)).isZero();

            assertThat(task.getStatus()).isEqualTo("waiting_approval");
            assertThat(goal.getStatus()).isEqualTo("running");
        }
    }

    private Approval pendingApproval(Run run) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(run.getId());
        approval.setTaskId(run.getTaskId());
        approval.setAgentId(run.getAgentId());
        approval.setTool("gmail.send_message");
        approval.setActionClass("OUTBOUND");
        approval.setSummary("Send the welcome email");
        approval.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        allApprovals.add(approval);
        return approval;
    }
}
