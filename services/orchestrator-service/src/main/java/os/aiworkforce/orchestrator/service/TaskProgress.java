package os.aiworkforce.orchestrator.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Tasks;

/**
 * Keeps a task, and the goal above it, in step with the run doing the work.
 *
 * <p>A run changes state in many places: the loop finishing, a run parking for an approval and
 * resuming after it, an approver rejecting, an approval expiring, a person cancelling, the reaper
 * giving up on a dead worker. Before this class existed only the first of those touched the task,
 * so a task that paused for an approval showed "waiting" forever, its goal showed "running"
 * forever, and its duration kept counting. Every one of those places now reports here, and the
 * rules for what a run's outcome means for its task live in one spot.
 *
 * <p>Depends only on the task and goal repositories, so any service can call it without a cycle.
 * Every method is safe to call twice: a task that has already moved on is left alone.
 */
@Service
public class TaskProgress {

    private static final Logger log = LoggerFactory.getLogger(TaskProgress.class);

    static final String EARLIER_TASK_UNFINISHED = "An earlier task in this goal did not finish.";
    private static final String DEFAULT_FAILURE = "The agent did not complete this task.";
    private static final String DEFAULT_ABANDONED = "The worker running this task stopped responding.";

    private final Tasks tasks;
    private final Goals goals;

    public TaskProgress(Tasks tasks, Goals goals) {
        this.tasks = tasks;
        this.goals = goals;
    }

    /**
     * Applies a run's terminal outcome to its task.
     *
     * <p>Only a task that is still running or waiting for an approval changes. A task that has
     * finished, or that was already put back to wait for another attempt, belongs to a later
     * decision, and a late report about an earlier run must not undo it.
     *
     * @param run the run that ended; a run started directly rather than for a task is ignored
     * @param status {@code completed}, {@code failed}, {@code abandoned} or {@code cancelled}
     * @param answer the agent's final text, for a completed run
     * @param reason why the run did not complete, shown on the task
     */
    @Transactional
    public void onRunFinished(Run run, String status, String answer, String reason) {
        Task task = activeTaskOf(run);
        if (task == null) {
            return;
        }
        Instant now = Instant.now();
        switch (status) {
            case "completed" -> {
                task.setStatus("completed");
                task.setResult(answer);
                task.setFailureReason(null);
                task.setCompletedAt(now);
            }
            case "failed" -> applyFailure(task, reason == null ? DEFAULT_FAILURE : reason, now);
            case "abandoned" -> {
                // Never retried. The dead worker may have completed a side effect before it
                // stopped, and repeating the task automatically is how one email becomes two.
                task.setStatus("failed");
                task.setFailureReason(reason == null ? DEFAULT_ABANDONED : reason);
                task.setCompletedAt(now);
            }
            case "cancelled" -> {
                task.setStatus("cancelled");
                task.setFailureReason(reason);
                task.setCompletedAt(now);
            }
            default -> {
                log.warn("Run {} reported {}, which is not a finished state; task {} left as it was",
                        run.getId(), status, task.getId());
                return;
            }
        }
        tasks.save(task);
        afterTaskChanged(task);
    }

    /** The run stopped to wait for a person, so its task is waiting too. */
    @Transactional
    public void onRunParked(Run run) {
        Task task = activeTaskOf(run);
        if (task == null || "waiting_approval".equals(task.getStatus())) {
            return;
        }
        task.setStatus("waiting_approval");
        tasks.save(task);
    }

    /** The approval was granted and the run picked up again. */
    @Transactional
    public void onRunResumed(Run run) {
        Task task = activeTaskOf(run);
        if (task == null || "running".equals(task.getStatus())) {
            return;
        }
        task.setStatus("running");
        tasks.save(task);
    }

    /**
     * A task whose run could not even begin: the agent is paused, has been removed, or has no
     * configuration. The attempt has already been counted, so the retry rule still bounds it.
     */
    @Transactional
    public void onStartFailed(Task task, String reason) {
        applyFailure(task, reason == null ? DEFAULT_FAILURE : reason, Instant.now());
        tasks.save(task);
        afterTaskChanged(task);
    }

    /**
     * Closes a goal once every task under it has finished.
     *
     * <p>Failed outranks cancelled, which outranks completed: a goal with one failed task did not
     * achieve what was asked, however the rest went. A goal that has already finished keeps its
     * status, so a cancelled goal is never reopened as failed by a run ending late.
     */
    @Transactional
    public void closeGoalIfFinished(UUID goalId) {
        Goal goal = goals.findById(goalId).orElse(null);
        if (goal == null || goal.isFinished()) {
            return;
        }
        List<Task> all = tasks.findByGoalIdOrderByPosition(goalId);
        if (all.isEmpty() || !all.stream().allMatch(Task::isTerminal)) {
            return;
        }
        boolean anyFailed = all.stream().anyMatch(task -> "failed".equals(task.getStatus()));
        boolean anyCancelled = all.stream().anyMatch(task -> "cancelled".equals(task.getStatus()));
        goal.setStatus(anyFailed ? "failed" : anyCancelled ? "cancelled" : "completed");
        goal.setCompletedAt(Instant.now());
        goals.save(goal);
        log.info("Goal {} finished as {}", goalId, goal.getStatus());
    }

    // ---- Rules ------------------------------------------------------------------------------

    private static void applyFailure(Task task, String reason, Instant now) {
        task.setFailureReason(reason);
        if (task.canRetry()) {
            // Back to waiting so the goal sweep picks it up again, with the attempt already
            // counted so it cannot loop forever.
            task.setStatus("pending");
        } else {
            task.setStatus("failed");
            task.setCompletedAt(now);
        }
    }

    private void afterTaskChanged(Task task) {
        if ("failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())) {
            skipLaterTasks(task);
        }
        closeGoalIfFinished(task.getGoalId());
    }

    /**
     * Tasks run in order, so a task after one that failed or was cancelled can never start.
     * Leaving it "waiting to start" would keep the goal open with nothing left to happen.
     */
    private void skipLaterTasks(Task ended) {
        Instant now = Instant.now();
        for (Task later : tasks.findByGoalIdOrderByPosition(ended.getGoalId())) {
            boolean waiting = "pending".equals(later.getStatus()) || "ready".equals(later.getStatus());
            if (later.getPosition() > ended.getPosition() && waiting) {
                later.setStatus("skipped");
                later.setFailureReason(EARLIER_TASK_UNFINISHED);
                later.setCompletedAt(now);
                tasks.save(later);
            }
        }
    }

    /** The run's task, when it has one that is still in progress. */
    private Task activeTaskOf(Run run) {
        if (run == null || run.getTaskId() == null) {
            return null;
        }
        return tasks.findById(run.getTaskId())
                .filter(task -> "running".equals(task.getStatus()) || "waiting_approval".equals(task.getStatus()))
                .orElse(null);
    }
}
