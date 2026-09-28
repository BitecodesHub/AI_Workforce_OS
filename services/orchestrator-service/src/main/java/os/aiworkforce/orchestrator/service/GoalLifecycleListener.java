package os.aiworkforce.orchestrator.service;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;

/**
 * Notified as {@link TaskProgress} settles a task's or a goal's fate.
 *
 * <p>Every Spring bean implementing this is called, in no particular order, from inside the same
 * write that just changed the task or the goal. This is how the chat coordinator appends an
 * {@code answer} message when a task it started finishes, and how a schedule records its last run
 * outcome, without either package reaching into the other's tables or {@code TaskProgress} needing
 * to know they exist.
 *
 * <p>A listener that throws is caught and logged by {@link TaskProgress}: one package's failure to
 * react must never stop the task or the goal itself from being recorded as finished.
 */
public interface GoalLifecycleListener {

    /** A task under {@code goal} reached a terminal state, or was put back to wait for a retry. */
    default void onTaskFinished(Goal goal, Task task, String status) {}

    /** {@code goal} itself has just closed, as completed, failed or cancelled. */
    default void onGoalFinished(Goal goal) {}
}
