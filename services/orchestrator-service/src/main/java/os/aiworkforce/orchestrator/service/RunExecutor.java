package os.aiworkforce.orchestrator.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Hands goal and resume work to a virtual thread, so the request that triggered it can answer
 * before the work is done.
 *
 * <p>A chat message and a schedule firing both need to start work without waiting for it: the
 * person is looking at a reply, and the sweep has thirty seconds before its next tick, while an
 * agent run can take minutes. Everything submitted here runs on a thread of its own and this
 * class returns immediately; the run's own progress is then visible the ordinary way, by polling
 * the goal, the task or the run.
 *
 * <p>Each submission runs inside {@link RequestContext#as}, as the goal's own requester when one
 * is on record, so a run this starts is audited against the person who asked for it rather than
 * whichever request happened to trigger this tick. A goal with no requester - a schedule that
 * predates a creator, or a claim that finds nothing left to start - runs as the platform itself.
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** More than this in one submission would hold a virtual thread open for too long a chain. */
    private static final int MAX_TASKS_PER_SUBMISSION = 5;

    private final GoalService goalService;
    private final AgentRunner runner;
    private final Tasks tasks;
    private final Goals goals;
    private final Runs runs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Runs with a resume in flight on this instance, so an answer's own submission and the sweep
     * do not both start one. Only saves work: the claim in the database decides which resume
     * drives a run, on this instance or any other.
     */
    private final Set<UUID> resuming = ConcurrentHashMap.newKeySet();

    public RunExecutor(GoalService goalService, AgentRunner runner, Tasks tasks, Goals goals, Runs runs) {
        this.goalService = goalService;
        this.runner = runner;
        this.tasks = tasks;
        this.goals = goals;
        this.runs = runs;
    }

    /**
     * Starts as many of this workspace's next ready tasks as it can find, up to {@value
     * #MAX_TASKS_PER_SUBMISSION} in this one submission, and returns without waiting for any of
     * them to finish.
     */
    public void submitNextTasks(UUID orgId) {
        Actor actor = actorForNextClaimable(orgId);
        executor.submit(() -> RequestContext.as(actor, () -> {
            try {
                for (int i = 0; i < MAX_TASKS_PER_SUBMISSION; i++) {
                    if (!goalService.runNextTask(orgId)) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.error("Submitting the next tasks for workspace {} failed", orgId, e);
            }
            return null;
        }));
    }

    /**
     * Resumes a run that was just approved or answered, without making that person's request wait
     * for it.
     *
     * <p>A second submission for a run already being resumed here is ignored. The id is released
     * however the submission ends - the resume finishing or failing, or the submission itself
     * failing before it started - so a run is never left unresumable on this instance.
     */
    public void submitResume(UUID orgId, UUID runId) {
        if (!resuming.add(runId)) {
            return;
        }
        try {
            Actor actor = actorForRun(orgId, runId);
            executor.submit(() -> RequestContext.as(actor, () -> {
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
                } finally {
                    resuming.remove(runId);
                }
                return null;
            }));
        } catch (RuntimeException e) {
            resuming.remove(runId);
            log.error("Could not submit the resume of run {}", runId, e);
        }
    }

    /**
     * The requester of whichever goal owns the next claimable task, as a best-effort guess at
     * whose work this submission is about. A submission with nothing left to claim, or a task
     * whose goal was never attributed to anyone, runs as the platform.
     */
    private Actor actorForNextClaimable(UUID orgId) {
        return tasks.findClaimable(orgId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .flatMap(task -> goals.findById(task.getGoalId()))
                .map(goal -> asUserActor(goal, orgId))
                .orElse(Actor.SYSTEM);
    }

    private Actor actorForRun(UUID orgId, UUID runId) {
        return runs.findByIdAndOrgId(runId, orgId)
                .map(Run::getTaskId)
                .flatMap(taskId -> taskId == null
                        ? java.util.Optional.<os.aiworkforce.orchestrator.domain.Task>empty()
                        : tasks.findById(taskId))
                .flatMap(task -> goals.findById(task.getGoalId()))
                .map(goal -> asUserActor(goal, orgId))
                .orElse(Actor.SYSTEM);
    }

    private static Actor asUserActor(Goal goal, UUID orgId) {
        UUID requestedBy = goal.getRequestedBy();
        if (requestedBy == null) {
            return Actor.SYSTEM;
        }
        return Actor.user(requestedBy.toString(), orgId.toString(), null, Set.of(), 0L);
    }
}
