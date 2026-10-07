package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Submissions run on a thread of their own, so every assertion here waits for that thread rather
 * than for the call that started it - none of these ever block on the work itself.
 */
class RunExecutorTest {

    private GoalService goalService;
    private AgentRunner runner;
    private Tasks tasks;
    private Goals goals;
    private Runs runs;
    private RunExecutor executor;

    @BeforeEach
    void setUp() {
        goalService = mock(GoalService.class);
        runner = mock(AgentRunner.class);
        tasks = mock(Tasks.class);
        goals = mock(Goals.class);
        runs = mock(Runs.class);
        executor = new RunExecutor(goalService, runner, tasks, goals, runs);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static GoalService.TaskStart claimed() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setGoalId(UUID.randomUUID());
        return new GoalService.TaskStart(task, List.of());
    }

    @Test
    @DisplayName("keeps claiming until nothing more is ready, and starts each claimed task")
    void stopsWhenNothingMoreIsClaimed() {
        GoalService.TaskStart first = claimed();
        GoalService.TaskStart second = claimed();
        when(goalService.claimNextTask(ORG))
                .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000)).startClaimed(ORG, first);
        verify(goalService, timeout(2_000)).startClaimed(ORG, second);
        verify(goalService, after(300).times(3)).claimNextTask(ORG);
    }

    @Test
    @DisplayName("claims at most five tasks in one pass, even when every one is ready")
    void capsAtFivePerPass() {
        when(goalService.claimNextTask(ORG)).thenAnswer(call -> Optional.of(claimed()));

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000).times(5)).startClaimed(eq(ORG), any());
        verify(goalService, after(300).times(5)).claimNextTask(ORG);
    }

    @Test
    @DisplayName("each claimed task starts on a thread of its own, so a long run never holds up the next")
    void startsEachTaskOnItsOwnThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(goalService.claimNextTask(ORG))
                .thenReturn(Optional.of(claimed()), Optional.of(claimed()), Optional.empty());
        doAnswer(call -> {
                    release.await(2, TimeUnit.SECONDS);
                    return null;
                })
                .when(goalService)
                .startClaimed(eq(ORG), any());

        executor.submitNextTasks(ORG);

        // Both runs are under way while the first is still working.
        verify(goalService, timeout(1_000).times(2)).startClaimed(eq(ORG), any());
        release.countDown();
    }

    @Test
    @DisplayName("the pass claims as the platform, whoever's request triggered it; attribution is per task")
    void dispatchRunsAsThePlatform() throws Exception {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));
        // Completed by the stub itself: Mockito records the call before the stub runs, so a passing
        // verify does not yet mean the actor has been seen.
        CompletableFuture<String> seenActor = new CompletableFuture<>();
        when(goalService.claimNextTask(ORG)).thenAnswer(call -> {
            seenActor.complete(RequestContext.actor().map(Actor::id).orElse(null));
            return Optional.empty();
        });

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000)).claimNextTask(ORG);
        assertThat(seenActor.get(2, TimeUnit.SECONDS)).isEqualTo("system");
    }

    @Test
    @DisplayName("a request made during a pass is not lost, and never starts a second pass beside it")
    void requestDuringAPassRunsAfterIt() throws Exception {
        CountDownLatch inPass = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        when(goalService.claimNextTask(ORG)).thenAnswer(call -> {
            mostAtOnce.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            inPass.countDown();
            release.await(2, TimeUnit.SECONDS);
            concurrent.decrementAndGet();
            return Optional.empty();
        });

        executor.submitNextTasks(ORG);
        assertThat(inPass.await(2, TimeUnit.SECONDS)).isTrue();
        executor.submitNextTasks(ORG);
        executor.submitNextTasks(ORG);
        release.countDown();

        // The first pass, then exactly one more for the two requests that arrived during it.
        verify(goalService, timeout(2_000).times(2)).claimNextTask(ORG);
        verify(goalService, after(300).times(2)).claimNextTask(ORG);
        assertThat(mostAtOnce.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure while claiming is logged rather than thrown, and frees the workspace for the next pass")
    void exceptionDuringDispatchIsSwallowed() {
        when(goalService.claimNextTask(ORG))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(Optional.empty());

        assertThatCode(() -> executor.submitNextTasks(ORG)).doesNotThrowAnyException();
        verify(goalService, timeout(2_000).times(1)).claimNextTask(ORG);
        verify(goalService, after(300).times(1)).claimNextTask(ORG);

        executor.submitNextTasks(ORG);
        verify(goalService, timeout(2_000).times(2)).claimNextTask(ORG);
    }

    @Test
    @DisplayName("a task settling in a workspace dispatches that workspace at once")
    void settledTaskDispatches() {
        when(goalService.claimNextTask(ORG)).thenReturn(Optional.empty());

        executor.onTaskSettled(new TaskSettledEvent(ORG));

        verify(goalService, timeout(2_000)).claimNextTask(ORG);
    }

    @Test
    @DisplayName("drives a prepared run on a thread of its own, as the person who started it")
    void submitDriveRunsAsTheStarter() throws Exception {
        UUID runId = UUID.randomUUID();
        Actor starter = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L);
        // Completed by the stub itself: Mockito records the call before the stub runs, so a passing
        // verify does not yet mean the actor has been seen.
        CompletableFuture<String> seenActor = new CompletableFuture<>();
        when(runner.drive(ORG, runId)).thenAnswer(call -> {
            seenActor.complete(RequestContext.actor().map(Actor::id).orElse(null));
            return null;
        });

        executor.submitDrive(ORG, runId, starter);

        verify(runner, timeout(2_000)).drive(ORG, runId);
        assertThat(seenActor.get(2, TimeUnit.SECONDS)).isEqualTo(starter.id());
    }

    @Test
    @DisplayName("a drive that fails is logged rather than thrown")
    void submitDriveFailureIsSwallowed() {
        UUID runId = UUID.randomUUID();
        when(runner.drive(ORG, runId)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> executor.submitDrive(ORG, runId, Actor.SYSTEM)).doesNotThrowAnyException();

        verify(runner, timeout(2_000)).drive(ORG, runId);
    }

    @Test
    @DisplayName("a run started directly, with no goal behind it, resumes as the approver who let it continue")
    void directRunResumesAsTheApprover() throws Exception {
        UUID runId = UUID.randomUUID();
        Run run = new Run();
        run.setId(runId);
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));
        Actor approver = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L);
        // Completed by the stub itself: Mockito records the call before the stub runs, so a passing
        // verify does not yet mean the actor has been seen.
        CompletableFuture<String> seenActor = new CompletableFuture<>();
        when(runner.resume(ORG, runId)).thenAnswer(call -> {
            seenActor.complete(RequestContext.actor().map(Actor::id).orElse(null));
            return null;
        });

        executor.submitResume(ORG, runId, approver);

        verify(runner, timeout(2_000)).resume(ORG, runId);
        assertThat(seenActor.get(2, TimeUnit.SECONDS)).isEqualTo(approver.id());
    }

    @Test
    @DisplayName("resumes the run exactly once, as the requester of the goal behind its task")
    void submitResumeInvokesRunnerOnce() {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Run run = new Run();
        run.setId(runId);
        run.setTaskId(taskId);
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));
        Task task = new Task();
        task.setId(taskId);
        task.setGoalId(goalId);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setRequestedBy(requester);
        when(goals.findById(goalId)).thenReturn(Optional.of(goal));

        executor.submitResume(ORG, runId);

        verify(runner, timeout(2_000).times(1)).resume(ORG, runId);
        verify(runner, after(300).times(1)).resume(ORG, runId);
    }

    @Test
    @DisplayName("resuming a run with no task on record still resumes, as the platform actor")
    void submitResumeWithoutTaskFallsBackToSystem() {
        UUID runId = UUID.randomUUID();
        Run run = new Run();
        run.setId(runId);
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.of(run));

        executor.submitResume(ORG, runId);

        verify(runner, timeout(2_000).times(1)).resume(ORG, runId);
    }

    @Test
    @DisplayName("a second resume of a run already being resumed here is ignored until the first ends")
    void submitResumeIgnoresDuplicateWhileInFlight() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.empty());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runner.resume(ORG, runId)).thenAnswer(call -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });

        executor.submitResume(ORG, runId);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        executor.submitResume(ORG, runId);
        release.countDown();

        verify(runner, after(300).times(1)).resume(ORG, runId);

        // Once the first has finished, the run can be resumed again.
        executor.submitResume(ORG, runId);
        verify(runner, timeout(2_000).times(2)).resume(ORG, runId);
    }

    @Test
    @DisplayName("a submission that fails before it starts releases the run, so the next one still resumes it")
    void submitResumeFailureDoesNotLeakInFlightId() {
        UUID runId = UUID.randomUUID();
        when(runs.findByIdAndOrgId(runId, ORG))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(Optional.empty());

        assertThatCode(() -> executor.submitResume(ORG, runId)).doesNotThrowAnyException();
        executor.submitResume(ORG, runId);

        verify(runner, timeout(2_000).times(1)).resume(ORG, runId);
    }

    @Test
    @DisplayName("a resume that finds the run no longer waiting is an ordinary outcome, and releases the run")
    void conflictFromResumeIsNotAnError() {
        UUID runId = UUID.randomUUID();
        when(runs.findByIdAndOrgId(runId, ORG)).thenReturn(Optional.empty());
        when(runner.resume(ORG, runId))
                .thenThrow(new ApiException(ErrorCode.CONFLICT, "That run is not waiting for a person."))
                .thenReturn(null);

        assertThatCode(() -> executor.submitResume(ORG, runId)).doesNotThrowAnyException();
        verify(runner, timeout(2_000).times(1)).resume(ORG, runId);
        verify(runner, after(200).times(1)).resume(ORG, runId);

        executor.submitResume(ORG, runId);
        verify(runner, timeout(2_000).times(2)).resume(ORG, runId);
    }
}
