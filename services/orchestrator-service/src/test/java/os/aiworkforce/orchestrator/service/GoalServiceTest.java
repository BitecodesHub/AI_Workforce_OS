// @find: tests for goal service, goals, tasks, dependencies, create goal, retry goal, cancel goal, concurrency cap, claim task
// @what: Unit and integration tests (45 cases) for goal service, for example: stops run and approval; old approval cannot be decided; finished run untouched; finished goal refused.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;
import static os.aiworkforce.orchestrator.service.WorkFixture.attempt;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

class GoalServiceTest {

    private WorkFixture work;
    private Runs runs;
    private RunSteps steps;
    private AgentRunner runner;
    private Approvals approvalRows;
    private final List<Approval> allApprovals = new ArrayList<>();
    private ApprovalService approvals;
    private Agents agents;
    private RunExecutor executor;
    private RunQuestions questionRows;
    private LifecycleAnnouncer announcer;
    private AuditClient audit;
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
        // A decision locks the row first; the in-memory rows answer the same either way.
        lenient().when(approvalRows.lockByIdAndOrgId(any(), any())).thenAnswer(call -> allApprovals.stream()
                .filter(approval -> approval.getId().equals(call.getArgument(0)))
                .findFirst());
        // The bulk withdrawal, applied to the in-memory approvals the way the update would.
        lenient().when(approvalRows.withdrawPending(any(), any())).thenAnswer(call -> {
            List<Approval> pending = allApprovals.stream()
                    .filter(approval -> approval.getRunId().equals(call.getArgument(0)) && approval.isPending())
                    .toList();
            pending.forEach(approval -> approval.setStatus("cancelled"));
            return pending.size();
        });

        TaskProgress progress = new TaskProgress(work.tasks, work.goals, List.of());
        approvals =
                new ApprovalService(approvalRows, runs, new ObjectMapper(), mock(AuditClient.class), progress, null);
        agents = mock(Agents.class);
        executor = mock(RunExecutor.class);
        questionRows = mock(RunQuestions.class);
        QuestionService questions = new QuestionService(
                questionRows,
                runs,
                work.tasks,
                work.goals,
                new ObjectMapper(),
                mock(AuditClient.class),
                Duration.ofHours(24));
        announcer = mock(LifecycleAnnouncer.class);
        audit = mock(AuditClient.class);
        service = new GoalService(
                work.goals,
                work.tasks,
                runs,
                steps,
                runner,
                approvals,
                progress,
                agents,
                executor,
                questions,
                announcer,
                audit);
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
            when(runs.lockActiveByGoal(goal.getId())).thenReturn(List.of(run));
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
            when(runs.lockActiveByGoal(goal.getId())).thenReturn(List.of(run));
            when(runs.findById(run.getId())).thenReturn(Optional.of(run));
            Approval approval = pendingApproval(run);

            service.cancel(ORG, goal.getId());

            RequestContext.setActor(
                    Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L));
            assertThatThrownBy(() -> approvals.decide(ORG, approval.getId(), true, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
            assertThat(run.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("does not rewrite a run that has already finished")
        void finishedRunUntouched() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");
            Run run = runFor(task, "completed");
            // A finished run is not active, so the lock never returns it.
            when(runs.lockActiveByGoal(goal.getId())).thenReturn(List.of());

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
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        }

        @Test
        @DisplayName("withdraws the question and the approvals of every active run with bulk updates")
        void cancelWithdrawsQuestions() {
            Goal goal = work.goal("running");
            Task asking = work.task(goal, 0, "waiting_input");
            Run run = runFor(asking, "waiting_input");
            when(runs.lockActiveByGoal(goal.getId())).thenReturn(List.of(run));
            when(questionRows.withdrawPending(eq(run.getId()), any(), any())).thenReturn(1);

            GoalService.CancelCounts counts = service.cancel(ORG, goal.getId(), "Stopped from the chat.");

            verify(questionRows).withdrawPending(eq(run.getId()), eq(GoalService.GOAL_CANCELLED), any());
            verify(approvalRows).withdrawPending(eq(run.getId()), any());
            verify(questionRows, never()).findByRunIdAndStatus(any(), any());
            verify(approvalRows, never()).findByRunIdAndStatus(any(), any());
            assertThat(counts).isEqualTo(new GoalService.CancelCounts(1, 1, 0, 1));
            assertThat(run.getStatus()).isEqualTo("cancelled");
            verify(announcer).goalCancelled(goal.getId(), "Stopped from the chat.");
        }

        @Test
        @DisplayName("locks the goal's runs before it reads the tasks, following the lock order")
        void cancelLocksRunsBeforeLoadingTasks() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "running");

            service.cancel(ORG, goal.getId());

            InOrder order = inOrder(runs, work.tasks);
            order.verify(runs).lockActiveByGoal(goal.getId());
            order.verify(work.tasks).findByGoalIdOrderByPosition(goal.getId());
        }

        @Test
        @DisplayName("records the reason on every task it cancels")
        void cancelSetsReasonOnTasks() {
            Goal goal = work.goal("running");
            Task done = work.task(goal, 0, "completed");
            Task running = work.task(goal, 1, "running");
            Task later = work.task(goal, 2, "pending");

            service.cancel(ORG, goal.getId(), "Stopped from the chat.");

            assertThat(running.getFailureReason()).isEqualTo("Stopped from the chat.");
            assertThat(later.getFailureReason()).isEqualTo("Stopped from the chat.");
            assertThat(done.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("records goal.cancel in the audit log as the person who stopped it, once it commits")
        @SuppressWarnings("unchecked")
        void cancelIsAudited() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "running");
            Actor manager = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:cancel"), 0L);
            RequestContext.setActor(manager);

            service.cancel(ORG, goal.getId(), "Stopped from the chat.");

            ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
            verify(audit)
                    .record(
                            eq(ORG),
                            eq(manager),
                            eq("goal.cancel"),
                            eq("goal"),
                            eq(goal.getId().toString()),
                            eq("succeeded"),
                            detail.capture());
            assertThat(detail.getValue()).containsEntry("reason", "Stopped from the chat.").containsEntry("tasks", 1);
        }

        @Test
        @DisplayName("cancelIfActive does nothing for a goal that has already finished, or is missing")
        void cancelIfActiveSkipsFinishedGoal() {
            Goal goal = work.goal("completed");

            assertThat(service.cancelIfActive(ORG, goal.getId(), "The conversation was deleted."))
                    .isEmpty();
            assertThat(service.cancelIfActive(ORG, UUID.randomUUID(), "The conversation was deleted."))
                    .isEmpty();

            verify(runs, never()).lockActiveByGoal(any());
            assertThat(goal.getStatus()).isEqualTo("completed");
        }
    }

    @Nested
    @DisplayName("stopping one run")
    class StopRun {

        @Test
        @DisplayName("locks the run, withdraws its approvals and question, and cancels its task")
        void stopRunWithdrawsApprovalsAndQuestions() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_input");
            Run run = runFor(task, "waiting_input");
            when(runs.lockByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

            GoalService.CancelCounts counts = service.stopRun(ORG, run.getId(), "A person stopped this run.");

            assertThat(run.getStatus()).isEqualTo("cancelled");
            assertThat(run.getFailureReason()).isEqualTo("A person stopped this run.");
            verify(approvalRows).withdrawPending(eq(run.getId()), any());
            verify(questionRows).withdrawPending(eq(run.getId()), eq("A person stopped this run."), any());
            assertThat(task.getStatus()).isEqualTo("cancelled");
            assertThat(goal.getStatus()).isEqualTo("cancelled");
            assertThat(counts.tasks()).isEqualTo(1);
            assertThat(counts.runs()).isEqualTo(1);
        }

        @Test
        @DisplayName("records run.stop in the audit log, as succeeded")
        void stopRunIsAudited() {
            Run run = runFor(null, "running");
            when(runs.lockByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

            service.stopRun(ORG, run.getId(), "A person stopped this run.");

            verify(audit)
                    .record(
                            eq(ORG),
                            any(),
                            eq("run.stop"),
                            eq("run"),
                            eq(run.getId().toString()),
                            eq("succeeded"),
                            any());
        }

        @Test
        @DisplayName("stopRunIfActive does nothing for a run that has already finished")
        void stopRunIfActiveSkipsFinishedRun() {
            Run run = runFor(null, "completed");
            when(runs.lockByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

            assertThat(service.stopRunIfActive(ORG, run.getId(), "Stopped.")).isEmpty();

            assertThat(run.getStatus()).isEqualTo("completed");
            verify(approvalRows, never()).withdrawPending(any(), any());
            verify(questionRows, never()).withdrawPending(any(), any(), any());
        }

        @Test
        @DisplayName("stopRun refuses a run that has already finished")
        void stopRunRefusesFinishedRun() {
            Run run = runFor(null, "failed");
            when(runs.lockByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

            assertThatThrownBy(() -> service.stopRun(ORG, run.getId(), "Stopped."))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        }
    }

    @Nested
    @DisplayName("trying a goal again")
    class Retry {

        private final UUID requester = UUID.randomUUID();

        @Test
        @DisplayName("resets every unfinished step from the first failed one, and starts the goal again")
        void retryResetsFromFirstFailedStep() {
            Goal goal = work.goal("failed");
            goal.setRequestedBy(requester);
            Task done = work.task(goal, 0, "completed");
            Task failed = attempt(work.task(goal, 1, "failed"), 2, 2);
            failed.setFailureReason("The model's answer was cut short.");
            failed.setStartedAt(Instant.now());
            failed.setCompletedAt(Instant.now());
            Task skipped = work.task(goal, 2, "skipped");

            GoalService.RetryResult result = service.retry(ORG, goal.getId(), person(requester));

            assertThat(result.fromTask()).isEqualTo(failed);
            assertThat(failed.getStatus()).isEqualTo("pending");
            assertThat(failed.getAttempt()).isZero();
            assertThat(failed.getFailureReason()).isNull();
            assertThat(failed.getStartedAt()).isNull();
            assertThat(failed.getCompletedAt()).isNull();
            assertThat(skipped.getStatus()).isEqualTo("pending");
            assertThat(done.getStatus()).isEqualTo("completed");
            assertThat(goal.getStatus()).isEqualTo("running");
            assertThat(goal.getCompletedAt()).isNull();
            verify(executor).submitNextTasks(ORG);
            verify(announcer).goalRetried(goal.getId(), failed.getId());
        }

        @Test
        @DisplayName("keeps the results of steps that completed, before and after the one that failed")
        void retryKeepsCompletedSteps() {
            Goal goal = work.goal("cancelled");
            goal.setRequestedBy(requester);
            Task first = work.task(goal, 0, "completed");
            first.setResult("Researched the market.");
            Task cancelled = work.task(goal, 1, "cancelled");
            Task side = work.task(goal, 2, "completed");
            side.setResult("Drafted the outline.");

            service.retry(ORG, goal.getId(), person(requester));

            assertThat(first.getStatus()).isEqualTo("completed");
            assertThat(first.getResult()).isEqualTo("Researched the market.");
            assertThat(side.getStatus()).isEqualTo("completed");
            assertThat(side.getResult()).isEqualTo("Drafted the outline.");
            assertThat(cancelled.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("refuses a goal that is still running")
        void retryRefusesRunningGoal() {
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);
            work.task(goal, 0, "running");

            assertThatThrownBy(() -> service.retry(ORG, goal.getId(), person(requester)))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
            verify(executor, never()).submitNextTasks(any());
        }

        @Test
        @DisplayName("only the requester, or someone who can cancel work, may retry it")
        void retryRequiresRequesterOrCancel() {
            Goal goal = work.goal("failed");
            goal.setRequestedBy(requester);
            work.task(goal, 0, "failed");

            assertThatThrownBy(() -> service.retry(ORG, goal.getId(), person(UUID.randomUUID(), "task:create")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                        assertThat(e.details()).containsEntry("requiredPermission", "task:cancel");
                    });
            assertThat(goal.getStatus()).isEqualTo("failed");

            assertThatCode(() -> service.retry(ORG, goal.getId(), person(requester)))
                    .doesNotThrowAnyException();
            assertThat(goal.getStatus()).isEqualTo("running");

            Goal other = work.goal("failed");
            other.setRequestedBy(requester);
            work.task(other, 0, "failed");
            assertThatCode(() ->
                            service.retry(ORG, other.getId(), person(UUID.randomUUID(), "task:create", "task:cancel")))
                    .doesNotThrowAnyException();
            assertThat(other.getStatus()).isEqualTo("running");
        }

        private Actor person(UUID id, String... permissions) {
            Set<String> held = permissions.length == 0 ? Set.of("task:create") : Set.of(permissions);
            return Actor.user(id.toString(), ORG.toString(), "role", held, 0L);
        }
    }

    @Nested
    @DisplayName("who may stop a goal")
    class CanStop {

        private final UUID requester = UUID.randomUUID();

        @Test
        @DisplayName("the person who asked for it may stop it, without task:cancel")
        void requesterMayStop() {
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);

            assertThatCode(() -> service.requireCanStop(goal, person(requester, "task:create")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("another employee may not, and is told which permission it takes")
        void anotherEmployeeIsRefused() {
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);

            assertThatThrownBy(() -> service.requireCanStop(goal, person(UUID.randomUUID(), "task:create")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(403);
                        assertThat(e.details()).containsEntry("requiredPermission", "task:cancel");
                    });
        }

        @Test
        @DisplayName("a manager who can cancel work may stop anyone's goal")
        void managerMayStop() {
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);

            assertThatCode(() -> service.requireCanStop(goal, person(UUID.randomUUID(), "task:create", "task:cancel")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a goal that has already finished is a conflict, even for its requester")
        void finishedGoalIsAConflict() {
            Goal goal = work.goal("completed");
            goal.setRequestedBy(requester);

            assertThatThrownBy(() -> service.requireCanStop(goal, person(requester, "task:create")))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        }

        private Actor person(UUID id, String... permissions) {
            return Actor.user(id.toString(), ORG.toString(), "role", Set.of(permissions), 0L);
        }
    }

    @Test
    @DisplayName("validate refuses more than five tasks from a chat message before anything is written")
    void validateRejectsTooManyChatTasks() {
        List<GoalService.NewTask> chatTasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chatTasks.add(new GoalService.NewTask(UUID.randomUUID(), "Task " + i, "Do it", List.of()));
        }

        assertThatThrownBy(() ->
                        service.validate(new GoalService.NewGoal("Chat goal", "", null, "chat", null, null, chatTasks)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(work.goals, never()).save(any());
    }

    @Nested
    @DisplayName("creating a goal")
    class CreateGoal {

        @Test
        @DisplayName("sets source, links and requester, and maps dependsOn positions to task ids")
        void createGoalSetsFieldsAndDependencies() {
            UUID conversationId = UUID.randomUUID();
            UUID requestedBy = UUID.randomUUID();
            GoalService.NewGoal spec = new GoalService.NewGoal(
                    "Answer the customer",
                    "",
                    requestedBy,
                    "chat",
                    conversationId,
                    null,
                    List.of(
                            new GoalService.NewTask(UUID.randomUUID(), "Research", "Look into it", List.of()),
                            new GoalService.NewTask(UUID.randomUUID(), "Reply", "Write back", List.of(0))));

            Goal goal = service.createGoal(ORG, spec, false);

            assertThat(goal.getSource()).isEqualTo("chat");
            assertThat(goal.getConversationId()).isEqualTo(conversationId);
            assertThat(goal.getRequestedBy()).isEqualTo(requestedBy);
            assertThat(goal.getStatus()).isEqualTo("running");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Task>> saved = ArgumentCaptor.forClass(List.class);
            verify(work.tasks).saveAll(saved.capture());
            List<Task> created = saved.getValue();
            assertThat(created).hasSize(2);
            assertThat(created.get(1).getDependsOn())
                    .containsExactly(created.get(0).getId());
            verify(executor, never()).submitNextTasks(any());
        }

        @Test
        @DisplayName("submits the goal's tasks through the run executor when asked to run without waiting")
        void asyncGoalSubmitsThroughExecutor() {
            GoalService.NewGoal spec = new GoalService.NewGoal(
                    "Draft a reply",
                    "",
                    null,
                    "chat",
                    null,
                    null,
                    List.of(new GoalService.NewTask(UUID.randomUUID(), "Reply", "Write it", List.of())));

            service.createGoal(ORG, spec, true);

            verify(executor).submitNextTasks(ORG);
        }

        @Test
        @DisplayName("refuses more than five tasks started from a chat message")
        void chatGuardLimitsTaskCount() {
            List<GoalService.NewTask> chatTasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                chatTasks.add(new GoalService.NewTask(UUID.randomUUID(), "Task " + i, "Do it", List.of()));
            }
            GoalService.NewGoal spec = new GoalService.NewGoal("Chat goal", "", null, "chat", null, null, chatTasks);

            assertThatThrownBy(() -> service.createGoal(ORG, spec, false))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("a manual goal may still hold more than five tasks")
        void manualGoalIgnoresChatLimit() {
            List<GoalService.NewTask> manualTasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                manualTasks.add(new GoalService.NewTask(UUID.randomUUID(), "Task " + i, "Do it", List.of()));
            }
            GoalService.NewGoal spec = new GoalService.NewGoal("Plan", "", null, "manual", null, null, manualTasks);

            assertThatCode(() -> service.createGoal(ORG, spec, false)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a chain where one agent appears more than twice")
        void agentRepeatGuardRefuses() {
            UUID agentId = UUID.randomUUID();
            List<GoalService.NewTask> repeated = List.of(
                    new GoalService.NewTask(agentId, "One", "Do", List.of()),
                    new GoalService.NewTask(agentId, "Two", "Do", List.of()),
                    new GoalService.NewTask(agentId, "Three", "Do", List.of()));
            GoalService.NewGoal spec = new GoalService.NewGoal("Loop", "", null, "manual", null, null, repeated);

            assertThatThrownBy(() -> service.createGoal(ORG, spec, false))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
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
        @DisplayName("hands off a completed predecessor's result, and records it as a hand-off")
        void handsOffCompletedPredecessor() {
            Goal goal = work.goal("running");
            Task done = work.task(goal, 0, "completed");
            done.setResult("Drafted the welcome email.");
            Task next = work.task(goal, 1, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(next));
            when(work.tasks.claim(next.getId())).thenReturn(Optional.of(next));

            assertThat(service.runNextTask(ORG)).isTrue();

            ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> handoffs = ArgumentCaptor.forClass(List.class);
            verify(runner)
                    .start(
                            eq(ORG),
                            eq(next.getAgentId()),
                            eq(next.getId()),
                            instruction.capture(),
                            eq("task"),
                            handoffs.capture());
            assertThat(instruction.getValue())
                    .contains("Work already done for this request:")
                    .contains("Drafted the welcome email.")
                    .contains("Your part:")
                    .contains(next.getInstruction());
            assertThat(handoffs.getValue()).hasSize(1);
            assertThat(handoffs.getValue().getFirst())
                    .containsEntry("fromTaskId", done.getId().toString())
                    .containsEntry("summary", "Drafted the welcome email.");
        }

        @Test
        @DisplayName("skips a task whose dependency failed rather than leaving it pending forever")
        void skipsTaskBlockedByFailedDependency() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "failed");
            Task next = work.task(goal, 1, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(next));
            when(work.tasks.claim(next.getId())).thenReturn(Optional.of(next));

            assertThat(service.runNextTask(ORG)).isFalse();

            assertThat(next.getStatus()).isEqualTo("skipped");
            assertThat(next.getFailureReason()).isEqualTo(TaskProgress.EARLIER_TASK_UNFINISHED);
            verify(runner, never()).start(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("waits for the tasks named in dependsOn rather than every earlier position")
        void explicitDependsOnOverridesPositionOrder() {
            Goal goal = work.goal("running");
            work.task(goal, 0, "pending");
            Task dependency = work.task(goal, 1, "completed");
            dependency.setResult("Researched the competitors.");
            Task next = work.task(goal, 2, "pending");
            next.setDependsOn(List.of(dependency.getId()));
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(next));
            when(work.tasks.claim(next.getId())).thenReturn(Optional.of(next));

            assertThat(service.runNextTask(ORG)).isTrue();

            verify(runner).start(eq(ORG), eq(next.getAgentId()), eq(next.getId()), anyString(), eq("task"), any());
        }

        @Test
        @DisplayName("starts a claimed task as its own goal's requester, whoever's request claimed it")
        void startsAsTheGoalsRequester() {
            UUID requester = UUID.randomUUID();
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));
            Actor someoneElse =
                    Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:create"), 0L);
            RequestContext.setActor(someoneElse);
            List<String> seen = new ArrayList<>();
            when(runner.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                seen.add(RequestContext.actor().map(Actor::id).orElse(null));
                return null;
            });

            assertThat(service.runNextTask(ORG)).isTrue();

            assertThat(seen).containsExactly(requester.toString());
            // The caller's own identity is back once the start returns.
            assertThat(RequestContext.actor()).contains(someoneElse);
        }

        @Test
        @DisplayName("a task whose goal nobody is on record for starts as the platform")
        void startsAsThePlatformWithoutARequester() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));
            RequestContext.setActor(
                    Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("task:create"), 0L));
            List<String> seen = new ArrayList<>();
            when(runner.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                seen.add(RequestContext.actor().map(Actor::id).orElse(null));
                return null;
            });

            service.runNextTask(ORG);

            assertThat(seen).containsExactly("system");
        }

        @Test
        @DisplayName("a start the runner refuses is recorded as the goal's requester too")
        void startFailureRecordedAsTheRequester() {
            UUID requester = UUID.randomUUID();
            Goal goal = work.goal("running");
            goal.setRequestedBy(requester);
            Task task = attempt(work.task(goal, 0, "pending"), 0, 2);
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));
            when(runner.start(any(), any(), any(), any(), any()))
                    .thenThrow(new ApiException(ErrorCode.POLICY_VIOLATION, "That agent is paused."));
            List<String> seen = new ArrayList<>();
            when(work.tasks.save(task)).thenAnswer(call -> {
                seen.add(RequestContext.actor().map(Actor::id).orElse(null));
                return task;
            });

            service.runNextTask(ORG);

            assertThat(task.getFailureReason()).isEqualTo("That agent is paused.");
            assertThat(seen).last().isEqualTo(requester.toString());
        }

        @Test
        @DisplayName("holds every task back while the workspace has its cap of four running")
        void capHoldsTasksBack() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.countByOrgIdAndStatus(ORG, "running")).thenReturn(4L);
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));

            assertThat(service.claimNextTask(ORG)).isEmpty();
            assertThat(service.runNextTask(ORG)).isFalse();

            verify(work.tasks, never()).claim(any());
            verify(runner, never()).start(any(), any(), any(), any(), any());
            assertThat(task.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("starts the next task once fewer than the cap are running")
        void capLetsTheNextOneThrough() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.countByOrgIdAndStatus(ORG, "running")).thenReturn(3L);
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
            when(work.tasks.claim(task.getId())).thenReturn(Optional.of(task));

            assertThat(service.runNextTask(ORG)).isTrue();

            assertThat(task.getStatus()).isEqualTo("running");
        }

        @Test
        @DisplayName("the cap is the workspace's runtime setting when one is stored")
        void capFollowsTheRuntimeSetting() {
            RuntimeConfigService settings = mock(RuntimeConfigService.class);
            when(settings.getInt(GoalService.MAX_CONCURRENT_RUNS, ORG.toString())).thenReturn(2);
            service.setRuntimeConfig(settings);
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "pending");
            when(work.tasks.countByOrgIdAndStatus(ORG, "running")).thenReturn(2L);
            when(work.tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));

            assertThat(service.claimNextTask(ORG)).isEmpty();

            verify(settings).register(List.of(GoalService.MAX_CONCURRENT_RUNS));
            verify(work.tasks, never()).claim(any());
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
            when(steps.findByRunIdOrderByPosition(run.getId()))
                    .thenReturn(List.of(
                            RunStep.of(
                                    ORG, run.getId(), 0, "note", Map.of("type", "instruction", "content", "Draft it.")),
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
        @DisplayName("leaves a task waiting for an answer while its run waits too")
        void reconcileSkipsWaitingInput() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "waiting_input");
            when(work.tasks.findStranded(any())).thenReturn(List.of(task));
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId()))
                    .thenReturn(Optional.of(runFor(task, "waiting_input")));

            assertThat(service.reconcileStrandedTasks(50)).isZero();

            assertThat(task.getStatus()).isEqualTo("waiting_input");
            assertThat(goal.getStatus()).isEqualTo("running");
        }

        @Test
        @DisplayName("ignores a finished run from an attempt before the task's current one")
        void reconcileIgnoresRunOlderThanTaskStart() {
            Goal goal = work.goal("running");
            Task task = work.task(goal, 0, "running");
            task.setStartedAt(Instant.now());
            Run old = runFor(task, "failed");
            old.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
            when(work.tasks.findStranded(any())).thenReturn(List.of(task));
            when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(old));

            assertThat(service.reconcileStrandedTasks(50)).isZero();

            assertThat(task.getStatus()).isEqualTo("running");
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
