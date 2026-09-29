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

/**
 * Turns a goal and its tasks into the board's read-only views.
 *
 * <p>Pulled out of {@link BoardService} so the same construction - a goal's tasks in order, each
 * carrying its latest run - is shared by the live board and the "failed today" list, which reads
 * goals the window may not otherwise include.
 */
public final class GoalViews {

    private GoalViews() {}

    /** A goal as the board shows it, its tasks kept in position order. */
    public static GoalView goalView(Goal goal, List<Task> tasks, Map<UUID, Run> latestRunByTask) {
        List<TaskView> taskViews =
                tasks.stream().map(task -> taskView(task, latestRunByTask)).toList();
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

    /** One task, carrying its latest run's status, step count and cost when it has run at all. */
    public static TaskView taskView(Task task, Map<UUID, Run> latestRunByTask) {
        Run run = latestRunByTask.get(task.getId());
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
                run == null ? null : run.getId(),
                run == null ? null : run.getStatus(),
                task.getStartedAt(),
                task.getCompletedAt(),
                run == null ? 0 : run.getStepCount(),
                run == null ? BigDecimal.ZERO : run.getTotalCost());
    }

    /**
     * Each task's most recent run, from one query over every id at once rather than one lookup per
     * task - the same result {@code findFirstByTaskIdOrderByStartedAtDesc} would give one task at a
     * time.
     */
    public static Map<UUID, Run> latestRunByTask(Runs runs, Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Run> latest = new LinkedHashMap<>();
        for (Run run : runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(taskIds)) {
            // Ordered by task then most-recent-first, so the first run seen for a task is its
            // latest one.
            latest.putIfAbsent(run.getTaskId(), run);
        }
        return latest;
    }
}
