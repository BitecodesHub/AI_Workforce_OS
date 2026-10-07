package os.aiworkforce.orchestrator.board;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.board.GoalViews.TaskRuns;
import os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.schedule.Schedule;
import os.aiworkforce.orchestrator.schedule.ScheduleService;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Builds the orchestrator board: everything running, queued or waiting on a person, who it is
 * for, and what it has cost - and stops everything, when a person asks for that.
 *
 * <p>Nothing here writes new state except {@link #stopAll}. The board reads goals within the
 * requested window, and every active goal besides, however old it is, and derives every other
 * figure - the per-agent counts, the queue and its reasons, the timeline, the questions and
 * approvals a person can act on - from the tasks, runs, questions and approvals already under
 * them, so a refresh costs a small, bounded number of queries rather than one per row shown. The
 * goal and run reads return lists, not pages, so none of them adds a {@code count(*)}.
 */
@Service
public class BoardService {

    /** Goal fetch size for the 1h, 2h and 6h windows. */
    private static final int GOAL_FETCH_LIMIT = 200;
    /** Goal fetch size for the 24h and today windows, which reach much further back. */
    private static final int GOAL_FETCH_LIMIT_WIDE = 500;
    /** Run fetch size for the 1h and 2h windows. */
    private static final int RUN_FETCH_LIMIT = 300;
    /** Run fetch size for the 6h window. */
    private static final int RUN_FETCH_LIMIT_MID = 600;
    /** Run fetch size for the 24h and today windows. */
    private static final int RUN_FETCH_LIMIT_WIDE = 1000;

    private static final int QUEUE_FETCH_LIMIT = 100;
    private static final int FAILED_TODAY_LIMIT = 50;
    private static final int QUESTION_FETCH_LIMIT = 50;

    /** The statuses a run started directly on an agent, rather than for a task, is still active in. */
    private static final List<String> DIRECT_RUN_STATUSES = List.of("running", "waiting_approval", "waiting_input");

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    static final String STOPPED_REASON = "A person stopped every run in this workspace.";

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final Agents agents;
    private final Usage usage;
    private final ApprovalService approvals;
    private final GoalService goalService;
    private final QuestionService questions;
    private final ScheduleService schedules;
    private final WorkspaceZoneLookup zones;

    /** Who may read which conversation; absent only where a test builds this by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.orchestrator.chat.ConversationAccess access;

    public BoardService(
            Goals goals,
            Tasks tasks,
            Runs runs,
            Agents agents,
            Usage usage,
            ApprovalService approvals,
            GoalService goalService,
            QuestionService questions,
            ScheduleService schedules,
            WorkspaceZoneLookup zones) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.agents = agents;
        this.usage = usage;
        this.approvals = approvals;
        this.goalService = goalService;
        this.questions = questions;
        this.schedules = schedules;
        this.zones = zones;
    }

    // ---- Views -----------------------------------------------------------------------------

    /** The board's time range. {@code TODAY} starts at local midnight rather than a fixed span. */
    public enum Window {
        H1("PT1H", 60),
        H2("PT2H", 120),
        H6("PT6H", 360),
        H24("PT24H", 1440),
        TODAY("TODAY", -1);

        private final String code;
        private final int minutes;

        Window(String code, int minutes) {
            this.code = code;
            this.minutes = minutes;
        }

        public String code() {
            return code;
        }

        public int minutes() {
            return minutes;
        }

        public static Window parse(String code) {
            for (Window window : values()) {
                if (window.code.equals(code)) {
                    return window;
                }
            }
            throw ApiException.validation("window", "must be PT1H, PT2H, PT6H, PT24H or TODAY");
        }
    }

    /**
     * @param runId the task's latest run, and {@code runStatus} and {@code stepCount} are that run's
     * @param cost what every run of the task cost, not only the latest
     * @param attempts how many runs the task has had
     */
    public record TaskView(
            UUID id,
            UUID agentId,
            String title,
            String status,
            int position,
            List<UUID> dependsOn,
            int attempt,
            int maxAttempts,
            String result,
            String failureReason,
            UUID runId,
            String runStatus,
            Instant startedAt,
            Instant completedAt,
            int stepCount,
            BigDecimal cost,
            int attempts) {}

    public record GoalView(
            UUID id,
            String title,
            String description,
            String status,
            UUID requestedBy,
            String source,
            UUID conversationId,
            UUID scheduleId,
            Instant createdAt,
            Instant completedAt,
            List<TaskView> tasks) {}

    public record AgentSummary(
            UUID id,
            String name,
            String category,
            String status,
            boolean fallback,
            List<UUID> runningRunIds,
            List<UUID> waitingRunIds,
            List<UUID> askingRunIds,
            int queued) {}

    public record QueueEntry(
            UUID goalId,
            String goalTitle,
            UUID taskId,
            UUID agentId,
            int position,
            String reason,
            UUID requestedBy,
            String source,
            Instant createdAt) {}

    public record TimelineEntry(
            UUID runId, UUID agentId, UUID goalId, String status, Instant startedAt, Instant completedAt) {}

    /**
     * @param requestedBy the goal's requester, or a direct run's starter
     * @param canDecide the caller holds the approval's requiredPermission
     */
    public record ApprovalSummary(
            UUID id,
            UUID runId,
            UUID taskId,
            UUID goalId,
            UUID agentId,
            String tool,
            String actionClass,
            String summary,
            Instant requestedAt,
            Instant expiresAt,
            UUID requestedBy,
            boolean canDecide) {}

    /**
     * The first eight fields keep their v1 meaning (tasks and runs), so existing screens are
     * unchanged. {@code goalsCompletedToday} and {@code goalsFailedToday} count goals finished
     * since local midnight, whatever the window; they are what the "Done today" and "Failed today"
     * tiles show. {@code directRuns} counts active runs with no task.
     */
    public record Stats(
            int running,
            int waitingApproval,
            int waitingInput,
            int queued,
            int held,
            int completedToday,
            int failedToday,
            BigDecimal spendToday,
            int goalsCompletedToday,
            int goalsFailedToday,
            int directRuns) {}

    public record Board(
            Instant generatedAt,
            String timezone,
            String window,
            int windowMinutes,
            Stats stats,
            List<AgentSummary> agents,
            List<GoalView> goals,
            List<QueueEntry> queue,
            List<TimelineEntry> timeline,
            List<QuestionService.QuestionView> questions,
            List<ApprovalSummary> approvals,
            List<GoalView> failedToday) {}

    public record StopAllResult(
            int runsCancelled,
            int tasksCancelled,
            int approvalsWithdrawn,
            int questionsWithdrawn,
            int schedulesPaused,
            int goalsSkipped,
            int runsSkipped) {}

    // ---- Reading the board -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Board board(UUID orgId, Window window, Actor actor) {
        ZoneId zone = zones.zoneFor(orgId);
        Instant now = Instant.now();
        Instant midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();

        Instant since;
        int windowMinutes;
        if (window == Window.TODAY) {
            since = midnight;
            windowMinutes = (int) Math.max(1L, Duration.between(midnight, now).toMinutes());
        } else {
            windowMinutes = window.minutes();
            since = now.minus(Duration.ofMinutes(windowMinutes));
        }

        List<Goal> recentGoals = goals.findRecent(orgId, PageRequest.of(0, goalFetchLimit(window)));
        Map<UUID, Goal> goalById = new LinkedHashMap<>();
        for (Goal goal : recentGoals) {
            goalById.put(goal.getId(), goal);
        }

        // Every active goal joins the fetch, however old it is: a goal still waiting on a person
        // after hundreds of newer ones finished must still be drawn, counted and listed under its
        // agent, or the approvals inbox would show something no card or tile accounts for (D-20).
        for (Goal goal : goals.findAllById(goals.activeIds(orgId))) {
            if (orgId.equals(goal.getOrgId())) {
                goalById.putIfAbsent(goal.getId(), goal);
            }
        }

        // Failed-today goals join the fetch before tasks and runs are read, so a goal that failed
        // today but fell outside the window is still counted and drawn - the tiles must match the
        // cards exactly (D-20) - without a second round trip per goal.
        List<Goal> failedToday =
                goals.findFinishedSince(orgId, "failed", midnight, PageRequest.of(0, FAILED_TODAY_LIMIT));
        for (Goal goal : failedToday) {
            goalById.putIfAbsent(goal.getId(), goal);
        }
        // Work a private conversation started is shown only to the people in that conversation.
        Set<UUID> hiddenTasks = Set.of();
        if (access != null) {
            goalById.keySet().removeAll(access.hiddenGoalIds(orgId, actor));
            hiddenTasks = access.hiddenTaskIds(orgId, actor);
        }
        Set<UUID> goalIdsForTasks = new LinkedHashSet<>(goalById.keySet());

        // One query for every goal's tasks, and one for every task's runs, instead of a pair of
        // queries per goal: a board with hundreds of goals and their tasks would otherwise cost
        // hundreds of round trips on an endpoint a client polls every few seconds.
        List<Task> allTasks = tasks.findByGoalIdInOrderByPositionAsc(goalIdsForTasks);
        Map<UUID, List<Task>> tasksByGoal = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getGoalId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, UUID> goalIdByTaskId = new LinkedHashMap<>();
        for (Task task : allTasks) {
            goalIdByTaskId.put(task.getId(), task.getGoalId());
        }
        List<UUID> taskIds = allTasks.stream().map(Task::getId).toList();
        Map<UUID, TaskRuns> runsByTask = GoalViews.runsByTask(runs, taskIds);
        Map<UUID, Run> latestRunByTask = GoalViews.latestRuns(runsByTask);

        Map<UUID, Agent> agentById = new LinkedHashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            agentById.put(agent.getId(), agent);
        }

        List<Run> directRuns = runs.findByOrgIdAndTaskIdIsNullAndStatusIn(orgId, DIRECT_RUN_STATUSES);
        Map<UUID, Run> directRunById = new LinkedHashMap<>();
        for (Run run : directRuns) {
            directRunById.put(run.getId(), run);
        }

        List<Goal> displayGoals = goalById.values().stream()
                .filter(goal -> !goal.isFinished()
                        || (goal.getCompletedAt() != null
                                && !goal.getCompletedAt().isBefore(since)))
                .sorted(Comparator.comparing(Goal::getCreatedAt).reversed())
                .toList();
        List<GoalView> goalViews = displayGoals.stream()
                .map(goal -> GoalViews.goalView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), runsByTask))
                .toList();
        List<GoalView> failedTodayViews = failedToday.stream()
                .filter(goal -> goalById.containsKey(goal.getId()))
                .map(goal -> GoalViews.goalView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), runsByTask))
                .toList();

        int goalsCompletedToday =
                (int) goals.countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(orgId, "completed", midnight);
        int goalsFailedToday =
                (int) goals.countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(orgId, "failed", midnight);

        Stats stats = computeStats(
                orgId, tasksByGoal, agentById, midnight, directRuns, goalsCompletedToday, goalsFailedToday);
        List<AgentSummary> agentSummaries =
                computeAgentSummaries(agentById.values(), tasksByGoal, latestRunByTask, directRuns);
        List<QueueEntry> queue = computeQueue(orgId, goalById, tasksByGoal, agentById);
        List<TimelineEntry> timeline = computeTimeline(orgId, since, runFetchLimit(window), goalIdByTaskId, hiddenTasks);
        List<QuestionService.QuestionView> questionViews =
                questions.views(questions.pending(orgId, QUESTION_FETCH_LIMIT), actor);
        List<ApprovalSummary> approvalSummaries =
                computeApprovals(orgId, goalIdByTaskId, goalById, directRunById, actor);

        return new Board(
                now,
                zone.getId(),
                window.code(),
                windowMinutes,
                stats,
                agentSummaries,
                goalViews,
                queue,
                timeline,
                questionViews,
                approvalSummaries,
                failedTodayViews);
    }

    private static int goalFetchLimit(Window window) {
        return switch (window) {
            case H24, TODAY -> GOAL_FETCH_LIMIT_WIDE;
            default -> GOAL_FETCH_LIMIT;
        };
    }

    private static int runFetchLimit(Window window) {
        return switch (window) {
            case H1, H2 -> RUN_FETCH_LIMIT;
            case H6 -> RUN_FETCH_LIMIT_MID;
            case H24, TODAY -> RUN_FETCH_LIMIT_WIDE;
        };
    }

    private Stats computeStats(
            UUID orgId,
            Map<UUID, List<Task>> tasksByGoal,
            Map<UUID, Agent> agentById,
            Instant midnight,
            List<Run> directRuns,
            int goalsCompletedToday,
            int goalsFailedToday) {
        int running = 0;
        int waitingApproval = 0;
        int waitingInput = 0;
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
                    case "waiting_input" -> waitingInput++;
                    case "pending", "ready" -> {
                        queued++;
                        if ("agent_paused".equals(reasonFor(task, siblings, agentById))) {
                            held++;
                        }
                    }
                    case "completed" -> {
                        if (task.getCompletedAt() != null
                                && !task.getCompletedAt().isBefore(midnight)) {
                            completedToday++;
                        }
                    }
                    case "failed" -> {
                        if (task.getCompletedAt() != null
                                && !task.getCompletedAt().isBefore(midnight)) {
                            failedToday++;
                        }
                    }
                    default -> {
                        /* skipped and cancelled tasks are not counted here */
                    }
                }
            }
        }
        for (Run run : directRuns) {
            switch (run.getStatus()) {
                case "running" -> running++;
                case "waiting_approval" -> waitingApproval++;
                case "waiting_input" -> waitingInput++;
                default -> {
                    /* a direct run is fetched only in these three statuses */
                }
            }
        }
        BigDecimal spendToday = usage.spendSince(orgId, midnight);
        return new Stats(
                running,
                waitingApproval,
                waitingInput,
                queued,
                held,
                completedToday,
                failedToday,
                spendToday,
                goalsCompletedToday,
                goalsFailedToday,
                directRuns.size());
    }

    private List<AgentSummary> computeAgentSummaries(
            java.util.Collection<Agent> allAgents,
            Map<UUID, List<Task>> tasksByGoal,
            Map<UUID, Run> latestRunByTask,
            List<Run> directRuns) {
        Map<UUID, List<UUID>> runningByAgent = new LinkedHashMap<>();
        Map<UUID, List<UUID>> waitingByAgent = new LinkedHashMap<>();
        Map<UUID, List<UUID>> askingByAgent = new LinkedHashMap<>();
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
                            runningByAgent
                                    .computeIfAbsent(agentId, key -> new ArrayList<>())
                                    .add(run.getId());
                        }
                    }
                    case "waiting_approval" -> {
                        if (run != null) {
                            waitingByAgent
                                    .computeIfAbsent(agentId, key -> new ArrayList<>())
                                    .add(run.getId());
                        }
                    }
                    case "waiting_input" -> {
                        if (run != null) {
                            askingByAgent
                                    .computeIfAbsent(agentId, key -> new ArrayList<>())
                                    .add(run.getId());
                        }
                    }
                    case "pending", "ready" -> queuedByAgent.merge(agentId, 1, Integer::sum);
                    default -> {
                        /* nothing to add for a finished task */
                    }
                }
            }
        }
        for (Run run : directRuns) {
            UUID agentId = run.getAgentId();
            if (agentId == null) {
                continue;
            }
            switch (run.getStatus()) {
                case "running" ->
                    runningByAgent
                            .computeIfAbsent(agentId, key -> new ArrayList<>())
                            .add(run.getId());
                case "waiting_approval" ->
                    waitingByAgent
                            .computeIfAbsent(agentId, key -> new ArrayList<>())
                            .add(run.getId());
                case "waiting_input" ->
                    askingByAgent
                            .computeIfAbsent(agentId, key -> new ArrayList<>())
                            .add(run.getId());
                default -> {
                    /* a direct run is fetched only in these three statuses */
                }
            }
        }
        return allAgents.stream()
                .map(agent -> new AgentSummary(
                        agent.getId(),
                        agent.getName(),
                        agent.getCategory(),
                        agent.getStatus(),
                        agent.isFallback(),
                        runningByAgent.getOrDefault(agent.getId(), List.of()),
                        waitingByAgent.getOrDefault(agent.getId(), List.of()),
                        askingByAgent.getOrDefault(agent.getId(), List.of()),
                        queuedByAgent.getOrDefault(agent.getId(), 0)))
                .toList();
    }

    /**
     * The tasks waiting to start, each with why. Almost every goal they belong to was already read
     * for the rest of the board; the few that were not are fetched with one batched goal lookup and
     * one batched task lookup, never a pair of queries per queued task.
     */
    private List<QueueEntry> computeQueue(
            UUID orgId, Map<UUID, Goal> goalById, Map<UUID, List<Task>> tasksByGoal, Map<UUID, Agent> agentById) {
        List<Task> queued = tasks.findQueued(orgId, PageRequest.of(0, QUEUE_FETCH_LIMIT));
        Set<UUID> missingGoalIds = new LinkedHashSet<>();
        for (Task task : queued) {
            if (!goalById.containsKey(task.getGoalId())) {
                missingGoalIds.add(task.getGoalId());
            }
        }
        Map<UUID, Goal> extraGoalById = new LinkedHashMap<>();
        Map<UUID, List<Task>> extraTasksByGoal = new LinkedHashMap<>();
        if (!missingGoalIds.isEmpty()) {
            for (Goal goal : goals.findAllById(missingGoalIds)) {
                if (orgId.equals(goal.getOrgId())) {
                    extraGoalById.put(goal.getId(), goal);
                }
            }
            if (!extraGoalById.isEmpty()) {
                tasks.findByGoalIdInOrderByPositionAsc(extraGoalById.keySet())
                        .forEach(task -> extraTasksByGoal
                                .computeIfAbsent(task.getGoalId(), key -> new ArrayList<>())
                                .add(task));
            }
        }

        List<QueueEntry> entries = new ArrayList<>();
        for (Task task : queued) {
            Goal goal = goalById.getOrDefault(task.getGoalId(), extraGoalById.get(task.getGoalId()));
            if (goal == null) {
                continue;
            }
            List<Task> siblings = tasksByGoal.getOrDefault(goal.getId(), extraTasksByGoal.get(goal.getId()));
            String reason = reasonFor(task, siblings == null ? List.of(task) : siblings, agentById);
            entries.add(new QueueEntry(
                    goal.getId(),
                    goal.getTitle(),
                    task.getId(),
                    task.getAgentId(),
                    task.getPosition(),
                    reason,
                    goal.getRequestedBy(),
                    goal.getSource(),
                    task.getCreatedAt()));
        }
        return entries;
    }

    private List<TimelineEntry> computeTimeline(
            UUID orgId, Instant since, int runFetchLimit, Map<UUID, UUID> goalIdByTaskId, Set<UUID> hiddenTasks) {
        return runs.findRecent(orgId, PageRequest.of(0, runFetchLimit)).stream()
                .filter(run -> run.getTaskId() == null || !hiddenTasks.contains(run.getTaskId()))
                .filter(run -> run.getStartedAt() != null && !run.getStartedAt().isBefore(since))
                .map(run -> new TimelineEntry(
                        run.getId(),
                        run.getAgentId(),
                        run.getTaskId() == null ? null : goalIdByTaskId.get(run.getTaskId()),
                        run.getStatus(),
                        run.getStartedAt(),
                        run.getCompletedAt()))
                .toList();
    }

    /**
     * Every pending approval, with the goal it belongs to (however that goal is found) and whether
     * the caller may decide it.
     *
     * <p>Most approvals belong to a task whose goal was already read for the rest of the board; the
     * few whose goal fell outside the window are resolved with one batched task lookup and one
     * batched goal lookup, never one query per approval.
     */
    private List<ApprovalSummary> computeApprovals(
            UUID orgId,
            Map<UUID, UUID> goalIdByTaskId,
            Map<UUID, Goal> goalById,
            Map<UUID, Run> directRunById,
            Actor actor) {
        List<Approval> pending = approvals.pending(orgId);
        if (pending.isEmpty()) {
            return List.of();
        }

        Set<UUID> missingTaskIds = new LinkedHashSet<>();
        for (Approval approval : pending) {
            UUID taskId = approval.getTaskId();
            if (taskId != null && !goalIdByTaskId.containsKey(taskId)) {
                missingTaskIds.add(taskId);
            }
        }
        Map<UUID, UUID> extraGoalIdByTaskId = new LinkedHashMap<>();
        Map<UUID, Goal> extraGoalById = new LinkedHashMap<>();
        if (!missingTaskIds.isEmpty()) {
            Set<UUID> extraGoalIds = new LinkedHashSet<>();
            for (Task task : tasks.findAllById(missingTaskIds)) {
                if (!orgId.equals(task.getOrgId())) {
                    continue;
                }
                extraGoalIdByTaskId.put(task.getId(), task.getGoalId());
                if (!goalById.containsKey(task.getGoalId())) {
                    extraGoalIds.add(task.getGoalId());
                }
            }
            if (!extraGoalIds.isEmpty()) {
                for (Goal goal : goals.findAllById(extraGoalIds)) {
                    if (orgId.equals(goal.getOrgId())) {
                        extraGoalById.put(goal.getId(), goal);
                    }
                }
            }
        }

        List<ApprovalSummary> summaries = new ArrayList<>(pending.size());
        for (Approval approval : pending) {
            UUID taskId = approval.getTaskId();
            UUID goalId = null;
            UUID requestedBy = null;
            if (taskId != null) {
                goalId = goalIdByTaskId.get(taskId);
                if (goalId == null) {
                    goalId = extraGoalIdByTaskId.get(taskId);
                }
                Goal goal = goalId == null ? null : goalById.getOrDefault(goalId, extraGoalById.get(goalId));
                requestedBy = goal == null ? null : goal.getRequestedBy();
            } else {
                Run run = directRunById.get(approval.getRunId());
                requestedBy = run == null ? null : parseUuidOrNull(run.getCreatedBy());
            }
            boolean canDecide = actor != null && actor.hasPermission(approval.getRequiredPermission());
            summaries.add(new ApprovalSummary(
                    approval.getId(),
                    approval.getRunId(),
                    taskId,
                    goalId,
                    approval.getAgentId(),
                    approval.getTool(),
                    approval.getActionClass(),
                    approval.getSummary(),
                    approval.getRequestedAt(),
                    approval.getExpiresAt(),
                    requestedBy,
                    canDecide));
        }
        return summaries;
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
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
            return siblings.stream()
                    .filter(sibling -> dependsOn.contains(sibling.getId()))
                    .toList();
        }
        return siblings.stream()
                .filter(sibling -> sibling.getPosition() < task.getPosition())
                .toList();
    }

    // ---- Stopping everything -------------------------------------------------------------------

    /**
     * Cancels every active goal (which stops its open tasks, their active runs, and withdraws any
     * approval or question they were waiting on), then separately stops any active run that
     * belongs to no goal at all - a run started directly on an agent rather than through a goal -
     * and, when asked, pauses every enabled schedule first so nothing new can start while the rest
     * is being stopped.
     *
     * <p>Deliberately not {@code @Transactional}: each goal and each run is cancelled in its own
     * transaction (through {@link GoalService#cancelIfActive} and {@link
     * GoalService#stopRunIfActive}), so a goal that finishes on its own in the middle of this call,
     * or a lock this call must wait behind, can never turn the rest of the sweep into a rollback.
     *
     * <p>Schedules are paused through {@link ScheduleService#pauseInternal}, not the per-owner
     * pause, so a caller with {@code run:cancel} reaches every schedule whoever owns it. The whole
     * call is recorded once in the audit trail, as {@code orchestrator.stop_all} with its counts.
     */
    public StopAllResult stopAll(UUID orgId, boolean pauseSchedules, Actor actor) {
        int schedulesPaused = 0;
        if (pauseSchedules) {
            if (actor == null || !actor.hasPermission(Permission.Codes.TASK_CREATE)) {
                throw new ApiException(
                                ErrorCode.PERMISSION_DENIED, "Pausing schedules needs permission to create work.")
                        .with("requiredPermission", Permission.Codes.TASK_CREATE);
            }
            for (Schedule schedule : schedules.list(orgId)) {
                if (!schedule.isEnabled()) {
                    continue;
                }
                try {
                    // The internal pause, not the per-owner one: Stop everything has to reach every
                    // schedule, whoever owns it, and its caller was already checked above.
                    schedules.pauseInternal(orgId, schedule.getId(), ScheduleService.STOPPED_EVERYTHING_REASON);
                    schedulesPaused++;
                } catch (RuntimeException e) {
                    log.warn("Could not pause schedule {}: {}", schedule.getId(), e.getMessage());
                }
            }
        }

        int runsCancelled = 0;
        int tasksCancelled = 0;
        int approvalsWithdrawn = 0;
        int questionsWithdrawn = 0;
        int goalsSkipped = 0;
        int runsSkipped = 0;

        for (UUID goalId : goals.activeIds(orgId)) {
            try {
                Optional<GoalService.CancelCounts> counts = goalService.cancelIfActive(orgId, goalId, STOPPED_REASON);
                if (counts.isPresent()) {
                    GoalService.CancelCounts c = counts.get();
                    runsCancelled += c.runs();
                    tasksCancelled += c.tasks();
                    approvalsWithdrawn += c.approvals();
                    questionsWithdrawn += c.questions();
                } else {
                    goalsSkipped++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not cancel goal {}: {}", goalId, e.getMessage());
                goalsSkipped++;
            }
        }

        // A run with no task belongs to no goal, so the cancellations above never reach it.
        for (Run run : runs.findByOrgIdAndTaskIdIsNullAndStatusIn(orgId, DIRECT_RUN_STATUSES)) {
            try {
                Optional<GoalService.CancelCounts> counts =
                        goalService.stopRunIfActive(orgId, run.getId(), STOPPED_REASON);
                if (counts.isPresent()) {
                    GoalService.CancelCounts c = counts.get();
                    runsCancelled += c.runs();
                    approvalsWithdrawn += c.approvals();
                    questionsWithdrawn += c.questions();
                } else {
                    runsSkipped++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not stop run {}: {}", run.getId(), e.getMessage());
                runsSkipped++;
            }
        }

        StopAllResult result = new StopAllResult(
                runsCancelled,
                tasksCancelled,
                approvalsWithdrawn,
                questionsWithdrawn,
                schedulesPaused,
                goalsSkipped,
                runsSkipped);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("pauseSchedules", pauseSchedules);
        counts.put("runsCancelled", runsCancelled);
        counts.put("tasksCancelled", tasksCancelled);
        counts.put("approvalsWithdrawn", approvalsWithdrawn);
        counts.put("questionsWithdrawn", questionsWithdrawn);
        counts.put("schedulesPaused", schedulesPaused);
        counts.put("goalsSkipped", goalsSkipped);
        counts.put("runsSkipped", runsSkipped);
        schedules.recordStopAll(orgId, actor, counts);
        return result;
    }
}
