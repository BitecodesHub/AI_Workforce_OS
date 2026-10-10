// @find: run executor, background run, virtual thread, async run, submit drive, submit resume, dispatch next tasks, task settled, start work without waiting
// @what: Hands goal, run and resume work to virtual threads so requests answer immediately.
// @flow: Called by controllers, GoalService, ApprovalService; calls AgentRunner and GoalService
package os.aiworkforce.orchestrator.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
 * Hands goal, run and resume work to a virtual thread, so the request that triggered it can
 * answer before the work is done.
 *
 * <p>Giving an agent a task, creating a goal, approving an action, answering a question, a chat
 * message and a schedule firing all need to start work without waiting for it: the person is
 * looking at a reply, and the gateway stops waiting after a minute, while an agent run can take
 * many. Everything submitted here runs on a thread of its own and this class returns immediately;
 * the run's own progress is then visible the ordinary way, by polling the goal, the task or the
 * run.
 *
 * <p>Attribution is decided per run, never per submission. A task's run is started as its own
 * goal's requester by {@link GoalService#startClaimed}, whoever's request or event caused the
 * dispatch; a resumed run as the requester of the goal behind it; a direct run as the person who
 * started it.
 *
 * <p>Whatever the submitting thread had in its logging and trace context goes with the work: the
 * executor is wrapped so a run's log lines carry the request that started it, and its spans hang
 * from that request's trace. On top of that each submission names its own run, goal, agent and
 * workspace in the logging context for as long as it runs (see {@link RunLogContext}), so one
 * run's lines can be pulled out of a busy log.
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** The most tasks one dispatch pass claims; the workspace's cap usually stops it sooner. */
    private static final int MAX_TASKS_PER_SUBMISSION = 5;

    private final GoalService goalService;
    private final AgentRunner runner;
    private final Tasks tasks;
    private final Goals goals;
    private final Runs runs;
    private final ExecutorService executor = contextual(Executors.newVirtualThreadPerTaskExecutor());
    /**
     * Runs with a resume in flight on this instance, so an answer's own submission and the sweep
     * do not both start one. Only saves work: the claim in the database decides which resume
     * drives a run, on this instance or any other.
     */
    private final Set<UUID> resuming = ConcurrentHashMap.newKeySet();
    /**
     * Workspaces with a dispatch pass in flight on this instance, so the goal sweep's ticks and a
     * burst of settled tasks do not pile up passes for one workspace. Only saves work: the claim
     * in the database decides which pass starts a task.
     */
    private final Set<UUID> dispatching = ConcurrentHashMap.newKeySet();
    /** Workspaces asked for another pass, so a request that arrives mid-pass is never lost. */
    private final Set<UUID> dispatchAgain = ConcurrentHashMap.newKeySet();

    /**
     * Wraps an executor so the logging and trace context of whoever submits goes with the task.
     *
     * <p>The logging context is not carried by Micrometer on its own, so it is registered here, in
     * a registry of this class's own rather than the global one: nothing else in the process is
     * changed by it.
     */
    static ExecutorService contextual(ExecutorService delegate) {
        ContextRegistry registry = new ContextRegistry().loadThreadLocalAccessors();
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
        ContextSnapshotFactory snapshots =
                ContextSnapshotFactory.builder().contextRegistry(registry).build();
        return ContextExecutorService.wrap(delegate, snapshots);
    }

    public RunExecutor(GoalService goalService, AgentRunner runner, Tasks tasks, Goals goals, Runs runs) {
        this.goalService = goalService;
        this.runner = runner;
        this.tasks = tasks;
        this.goals = goals;
        this.runs = runs;
    }

    // @find: dispatch next tasks for workspace
    /**
     * Starts as many of this workspace's ready tasks as it may, each run on a thread of its own,
     * and returns without waiting for any of them.
     *
     * <p>One pass per workspace at a time on this instance. A call made while a pass is running
     * asks that pass to look again when it ends, rather than starting a second one beside it. A
     * pass stops at {@value #MAX_TASKS_PER_SUBMISSION} tasks, or sooner when the workspace reaches
     * its cap of running tasks; the next task to settle dispatches again.
     */
    public void submitNextTasks(UUID orgId) {
        dispatchAgain.add(orgId);
        if (!dispatching.add(orgId)) {
            return;
        }
        try {
            executor.submit(() -> RequestContext.as(Actor.SYSTEM, () -> {
                dispatch(orgId);
                return null;
            }));
        } catch (RuntimeException e) {
            dispatching.remove(orgId);
            log.error("Could not submit the next tasks for workspace {}", orgId, e);
        }
    }

    // @find: on task settled, dispatch next ready tasks
    /** A task settled, so another may be ready, or may now fit under the workspace's cap. */
    @EventListener
    public void onTaskSettled(TaskSettledEvent event) {
        submitNextTasks(event.orgId());
    }

    private void dispatch(UUID orgId) {
        // The workspace is in the logging context for the failure lines below as well, so the scope
        // encloses the handlers rather than being a resource of the same try.
        try (RunLogContext ignored = RunLogContext.workspace(orgId)) {
            try {
                while (dispatchAgain.remove(orgId)) {
                    for (int i = 0; i < MAX_TASKS_PER_SUBMISSION; i++) {
                        Optional<GoalService.TaskStart> claimed = goalService.claimNextTask(orgId);
                        if (claimed.isEmpty()) {
                            break;
                        }
                        startOnItsOwnThread(orgId, claimed.get());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Submitting the next tasks for workspace {} failed", orgId, e);
            } catch (Error e) {
                // A Future swallows anything thrown into it, so an Error here would otherwise end the
                // thread with no trace. Logged, then rethrown so the JVM still sees it.
                log.error("Submitting the next tasks for workspace {} failed with an error", orgId, e);
                throw e;
            } finally {
                // However the pass ended, so the workspace is never left looking busy on this instance.
                dispatching.remove(orgId);
            }
        }
        // A call that arrived after the last look, but before the guard was released, found the
        // guard held and left its request for this pass; it gets a pass of its own instead.
        if (dispatchAgain.contains(orgId)) {
            submitNextTasks(orgId);
        }
    }

    private void startOnItsOwnThread(UUID orgId, GoalService.TaskStart claimed) {
        Runnable start = () -> {
            try {
                goalService.startClaimed(orgId, claimed);
            } catch (RuntimeException e) {
                log.error("Starting task {} failed", claimed.task().getId(), e);
            } catch (Error e) {
                log.error("Starting task {} failed with an error", claimed.task().getId(), e);
                throw e;
            }
        };
        try {
            executor.submit(start);
        } catch (RuntimeException rejected) {
            // The task is already claimed as running; starting it here is slower but never leaves
            // it claimed with nothing driving it.
            start.run();
        }
    }

    // @find: start run in background, drive run
    /**
     * Drives a run that was just prepared, as the person who started it, without making their
     * request wait for it.
     *
     * <p>A submission that cannot be made leaves the run as prepared; its lease lapses and the
     * reaper marks it abandoned with a reason, rather than it showing "running" for ever.
     */
    public void submitDrive(UUID orgId, UUID runId, Actor actor) {
        try {
            executor.submit(() -> RequestContext.as(actor, () -> {
                try (RunLogContext ignored = logContextOf(orgId, runId)) {
                    try {
                        runner.drive(orgId, runId);
                    } catch (RuntimeException e) {
                        log.error("Driving run {} failed", runId, e);
                    } catch (Error e) {
                        log.error("Driving run {} failed with an error", runId, e);
                        throw e;
                    }
                }
                return null;
            }));
        } catch (RuntimeException e) {
            log.error("Could not submit run {}; it will be marked abandoned when its lease runs out", runId, e);
        }
    }

    // @find: resume run after approval or answer
    /**
     * Resumes a run that was just approved or answered, without making that person's request wait
     * for it.
     *
     * <p>A second submission for a run already being resumed here is ignored. The id is released
     * however the submission ends - the resume finishing or failing, or the submission itself
     * failing before it started - so a run is never left unresumable on this instance.
     */
    public void submitResume(UUID orgId, UUID runId) {
        submitResume(orgId, runId, Actor.SYSTEM);
    }

    // @find: resume run without goal as actor
    /**
     * As {@link #submitResume(UUID, UUID)}, with whom a run that belongs to no goal resumes as -
     * the approver who let it continue, say. A goal's run always resumes as the goal's requester.
     */
    public void submitResume(UUID orgId, UUID runId, Actor withoutGoal) {
        if (!resuming.add(runId)) {
            return;
        }
        try {
            Actor actor = actorForRun(orgId, runId, withoutGoal == null ? Actor.SYSTEM : withoutGoal);
            executor.submit(() -> RequestContext.as(actor, () -> {
                try (RunLogContext ignored = logContextOf(orgId, runId)) {
                    try {
                        runner.resume(orgId, runId);
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.CONFLICT) {
                            // Ordinary with more than one instance or a sweep: the run was already
                            // resumed, or is no longer waiting.
                            log.info("Run {} was not resumed: {}", runId, e.getMessage());
                        } else {
                            log.error("Resuming run {} failed", runId, e);
                        }
                    } catch (RuntimeException e) {
                        log.error("Resuming run {} failed", runId, e);
                    } catch (Error e) {
                        log.error("Resuming run {} failed with an error", runId, e);
                        throw e;
                    } finally {
                        resuming.remove(runId);
                    }
                }
                return null;
            }));
        } catch (RuntimeException e) {
            resuming.remove(runId);
            log.error("Could not submit the resume of run {}", runId, e);
        }
    }

    /**
     * The run, its goal, its agent and its workspace, for the logging context. Read on the thread
     * that is about to run it, and never a reason for the run not to start: a lookup that fails
     * leaves the identifiers it could not find out of the log lines, nothing more.
     */
    private RunLogContext logContextOf(UUID orgId, UUID runId) {
        UUID goalId = null;
        UUID agentId = null;
        try {
            Optional<Run> run = runs.findByIdAndOrgId(runId, orgId);
            if (run != null && run.isPresent()) {
                agentId = run.get().getAgentId();
                UUID taskId = run.get().getTaskId();
                if (taskId != null) {
                    goalId = tasks.findById(taskId).map(Task::getGoalId).orElse(null);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not look up run {} for its log context: {}", runId, e.getMessage());
        }
        return RunLogContext.run(orgId, goalId, runId, agentId);
    }

    private Actor actorForRun(UUID orgId, UUID runId, Actor withoutGoal) {
        return runs.findByIdAndOrgId(runId, orgId)
                .map(Run::getTaskId)
                .flatMap(taskId -> taskId == null ? Optional.<Task>empty() : tasks.findById(taskId))
                .flatMap(task -> goals.findById(task.getGoalId()))
                .map(GoalService::requesterActor)
                .orElse(withoutGoal);
    }
}
