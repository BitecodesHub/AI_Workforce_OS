package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    @DisplayName("keeps starting tasks until the goal service reports nothing more started")
    void stopsWhenNothingMoreStarts() {
        when(goalService.runNextTask(ORG)).thenReturn(true, true, false);

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000).times(3)).runNextTask(ORG);
        verify(goalService, after(300).times(3)).runNextTask(ORG);
    }

    @Test
    @DisplayName("submits at most five tasks in one call, even when every one starts")
    void capsAtFivePerSubmission() {
        when(goalService.runNextTask(ORG)).thenReturn(true);

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000).times(5)).runNextTask(ORG);
        verify(goalService, after(300).times(5)).runNextTask(ORG);
    }

    @Test
    @DisplayName("runs as the requester of the goal behind the next claimable task")
    void usesGoalRequesterAsActor() {
        UUID goalId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setGoalId(goalId);
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setRequestedBy(requester);
        when(tasks.findClaimable(eq(ORG), any())).thenReturn(List.of(task));
        when(goals.findById(goalId)).thenReturn(Optional.of(goal));
        AtomicReference<String> seenActor = new AtomicReference<>();
        when(goalService.runNextTask(ORG)).thenAnswer(call -> {
            seenActor.set(RequestContext.actor().map(Actor::id).orElse(null));
            return false;
        });

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000)).runNextTask(ORG);
        assertThat(seenActor.get()).isEqualTo(requester.toString());
    }

    @Test
    @DisplayName("falls back to the platform actor when nothing is claimable or nobody requested it")
    void fallsBackToSystemActor() {
        when(tasks.findClaimable(eq(ORG), any())).thenReturn(List.of());
        AtomicReference<String> seenActor = new AtomicReference<>();
        when(goalService.runNextTask(ORG)).thenAnswer(call -> {
            seenActor.set(RequestContext.actor().map(Actor::id).orElse(null));
            return false;
        });

        executor.submitNextTasks(ORG);

        verify(goalService, timeout(2_000)).runNextTask(ORG);
        assertThat(seenActor.get()).isEqualTo("system");
    }

    @Test
    @DisplayName("a failure while submitting is logged rather than thrown, and stops that submission")
    void exceptionDuringSubmissionIsSwallowed() {
        when(goalService.runNextTask(ORG)).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> executor.submitNextTasks(ORG)).doesNotThrowAnyException();

        verify(goalService, timeout(2_000).times(1)).runNextTask(ORG);
        verify(goalService, after(300).times(1)).runNextTask(ORG);
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
}
