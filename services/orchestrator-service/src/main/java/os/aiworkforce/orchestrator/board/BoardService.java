package os.aiworkforce.orchestrator.board;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.GoalService;

/**
 * Builds the orchestrator board: everything running or queued, who it is for, and what it has
 * cost - and stops everything, when a person asks for that.
 *
 * <p>Nothing here writes new state except {@link #stopAll}. The board reads recent goals (active,
 * or finished within the last two hours) and derives every other figure - the per-agent counts,
 * the queue and its reasons, the timeline - from the tasks and runs already under them, so a
 * refresh costs a small, bounded number of queries rather than one per row shown.
 */
@Service
public class BoardService {

    /** How far back a finished goal, or a run, still shows on the board. */
    private static final Duration RECENT_WINDOW = Duration.ofHours(2);

    /** Bounds on the "recent" fetches below - generous for a single workspace's live activity. */
    private static final int GOAL_FETCH_LIMIT = 200;
    private static final int RUN_FETCH_LIMIT = 300;
    private static final int QUEUE_FETCH_LIMIT = 100;

    static final String STOPPED_REASON = "A person stopped every run in this workspace.";

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final Agents agents;
    private final Usage usage;
    private final ApprovalService approvals;
    private final GoalService goalService;
    private final WorkspaceZoneLookup zones;

    public BoardService(
            Goals goals, Tasks tasks, Runs runs, Agents agents, Usage usage, ApprovalService approvals,
            GoalService goalService, WorkspaceZoneLookup zones) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.agents = agents;
        this.usage = usage;
        this.approvals = approvals;
        this.goalService = goalService;
        this.zones = zones;
    }

    // ---- Views -----------------------------------------------------------------------------

    public record TaskView(
            UUID id, UUID agentId, String title, String status, int position, List<UUID> dependsOn,
            int attempt, int maxAttempts, String result, String failureReason,
            UUID runId, String runStatus, Instant startedAt, Instant completedAt, int stepCount, BigDecimal cost) {}

    public record GoalView(
            UUID id, String title, String description, String status, UUID requestedBy, String source,
            UUID conversationId, UUID scheduleId, Instant createdAt, Instant completedAt, List<TaskView> tasks) {}

    public record AgentSummary(
            UUID id, String name, String category, String status,
            List<UUID> runningRunIds, List<UUID> waitingRunIds, int queued) {}

    public record QueueEntry(
            UUID goalId, String goalTitle, UUID taskId, UUID agentId, int position, String reason,
            UUID requestedBy, String source, Instant createdAt) {}

    public record TimelineEntry(
            UUID runId, UUID agentId, UUID goalId, String status, Instant startedAt, Instant completedAt) {}

    public record Stats(
            int running, int waitingApproval, int queued, int held,
            int completedToday, int failedToday, BigDecimal spendToday) {}

    public record Board(
            Instant generatedAt, String timezone, Stats stats, List<AgentSummary> agents,
            List<GoalView> goals, List<QueueEntry> queue, List<TimelineEntry> timeline) {}

    public record StopAllResult(int runsCancelled, int tasksCancelled, int approvalsWithdrawn) {}

    // ---- Reading the board -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Board board(UUID orgId) {
        ZoneId zone = zones.zoneFor(orgId);
        Instant now = Instant.now();
        Instant recentCutoff = now.minus(RECENT_WINDOW);
        Instant midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();

        List<Goal> recentGoals = goals.findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, GOAL_FETCH_LIMIT)).getContent();
        Map<UUID, Goal> goalById = new LinkedHashMap<>();
        for (Goal goal : recentGoals) {
            goalById.put(goal.getId(), goal);
        }

        // One query for every goal's tasks, and one for every task's latest run, instead of a
        // pair of queries per goal: a board with 200 recent goals and their tasks would otherwise
        // cost hundreds of round trips on an endpoint a client polls every few seconds.
        List<Task> allTasks = tasks.findByGoalIdInOrderByPositionAsc(goalById.keySet());
        Map<UUID, List<Task>> tasksByGoal = allTasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(Task::getGoalId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<UUID, UUID> goalIdByTaskId = new LinkedHashMap<>();
        for (Task task : allTasks) {
            goalIdByTaskId.put(task.getId(), task.getGoalId());
        }

        Map<UUID, Run> latestRunByTask = new LinkedHashMap<>();
        List<UUID> taskIds = allTasks.stream().map(Task::getId).toList();
        if (!taskIds.isEmpty()) {
            for (Run run : runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(taskIds)) {
                // Ordered by task then most-recent-first, so the first run seen for a task is its
                // latest one - the same one the single-task lookup would have returned.
                latestRunByTask.putIfAbsent(run.getTaskId(), run);
            }
        }

        Map<UUID, Agent> agentById = new LinkedHashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            agentById.put(agent.getId(), agent);
        }

        List<Goal> displayGoals = recentGoals.stream()
                .filter(goal -> !goal.isFinished()
                        || (goal.getCompletedAt() != null && !goal.getCompletedAt().isBefore(recentCutoff)))
                .sorted(Comparator.comparing(Goal::getCreatedAt).reversed())
                .toList();
        List<GoalView> goalViews = displayGoals.stream()
                .map(goal -> toGoalView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), latestRunByTask))
                .toList();

        Stats stats = computeStats(orgId, tasksByGoal, agentById, midnight);
        List<AgentSummary> agentSummaries = computeAgentSummaries(agentById.values(), tasksByGoal, latestRunByTask);
        List<QueueEntry> queue = computeQueue(orgId, goalById, tasksByGoal, agentById);
        List<TimelineEntry> timeline = computeTimeline(orgId, recentCutoff, goalIdByTaskId);

        return new Board(now, zone.getId(), stats, agentSummaries, goalViews, queue, timeline);
    }

    private GoalView toGoalView(Goal goal, List<Task> taskList, Map<UUID, Run> latestRunByTask) {
        List<TaskView> taskViews = taskList.stream().map(task -> toTaskView(task, latestRunByTask)).toList();
        return new GoalView(
                goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(), goal.getRequestedBy(),
                goal.getSource(), goal.getConversationId(), goal.getScheduleId(), goal.getCreatedAt(),
                goal.getCompletedAt(), taskViews);
    }

    private TaskView toTaskView(Task task, Map<UUID, Run> latestRunByTask) {
        Run run = latestRunByTask.get(task.getId());
        return new TaskView(
                task.getId(), task.getAgentId(), task.getTitle(), task.getStatus(), task.getPosition(),
                task.getDependsOn(), task.getAttempt(), task.getMaxAttempts(), task.getResult(), task.getFailureReason(),
                run == null ? null : run.getId(), run == null ? null : run.getStatus(),
                task.getStartedAt(), task.getCompletedAt(), run == null ? 0 : run.getStepCount(),
                run == null ? BigDecimal.ZERO : run.getTotalCost());
    }

    private Stats computeStats(
            UUID orgId, Map<UUID, List<Task>> tasksByGoal, Map<UUID, Agent> agentById, Instant midnight) {
        int running = 0;
        int waitingApproval = 0;
        int queued = 0;
        int held = 0;
        int completedToday = 0;
        int failedToday = 0;
        for (Map.Entry<UUID, List<Task>> entry : tasksByGoal.entrySet()) {
            List<Task> siblings = entry.getValue();
            for (Task task : siblings) {
                switch (task.getStatus()) {
                    case "running" -> running++;
                    case "waiting_approval" -> waitingApproval++;
                    case "pending", "ready" -> {
                        queued++;
                        if ("agent_paused".equals(reasonFor(task, siblings, agentById))) {
                            held++;
                        }
                    }
                    case "completed" -> {
                        if (task.getCompletedAt() != null && !task.getCompletedAt().isBefore(midnight)) {
                            completedToday++;
                        }
                    }
                    case "failed" -> {
                        if (task.getCompletedAt() != null && !task.getCompletedAt().isBefore(midnight)) {
                            failedToday++;
                        }
                    }
                    default -> { /* skipped and cancelled tasks are not counted here */ }
                }
            }
        }
        BigDecimal spendToday = usage.spendSince(orgId, midnight);
        return new Stats(running, waitingApproval, queued, held, completedToday, failedToday, spendToday);
    }

    private List<AgentSummary> computeAgentSummaries(
            java.util.Collection<Agent> allAgents, Map<UUID, List<Task>> tasksByGoal, Map<UUID, Run> latestRunByTask) {
        Map<UUID, List<UUID>> runningByAgent = new LinkedHashMap<>();
        Map<UUID, List<UUID>> waitingByAgent = new LinkedHashMap<>();
        Map<UUID, Integer> queuedByAgent = new LinkedHashMap<>();
        for (List<Task> taskList : tasksByGoal.values()) {
            for (Task task : taskList) {
                UUID agentId = task.getAgentId();
                if (agentId == null) {
                    continue;
                }
                Run run = latestRunByTask.get(task.getId());
                switch (task.getStatus()) {
                    case "running" -> {
                        if (run != null) {
                            runningByAgent.computeIfAbsent(agentId, key -> new ArrayList<>()).add(run.getId());
                        }
                    }
                    case "waiting_approval" -> {
                        if (run != null) {
                            waitingByAgent.computeIfAbsent(agentId, key -> new ArrayList<>()).add(run.getId());
                        }
                    }
                    case "pending", "ready" -> queuedByAgent.merge(agentId, 1, Integer::sum);
                    default -> { /* nothing to add for a finished task */ }
                }
            }
        }
        return allAgents.stream()
                .map(agent -> new AgentSummary(
                        agent.getId(), agent.getName(), agent.getCategory(), agent.getStatus(),
                        runningByAgent.getOrDefault(agent.getId(), List.of()),
                        waitingByAgent.getOrDefault(agent.getId(), List.of()),
                        queuedByAgent.getOrDefault(agent.getId(), 0)))
                .toList();
    }

    private List<QueueEntry> computeQueue(
            UUID orgId, Map<UUID, Goal> goalById, Map<UUID, List<Task>> tasksByGoal, Map<UUID, Agent> agentById) {
        List<QueueEntry> entries = new ArrayList<>();
        for (Task task : tasks.findClaimable(orgId, PageRequest.of(0, QUEUE_FETCH_LIMIT))) {
            Goal goal = goalById.get(task.getGoalId());
            if (goal == null) {
                goal = goals.findById(task.getGoalId()).filter(candidate -> orgId.equals(candidate.getOrgId())).orElse(null);
            }
            if (goal == null) {
                continue;
            }
            List<Task> siblings = tasksByGoal.getOrDefault(goal.getId(), tasks.findByGoalIdOrderByPosition(goal.getId()));
            String reason = reasonFor(task, siblings, agentById);
            entries.add(new QueueEntry(
                    goal.getId(), goal.getTitle(), task.getId(), task.getAgentId(), task.getPosition(), reason,
                    goal.getRequestedBy(), goal.getSource(), task.getCreatedAt()));
        }
        return entries;
    }

    private List<TimelineEntry> computeTimeline(UUID orgId, Instant recentCutoff, Map<UUID, UUID> goalIdByTaskId) {
        return runs.findByOrgIdOrderByStartedAtDesc(orgId, PageRequest.of(0, RUN_FETCH_LIMIT)).getContent().stream()
                .filter(run -> run.getStartedAt() != null && !run.getStartedAt().isBefore(recentCutoff))
                .map(run -> new TimelineEntry(
                        run.getId(), run.getAgentId(),
                        run.getTaskId() == null ? null : goalIdByTaskId.get(run.getTaskId()),
                        run.getStatus(), run.getStartedAt(), run.getCompletedAt()))
                .toList();
    }

    /**
     * Why a pending or ready task has not started - the same rule the engine's own claim uses: an
     * explicit {@code dependsOn} waits for exactly those tasks, one without it waits for every
     * earlier position, and a task whose agent is paused or retired is held regardless of whether
     * its dependencies are otherwise met.
     */
    private static String reasonFor(Task task, List<Task> siblings, Map<UUID, Agent> agentById) {
        if (task.getAgentId() != null) {
            Agent agent = agentById.get(task.getAgentId());
            if (agent != null && !"active".equals(agent.getStatus())) {
                return "agent_paused";
            }
        }
        List<Task> predecessors = predecessorsOf(task, siblings);
        boolean everyPredecessorDone = predecessors.stream().allMatch(Task::isTerminal);
        return everyPredecessorDone ? "ready" : "waiting_on_earlier_task";
    }

    private static List<Task> predecessorsOf(Task task, List<Task> siblings) {
        List<UUID> dependsOn = task.getDependsOn();
        if (dependsOn != null && !dependsOn.isEmpty()) {
            return siblings.stream().filter(sibling -> dependsOn.contains(sibling.getId())).toList();
        }
        return siblings.stream().filter(sibling -> sibling.getPosition() < task.getPosition()).toList();
    }

    // ---- Stopping everything -------------------------------------------------------------------

    /**
     * Cancels every active goal (which stops its open tasks, their active runs, and withdraws any
     * approval they were waiting on), then separately stops any active run that belongs to no goal
     * at all - a run started directly on an agent rather than through a goal.
     */
    @Transactional
    public StopAllResult stopAll(UUID orgId) {
        int approvalsWithdrawn = approvals.pending(orgId).size();

        List<Run> activeRuns = new ArrayList<>();
        activeRuns.addAll(runs.findByOrgIdAndStatusOrderByStartedAtDesc(orgId, "running", PageRequest.of(0, RUN_FETCH_LIMIT)).getContent());
        activeRuns.addAll(runs.findByOrgIdAndStatusOrderByStartedAtDesc(orgId, "waiting_approval", PageRequest.of(0, RUN_FETCH_LIMIT)).getContent());
        int runsCancelled = activeRuns.size();

        List<Goal> activeGoals = goals.findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, GOAL_FETCH_LIMIT)).getContent().stream()
                .filter(goal -> !goal.isFinished())
                .toList();
        int tasksCancelled = 0;
        for (Goal goal : activeGoals) {
            tasksCancelled += (int) tasks.findByGoalIdOrderByPosition(goal.getId()).stream()
                    .filter(task -> !task.isTerminal())
                    .count();
            goalService.cancel(orgId, goal.getId());
        }

        // A run with no task belongs to no goal, so the cancellations above never reach it.
        for (Run run : activeRuns) {
            if (run.getTaskId() == null && run.isActive()) {
                run.finish("cancelled", STOPPED_REASON);
                runs.save(run);
                approvals.cancelForRun(run.getId());
            }
        }

        return new StopAllResult(runsCancelled, tasksCancelled, approvalsWithdrawn);
    }
}
