package os.aiworkforce.orchestrator.service;

import java.util.UUID;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;

/**
 * Notified as {@link TaskProgress} settles a task's or a goal's fate, as a goal is stopped,
 * retried or asks its requester something, as an approval is raised or expires, and as a schedule
 * pauses itself.
 *
 * <p>Every Spring bean implementing this is called, in no particular order, after the write that
 * changed the task or the goal commits, each listener in its own transaction. This is how the
 * chat coordinator appends an {@code answer} message when a task it started finishes, and how a
 * schedule records its last run outcome, without either package reaching into the other's tables
 * or {@code TaskProgress} needing to know they exist. Running after commit means a listener that
 * fails, or waits on a lock, can never roll back the run's own finish.
 *
 * <p>The goal and task a listener receives were read by the committed write and are detached by
 * the time it runs, so a listener reads their fields and never saves them.
 *
 * <p>A listener that throws is caught and logged by {@link TaskProgress} and {@link
 * LifecycleAnnouncer}: one package's failure to react must never stop the task or the goal itself
 * from being recorded, or another listener from hearing about it.
 */
public interface GoalLifecycleListener {

    /** A task under {@code goal} reached a terminal state, or was put back to wait for a retry. */
    default void onTaskFinished(Goal goal, Task task, String status) {}

    /** {@code goal} itself has just closed, as completed, failed or cancelled. */
    default void onGoalFinished(Goal goal) {}

    /** A person or a path acting for one stopped {@code goal}; {@code reason} is shown as written. */
    default void onGoalCancelled(Goal goal, String reason) {}

    /** {@code goal} was tried again from {@code fromTask}, the first step that had not finished. */
    default void onGoalRetried(Goal goal, Task fromTask) {}

    /**
     * A run stopped to ask its requester {@code question}. {@code goal} and {@code task} are null
     * for a run started directly on an agent rather than for a task.
     */
    default void onQuestionAsked(Goal goal, Task task, RunQuestion question) {}

    /**
     * A run parked on {@code approval}, waiting for a person to decide it. {@code goal} and {@code
     * task} are null for a run started directly on an agent.
     */
    default void onApprovalRaised(Goal goal, Task task, Approval approval) {}

    /**
     * Nobody decided {@code approval} before its deadline: it closed as expired, and its run was
     * stopped. {@code goal} and {@code task} are null for a run started directly on an agent.
     */
    default void onApprovalExpired(Goal goal, Task task, Approval approval) {}

    /** A schedule paused itself after too many failed runs in a row. */
    default void onSchedulePaused(SchedulePause pause) {}

    /**
     * A schedule that paused itself, as the schedule package reports it. Plain values rather than
     * the schedule itself, so this package never depends on that one.
     *
     * @param ownerId the person the schedule works for, or null for one nobody owns
     * @param failures how many runs in a row failed
     */
    record SchedulePause(UUID orgId, UUID scheduleId, UUID ownerId, String reason, int failures) {}
}
