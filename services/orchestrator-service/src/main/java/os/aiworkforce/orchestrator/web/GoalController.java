package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
 * second button to begin it has been asked the same question twice, so the response is the goal
 * as it stands after its first task has run - completed, waiting on an approval, or failed with a
 * reason.
 */
@RestController
@RequestMapping("/api/goals")
@Tag(name = "Goals")
public class GoalController {

    /** Tasks run synchronously on creation are capped, so one request cannot run for minutes. */
    private static final int MAX_TASKS_RUN_ON_CREATE = 3;

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final GoalService service;

    public GoalController(Goals goals, Tasks tasks, Runs runs, GoalService service) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.service = service;
    }

    public record TaskView(
            UUID id, UUID agentId, String title, String status, int attempt, int maxAttempts,
            String result, String failureReason, Instant startedAt, Instant completedAt,
            /**
             * The most recent run against this task, so the interface can link straight to its
             * trace. Null only for a task that has never actually run yet.
             */
            UUID runId) {}

    public record GoalView(
            UUID id, String title, String description, String status,
            Instant createdAt, Instant completedAt, List<TaskView> tasks) {}

    public record TaskInput(@NotNull UUID agentId, @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10_000) String instruction, List<Integer> dependsOn) {}

    public record CreateGoalRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4_000) String description,
            @Valid @NotNull @Size(min = 1, max = 50) List<TaskInput> tasks) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Recent goals in this workspace, with their tasks")
    public List<GoalView> list() {
        UUID orgId = orgId();
        return goals.findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, 50)).stream()
                .map(this::toView)
                .toList();
    }

    @GetMapping("/{goalId}")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "One goal and its tasks")
    public GoalView get(@PathVariable UUID goalId) {
        return toView(goals.findByIdAndOrgId(goalId, orgId())
                .orElseThrow(() -> ApiException.notFound("goal", goalId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Create a goal and start it")
    public GoalView create(@Valid @RequestBody CreateGoalRequest request) {
        UUID orgId = orgId();
        Goal goal = service.create(orgId, request.title(), request.description(),
                request.tasks().stream()
                        .map(task -> new GoalService.TaskRequest(
                                task.title(), task.instruction(), task.agentId(), task.dependsOn()))
                        .toList());

        for (int i = 0; i < MAX_TASKS_RUN_ON_CREATE; i++) {
            if (!service.runNextTask(orgId)) {
                break;
            }
        }
        return toView(goals.findById(goal.getId()).orElse(goal));
    }

    @PostMapping("/{goalId}/cancel")
    @RequiresPermission(Permission.Codes.TASK_CANCEL)
    @Operation(summary = "Cancel a goal and every task still open under it")
    public GoalView cancel(@PathVariable UUID goalId) {
        UUID orgId = orgId();
        service.cancel(orgId, goalId);
        return toView(goals.findByIdAndOrgId(goalId, orgId)
                .orElseThrow(() -> ApiException.notFound("goal", goalId)));
    }

    private GoalView toView(Goal goal) {
        List<TaskView> taskViews = tasks.findByGoalIdOrderByPosition(goal.getId()).stream()
                .map(this::toView)
                .toList();
        return new GoalView(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(),
                goal.getCreatedAt(), goal.getCompletedAt(), taskViews);
    }

    private TaskView toView(Task task) {
        UUID runId = runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())
                .map(run -> run.getId())
                .orElse(null);
        return new TaskView(task.getId(), task.getAgentId(), task.getTitle(), task.getStatus(),
                task.getAttempt(), task.getMaxAttempts(), task.getResult(), task.getFailureReason(),
                task.getStartedAt(), task.getCompletedAt(), runId);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
