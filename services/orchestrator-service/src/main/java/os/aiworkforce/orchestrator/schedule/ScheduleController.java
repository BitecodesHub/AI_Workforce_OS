package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.web.GoalController;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Schedules: recurring and one-off work a person sets up ahead of time.
 *
 * <p>Reads {@code task:read}, writes {@code task:create} - schedules are a way of creating tasks
 * later rather than a permission of their own, matching the plan's decision to reuse the codes
 * that already exist rather than invent {@code schedule:*}.
 */
@RestController
@RequestMapping("/api/schedules")
@Tag(name = "Schedules")
public class ScheduleController {

    private final ScheduleService service;
    private final Agents agents;
    private final Tasks tasks;
    private final Runs runs;

    public ScheduleController(ScheduleService service, Agents agents, Tasks tasks, Runs runs) {
        this.service = service;
        this.agents = agents;
        this.tasks = tasks;
        this.runs = runs;
    }

    public record PreviewRequest(@NotBlank @Size(max = 200) String text, String timezone) {}

    public record PreviewResponse(
            String kind, String cron, Instant runAt, String description, String timezone, List<Instant> nextRuns) {}

    public record CreateScheduleRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID agentId,
            @NotBlank @Size(max = 10_000) String instruction,
            @NotBlank @Size(max = 200) String text) {}

    public record UpdateScheduleRequest(
            @Size(max = 120) String name,
            UUID agentId,
            @Size(max = 10_000) String instruction,
            @Size(max = 200) String text) {}

    public record ScheduleView(
            UUID id,
            String name,
            UUID agentId,
            String agentName,
            String instruction,
            String kind,
            String cron,
            Instant runAt,
            String timezone,
            String description,
            boolean enabled,
            String overlapPolicy,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastStatus,
            UUID lastGoalId,
            int consecutiveFailures,
            String pausedReason,
            UUID createdBy,
            Instant createdAt) {}

    @PostMapping("/preview")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Read a schedule phrase back, without saving anything")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        ScheduleService.PreviewResult result = service.preview(orgId(), request.text(), request.timezone());
        return new PreviewResponse(
                result.kind(),
                result.cron(),
                result.runAt(),
                result.description(),
                result.timezone(),
                result.nextRuns());
    }

    @GetMapping
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Every schedule in this workspace")
    public List<ScheduleView> list() {
        return service.list(orgId()).stream().map(this::toView).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Create a schedule")
    public ScheduleView create(@Valid @RequestBody CreateScheduleRequest request) {
        Schedule schedule =
                service.create(orgId(), request.name(), request.agentId(), request.instruction(), request.text());
        return toView(schedule);
    }

    @PutMapping("/{scheduleId}")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Change a schedule's name, agent, instruction or timing")
    public ScheduleView update(@PathVariable UUID scheduleId, @Valid @RequestBody UpdateScheduleRequest request) {
        Schedule schedule = service.update(
                orgId(), scheduleId, request.name(), request.agentId(), request.instruction(), request.text());
        return toView(schedule);
    }

    @PostMapping("/{scheduleId}/pause")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Pause a schedule so it is skipped until resumed")
    public ScheduleView pause(@PathVariable UUID scheduleId) {
        return toView(service.pause(orgId(), scheduleId));
    }

    @PostMapping("/{scheduleId}/resume")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Resume a paused schedule")
    public ScheduleView resume(@PathVariable UUID scheduleId) {
        return toView(service.resume(orgId(), scheduleId));
    }

    @PostMapping("/{scheduleId}/run-now")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Fire a schedule immediately, without waiting for its next run")
    public ScheduleView runNow(@PathVariable UUID scheduleId) {
        return toView(service.runNow(orgId(), scheduleId));
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Delete a schedule")
    public void delete(@PathVariable UUID scheduleId) {
        service.delete(orgId(), scheduleId);
    }

    @GetMapping("/{scheduleId}/runs")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "The goals this schedule has fired, newest first")
    public List<GoalController.GoalView> runs(@PathVariable UUID scheduleId) {
        return service.runs(orgId(), scheduleId).stream().map(this::toGoalView).toList();
    }

    private ScheduleView toView(Schedule schedule) {
        String agentName =
                agents.findById(schedule.getAgentId()).map(Agent::getName).orElse(null);
        return new ScheduleView(
                schedule.getId(),
                schedule.getName(),
                schedule.getAgentId(),
                agentName,
                schedule.getInstruction(),
                schedule.getKind(),
                schedule.getCron(),
                schedule.getRunAt(),
                schedule.getTimezone(),
                schedule.getDescription(),
                schedule.isEnabled(),
                schedule.getOverlapPolicy(),
                schedule.getNextRunAt(),
                schedule.getLastRunAt(),
                schedule.getLastStatus(),
                schedule.getLastGoalId(),
                schedule.getConsecutiveFailures(),
                schedule.getPausedReason(),
                schedule.getRequestedBy(),
                schedule.getCreatedAt());
    }

    private GoalController.GoalView toGoalView(Goal goal) {
        List<GoalController.TaskView> taskViews = tasks.findByGoalIdOrderByPosition(goal.getId()).stream()
                .map(this::toTaskView)
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

    private GoalController.TaskView toTaskView(Task task) {
        UUID runId = runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())
                .map(run -> run.getId())
                .orElse(null);
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
                runId);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
