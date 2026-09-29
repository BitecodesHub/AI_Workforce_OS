package os.aiworkforce.orchestrator.service;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;

/**
 * Notified as {@link TaskProgress} settles a task's or a goal's fate, and as a goal is stopped,
 * retried or asks its requester something.
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
}
