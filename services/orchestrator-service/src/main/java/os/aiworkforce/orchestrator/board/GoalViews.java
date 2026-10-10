// @find: goal views, goal and task view, task runs, cost per task, attempts, latest run, GoalViews, goal list view, failed today list
// @what: Turns goals and their tasks into read-only views, batching task and run lookups.
// @flow: Called by BoardService, the goals list and conversations.
package os.aiworkforce.orchestrator.board;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import os.aiworkforce.orchestrator.board.BoardService.GoalView;
import os.aiworkforce.orchestrator.board.BoardService.TaskView;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.web.GoalController;

/**
 * Turns a goal and its tasks into read-only views.
 *
 * <p>Pulled out of {@link BoardService} so the same construction - a goal's tasks in order, each
 * carrying what its runs add up to - is shared by the live board, the "failed today" list (which
 * reads goals the window may not otherwise include), a conversation's goals and the goals list.
 * Every caller batches: tasks for all its goals in one query, and every task's runs in one more.
 */
public final class GoalViews {

    private GoalViews() {}

    /**
     * What one task's runs add up to: its latest run, what every attempt cost, and how many runs
     * there were. A retried task is run again, so the latest run alone would drop the spend of
     * every earlier attempt.
     */
    public record TaskRuns(Run latest, BigDecimal cost, int attempts) {}

    /** A goal as the board shows it, its tasks kept in position order. */
    // @find: build goal view with tasks
    public static GoalView goalView(Goal goal, List<Task> tasks, Map<UUID, TaskRuns> runsByTask) {
        List<TaskView> taskViews =
                tasks.stream().map(task -> taskView(task, runsByTask)).toList();
        return new GoalView(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getStatus(),
                goal.getRequestedBy(),
                goal.getSource(),
                goal.getConversationId(),
                goal.getScheduleId(),
                goal.getCreatedAt(),
                goal.getCompletedAt(),
                taskViews);
    }

    /**
     * One task, carrying what its runs add up to when it has run at all.
     *
     * <p>The cost is the sum over every run of the task, and {@code attempts} is the number of
     * runs. The run id, run status and step count are the latest attempt's, so the step count on a
     * card describes the attempt a person is looking at, not the retries before it. {@code
     * attempts} is counted from the runs rather than read from {@code task.getAttempt()}, which a
     * retry sets back to zero.
     */
    // @find: build task view
    public static TaskView taskView(Task task, Map<UUID, TaskRuns> runsByTask) {
        TaskRuns runs = runsByTask.get(task.getId());
        Run latest = runs == null ? null : runs.latest();
        return new TaskView(
                task.getId(),
                task.getAgentId(),
                task.getTitle(),
                task.getStatus(),
                task.getPosition(),
                task.getDependsOn(),
                task.getAttempt(),
                task.getMaxAttempts(),
                task.getResult(),
                task.getFailureReason(),
                latest == null ? null : latest.getId(),
                latest == null ? null : latest.getStatus(),
                task.getStartedAt(),
                task.getCompletedAt(),
                latest == null ? 0 : latest.getStepCount(),
                runs == null ? BigDecimal.ZERO : runs.cost(),
                runs == null ? 0 : runs.attempts());
    }

    /**
     * A goal as the goals list returns it: the same tasks, each with its latest run's id, built
     * from the same batched maps as the board so a page of goals costs a fixed number of queries.
     */
    // @find: build plain goal view
    public static GoalController.GoalView plainView(Goal goal, List<Task> tasks, Map<UUID, TaskRuns> runsByTask) {
        List<GoalController.TaskView> taskViews = tasks.stream()
                .map(task -> {
                    TaskRuns runs = runsByTask.get(task.getId());
                    return new GoalController.TaskView(
                            task.getId(),
                            task.getAgentId(),
                            task.getTitle(),
                            task.getStatus(),
                            task.getPosition(),
                            task.getDependsOn(),
                            task.getAttempt(),
                            task.getMaxAttempts(),
                            task.getResult(),
                            task.getFailureReason(),
                            task.getStartedAt(),
                            task.getCompletedAt(),
                            runs == null ? null : runs.latest().getId());
                })
                .toList();
        return new GoalController.GoalView(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getStatus(),
                goal.getRequestedBy(),
                goal.getSource(),
                goal.getConversationId(),
                goal.getScheduleId(),
                goal.getCreatedAt(),
                goal.getCompletedAt(),
                taskViews);
    }

    /**
     * Every task's runs added up, from one query over every id at once rather than one lookup per
     * task. The query returns each task's runs most recent first, so the first run seen for a task
     * is its latest one - the same run {@code findFirstByTaskIdOrderByStartedAtDesc} gives one
     * task at a time.
     */
    // @find: runs grouped by task, cost and attempts
    public static Map<UUID, TaskRuns> runsByTask(Runs runs, Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Run> latest = new LinkedHashMap<>();
        Map<UUID, BigDecimal> cost = new LinkedHashMap<>();
        Map<UUID, Integer> attempts = new LinkedHashMap<>();
        for (Run run : runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(taskIds)) {
            // Ordered by task then most-recent-first, so the first run seen for a task is its
            // latest one.
            latest.putIfAbsent(run.getTaskId(), run);
            BigDecimal runCost = run.getTotalCost() == null ? BigDecimal.ZERO : run.getTotalCost();
            cost.merge(run.getTaskId(), runCost, BigDecimal::add);
            attempts.merge(run.getTaskId(), 1, Integer::sum);
        }
        Map<UUID, TaskRuns> byTask = new LinkedHashMap<>();
        latest.forEach((taskId, run) -> byTask.put(taskId, new TaskRuns(run, cost.get(taskId), attempts.get(taskId))));
        return byTask;
    }

    /** Each task's latest run, from {@link #runsByTask}, for callers that need nothing else. */
    // @find: latest run per task
    public static Map<UUID, Run> latestRuns(Map<UUID, TaskRuns> runsByTask) {
        Map<UUID, Run> latest = new LinkedHashMap<>();
        runsByTask.forEach((taskId, runs) -> latest.put(taskId, runs.latest()));
        return latest;
    }
}
