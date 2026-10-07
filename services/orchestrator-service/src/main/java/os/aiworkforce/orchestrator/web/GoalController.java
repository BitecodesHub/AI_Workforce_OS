package os.aiworkforce.orchestrator.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.board.GoalViews;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Goals and the tasks beneath them.
 *
 * <p>Creating a goal starts it. A person who describes a piece of work and then has to find a
 * second button to begin it has been asked the same question twice. The goal is answered as soon
 * as it is saved, with its first task about to start: the work happens in the background, and the
 * goal and its run show it as it goes. Running the task inside this request would hold the person
 * for as long as the agent took - past the gateway's one-minute limit - and could run somebody
 * else's queued work first.
 */
@RestController
@RequestMapping("/api/goals")
@Tag(name = "Goals")
public class GoalController {

    /** The statuses a goal can be in, and so the values the list's {@code status} filter takes. */
    private static final List<String> STATUSES =
            List.of("planning", "running", "waiting", "completed", "failed", "cancelled");
    /** How a goal can have come to exist, and so the values the list's {@code source} filter takes. */
    private static final List<String> SOURCES = List.of("manual", "chat", "schedule");
    /** Stands in for the schedule id of a list with no schedule filter; never matched, never null. */
    private static final UUID NO_SCHEDULE = new UUID(0L, 0L);

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final GoalService service;

    /** Who may read which conversation; absent only where a test builds this by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.orchestrator.chat.ConversationAccess access;

    public GoalController(Goals goals, Tasks tasks, Runs runs, GoalService service) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.service = service;
    }

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
            Instant startedAt,
            Instant completedAt,
            /**
             * The most recent run against this task, so the interface can link straight to its
             * trace. Null only for a task that has never actually run yet.
             */
            UUID runId) {}

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

    public record TaskInput(
            @NotNull UUID agentId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10_000) String instruction,
            List<Integer> dependsOn) {}

    public record CreateGoalRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4_000) String description,
            @Valid @NotNull @Size(min = 1, max = 50) List<TaskInput> tasks) {}

    /**
     * Goals, newest first, a page at a time, optionally narrowed by status, source or schedule.
     *
     * <p>Each goal is returned with its tasks and each task with its latest run's id, and a page
     * reads them with one query for every goal's tasks and one for every task's runs, however many
     * goals it holds. The page is a plain list, so the call adds no {@code count(*)}. The filters
     * run in the database, so "every failed goal" reaches past the pages a person has loaded.
     */
    @GetMapping
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Recent goals in this workspace, with their tasks")
    public List<GoalView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) UUID scheduleId) {
        UUID orgId = orgId();
        String wantedStatus = status == null ? "" : status.strip();
        String wantedSource = source == null ? "" : source.strip();
        if (!wantedStatus.isEmpty() && !STATUSES.contains(wantedStatus)) {
            throw ApiException.validation("status", "must be one of " + STATUSES);
        }
        if (!wantedSource.isEmpty() && !SOURCES.contains(wantedSource)) {
            throw ApiException.validation("source", "must be one of " + SOURCES);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        boolean anySchedule = scheduleId == null;
        List<Goal> found = goals.findFiltered(
                orgId, wantedStatus, wantedSource, anySchedule, anySchedule ? NO_SCHEDULE : scheduleId, pageable);
        // Work started from somebody else's private conversation is not listed for this person.
        if (access != null && !found.isEmpty()) {
            Set<UUID> hidden = access.hiddenGoalIds(orgId, RequestContext.requireActor());
            found = found.stream().filter(goal -> !hidden.contains(goal.getId())).toList();
        }
        return views(found);
    }

    @GetMapping("/{goalId}")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "One goal and its tasks")
    public GoalView get(@PathVariable UUID goalId) {
        return toView(visibleGoal(goalId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Create a goal and start it")
    public GoalView create(@Valid @RequestBody CreateGoalRequest request) {
        UUID orgId = orgId();
        // Started after the goal commits, on the executor's threads and as this person; nothing
        // runs inside this request, and no other goal's queued work is ever touched by it.
        Goal goal = service.createGoal(
                orgId,
                new GoalService.NewGoal(
                        request.title(),
                        request.description(),
                        null,
                        "manual",
                        null,
                        null,
                        request.tasks().stream()
                                .map(task -> new GoalService.NewTask(
                                        task.agentId(), task.title(), task.instruction(), task.dependsOn()))
                                .toList()),
                true);
        return toView(goals.findById(goal.getId()).orElse(goal));
    }

    @PostMapping("/{goalId}/retry")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Try a failed or stopped goal again, from the step that did not finish")
    public GoalView retry(@PathVariable UUID goalId) {
        UUID orgId = orgId();
        if (access != null) {
            visibleGoal(goalId);
        }
        // The person who asked for the work, or someone who can cancel work, else 403.
        service.retry(orgId, goalId, RequestContext.requireActor());
        return toView(goals.findByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId)));
    }

    /**
     * Stops a goal. Open to the person who asked for it as well as to anyone who can cancel work,
     * the same rule chat and Try again follow: whoever can start work can stop their own.
     */
    @PostMapping("/{goalId}/cancel")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Cancel a goal and every task still open under it")
    public GoalView cancel(@PathVariable UUID goalId) {
        UUID orgId = orgId();
        Goal goal = visibleGoal(goalId);
        // The requester, or someone who can cancel work, else 403; a finished goal is a 409.
        service.requireCanStop(goal, RequestContext.requireActor());
        service.cancel(orgId, goalId);
        return toView(goals.findByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId)));
    }

    /** The goal, unless it came from a private conversation this person is not part of: then it does not exist. */
    private Goal visibleGoal(UUID goalId) {
        Goal goal = goals.findByIdAndOrgId(goalId, orgId()).orElseThrow(() -> ApiException.notFound("goal", goalId));
        if (access != null && access.hidden(orgId(), RequestContext.requireActor(), goal.getConversationId())) {
            throw ApiException.notFound("goal", goalId);
        }
        return goal;
    }

    /** One goal with its tasks, from one query for the tasks and one for their runs. */
    private GoalView toView(Goal goal) {
        List<Task> goalTasks = tasks.findByGoalIdOrderByPosition(goal.getId());
        return GoalViews.plainView(goal, goalTasks, GoalViews.runsByTask(runs, ids(goalTasks)));
    }

    /** Several goals with their tasks, from one query for every goal's tasks and one for their runs. */
    private List<GoalView> views(List<Goal> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<Task> allTasks = tasks.findByGoalIdInOrderByPositionAsc(
                found.stream().map(Goal::getId).toList());
        Map<UUID, List<Task>> tasksByGoal = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getGoalId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, GoalViews.TaskRuns> runsByTask = GoalViews.runsByTask(runs, ids(allTasks));
        return found.stream()
                .map(goal -> GoalViews.plainView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), runsByTask))
                .toList();
    }

    private static List<UUID> ids(List<Task> taskList) {
        return taskList.stream().map(Task::getId).toList();
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
