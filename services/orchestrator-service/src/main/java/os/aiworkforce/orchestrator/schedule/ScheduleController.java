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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Schedules: recurring and one-off work a person sets up ahead of time.
 *
 * <p>Reads {@code task:read}, writes {@code task:create} - schedules are a way of creating tasks
 * later rather than a permission of their own, matching the plan's decision to reuse the codes
 * that already exist rather than invent {@code schedule:*}. Changing one that already exists is
 * narrower still: {@code task:create} only lets a person through the door, and {@link
 * ScheduleService#requireCanManage} then admits its owner or someone who holds {@code task:cancel}.
 * Handing a schedule to somebody else needs {@code task:cancel} outright.
 */
@RestController
@RequestMapping("/api/schedules")
@Tag(name = "Schedules")
public class ScheduleController {

    /** A history page's size when none is asked for, and the most one may hold. */
    static final int DEFAULT_RUNS_PAGE = 20;

    static final int MAX_RUNS_PAGE = 100;

    private final ScheduleService service;
    private final Agents agents;

    public ScheduleController(ScheduleService service, Agents agents) {
        this.service = service;
        this.agents = agents;
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

    public record ChangeOwnerRequest(@NotNull UUID userId) {}

    /**
     * One schedule as the console reads it.
     *
     * @param state {@code active}, {@code paused}, or {@code done} for a one-off that has already
     *     run - so every screen tells "finished" from "stopped" by one rule, not each its own
     * @param completed whether {@code state} is {@code done}
     * @param createdBy who the schedule runs as: the person who set it up, or whoever took it on
     *     since by changing what it does or by a transfer
     */
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
            Instant createdAt,
            String state,
            boolean completed) {}

    /** One goal a schedule fired, as its history lists it: no tasks, which that list never shows. */
    public record ScheduleRunView(UUID id, String title, String status, Instant createdAt, Instant completedAt) {}

    /** One page of a schedule's history, newest first, and whether an older page follows. */
    public record ScheduleRunsPage(List<ScheduleRunView> runs, int page, int size, long total, boolean hasMore) {}

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
                orgId(),
                scheduleId,
                request.name(),
                request.agentId(),
                request.instruction(),
                request.text(),
                RequestContext.requireActor());
        return toView(schedule);
    }

    @PutMapping("/{scheduleId}/owner")
    @RequiresPermission(Permission.Codes.TASK_CANCEL)
    @Operation(summary = "Hand a schedule to another person, who it then runs as")
    public ScheduleView changeOwner(@PathVariable UUID scheduleId, @Valid @RequestBody ChangeOwnerRequest request) {
        return toView(service.changeOwner(orgId(), scheduleId, request.userId(), RequestContext.requireActor()));
    }

    @PostMapping("/{scheduleId}/pause")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Pause a schedule so it is skipped until resumed")
    public ScheduleView pause(@PathVariable UUID scheduleId) {
        return toView(service.pause(orgId(), scheduleId, RequestContext.requireActor()));
    }

    @PostMapping("/{scheduleId}/resume")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Resume a paused schedule")
    public ScheduleView resume(@PathVariable UUID scheduleId) {
        return toView(service.resume(orgId(), scheduleId, RequestContext.requireActor()));
    }

    @PostMapping("/{scheduleId}/run-now")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Fire a schedule immediately, as the caller, without waiting for its next run")
    public ScheduleView runNow(@PathVariable UUID scheduleId) {
        return toView(service.runNow(orgId(), scheduleId, RequestContext.requireActor()));
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Delete a schedule")
    public void delete(@PathVariable UUID scheduleId) {
        service.delete(orgId(), scheduleId, RequestContext.requireActor());
    }

    /**
     * The goals this schedule has fired, newest first, a page at a time.
     *
     * <p>Slim rows with no tasks: the history lists what each run was called, how it ended and
     * when, and a schedule that fires every few minutes has thousands of them a month, so each
     * page is one indexed query rather than one per goal and per task.
     */
    @GetMapping("/{scheduleId}/runs")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "The goals this schedule has fired, newest first, a page at a time")
    public ScheduleRunsPage runs(
            @PathVariable UUID scheduleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_RUNS_PAGE) int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_RUNS_PAGE));
        Page<Goal> goals = service.runs(orgId(), scheduleId, pageable);
        return new ScheduleRunsPage(
                goals.getContent().stream().map(ScheduleController::toRunView).toList(),
                goals.getNumber(),
                goals.getSize(),
                goals.getTotalElements(),
                goals.hasNext());
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
                schedule.getCreatedAt(),
                stateOf(schedule),
                ScheduleService.isCompleted(schedule));
    }

    /** {@code done} for a one-off that already ran, otherwise what {@code enabled} says. */
    static String stateOf(Schedule schedule) {
        if (ScheduleService.isCompleted(schedule)) {
            return "done";
        }
        return schedule.isEnabled() ? "active" : "paused";
    }

    private static ScheduleRunView toRunView(Goal goal) {
        return new ScheduleRunView(
                goal.getId(), goal.getTitle(), goal.getStatus(), goal.getCreatedAt(), goal.getCompletedAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
