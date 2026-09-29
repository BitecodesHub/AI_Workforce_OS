package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Runs and their traces.
 *
 * <p>The trace endpoint returns every step including the model attempts that failed. That is the
 * screen a person uses to decide whether to trust an answer, so leaving the failures out would
 * make the platform look more certain than it is.
 */
@RestController
@RequestMapping("/api/runs")
@Tag(name = "Runs")
public class RunController {

    /** Every value the runs_status_valid constraint allows. */
    static final Set<String> RUN_STATUSES =
            Set.of("running", "waiting_approval", "waiting_input", "completed", "failed", "cancelled", "abandoned");

    static final String RUN_STOPPED = "A person stopped this run before it finished.";

    private final Runs runs;
    private final RunSteps steps;
    private final Tasks tasks;
    private final Goals goals;
    private final QuestionService questions;
    private final GoalService goalService;

    public RunController(
            Runs runs, RunSteps steps, Tasks tasks, Goals goals, QuestionService questions, GoalService goalService) {
        this.runs = runs;
        this.steps = steps;
        this.tasks = tasks;
        this.goals = goals;
        this.questions = questions;
        this.goalService = goalService;
    }

    public record RunView(
            UUID id,
            UUID agentId,
            UUID taskId,
            String status,
            String trigger,
            int stepCount,
            int promptTokens,
            int completionTokens,
            BigDecimal cost,
            Instant startedAt,
            Instant completedAt,
            String failureReason,
            /** The goal this run's task belongs to. Null for a run started directly on an agent. */
            UUID goalId,
            /** Who asked for the goal this run belongs to. Null when that is not on record. */
            UUID requestedBy) {}

    public record StepView(
            UUID id,
            int position,
            String kind,
            Map<String, Object> detail,
            String provider,
            String model,
            int promptTokens,
            int completionTokens,
            long durationMs,
            Instant occurredAt) {}

    /**
     * Recent runs, newest first, optionally narrowed by status and agent.
     *
     * <p>The filters are applied in the database, not to the page already loaded, so "every
     * failed run" means every failed run in the workspace rather than the failures among the
     * newest few.
     */
    @GetMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Recent runs in this workspace, optionally filtered by status and agent")
    public List<RunView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID agentId) {
        UUID orgId = orgId();
        String wanted =
                status == null || status.isBlank() ? null : status.strip().toLowerCase(Locale.ROOT);
        if (wanted != null && !RUN_STATUSES.contains(wanted)) {
            throw ApiException.validation(
                    "status",
                    "must be one of running, waiting_approval, waiting_input, completed, failed, cancelled or abandoned");
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        Page<Run> result;
        if (wanted != null && agentId != null) {
            result = runs.findByOrgIdAndAgentIdAndStatusOrderByStartedAtDesc(orgId, agentId, wanted, pageable);
        } else if (wanted != null) {
            result = runs.findByOrgIdAndStatusOrderByStartedAtDesc(orgId, wanted, pageable);
        } else if (agentId != null) {
            result = runs.findByOrgIdAndAgentIdOrderByStartedAtDesc(orgId, agentId, pageable);
        } else {
            result = runs.findByOrgIdOrderByStartedAtDesc(orgId, pageable);
        }
        return toViews(result.getContent());
    }

    @GetMapping("/{runId}")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "One run")
    public RunView get(@PathVariable UUID runId) {
        Run run = runs.findByIdAndOrgId(runId, orgId()).orElseThrow(() -> ApiException.notFound("run", runId));
        return toViews(List.of(run)).getFirst();
    }

    @GetMapping("/{runId}/steps")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "The full trace, including attempts that failed")
    public List<StepView> trace(@PathVariable UUID runId) {
        runs.findByIdAndOrgId(runId, orgId()).orElseThrow(() -> ApiException.notFound("run", runId));
        return steps.findByRunIdOrderByPosition(runId).stream()
                .map(RunController::toStepView)
                .toList();
    }

    @GetMapping("/{runId}/questions")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "The questions this run asked, oldest first")
    public List<QuestionService.QuestionView> questions(@PathVariable UUID runId) {
        UUID orgId = orgId();
        runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        return questions.views(questions.forRun(orgId, runId), RequestContext.requireActor());
    }

    @PostMapping("/{runId}/cancel")
    @RequiresPermission(Permission.Codes.RUN_CANCEL)
    @Operation(summary = "Stop a run in progress")
    public RunView cancel(@PathVariable UUID runId) {
        UUID orgId = orgId();
        // The reason is shown on the trace as it is, so it is written for a person to read. Who
        // stopped the run is kept on the row itself (updated_by), not spelled out as an identifier.
        // The stop locks the run and withdraws any approval or question it was waiting on, so
        // nobody deciding or answering one can resume a run somebody has already stopped.
        goalService.stopRun(orgId, runId, RUN_STOPPED);
        Run run = runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        return toViews(List.of(run)).getFirst();
    }

    /**
     * Views for a batch of runs, with the goal and requester behind each one's task loaded in two
     * extra queries total rather than one pair per run.
     */
    private List<RunView> toViews(List<Run> runList) {
        Set<UUID> taskIds = new HashSet<>();
        for (Run run : runList) {
            if (run.getTaskId() != null) {
                taskIds.add(run.getTaskId());
            }
        }
        Map<UUID, UUID> taskGoal = new HashMap<>();
        if (!taskIds.isEmpty()) {
            for (Task task : tasks.findAllById(taskIds)) {
                taskGoal.put(task.getId(), task.getGoalId());
            }
        }
        Set<UUID> goalIds = new HashSet<>(taskGoal.values());
        Map<UUID, UUID> goalRequestedBy = new HashMap<>();
        if (!goalIds.isEmpty()) {
            for (Goal goal : goals.findAllById(goalIds)) {
                goalRequestedBy.put(goal.getId(), goal.getRequestedBy());
            }
        }

        List<RunView> views = new ArrayList<>();
        for (Run run : runList) {
            UUID goalId = run.getTaskId() == null ? null : taskGoal.get(run.getTaskId());
            UUID requestedBy = goalId == null ? null : goalRequestedBy.get(goalId);
            views.add(new RunView(
                    run.getId(),
                    run.getAgentId(),
                    run.getTaskId(),
                    run.getStatus(),
                    run.getTrigger(),
                    run.getStepCount(),
                    run.getTotalPromptTokens(),
                    run.getTotalCompletionTokens(),
                    run.getTotalCost(),
                    run.getStartedAt(),
                    run.getCompletedAt(),
                    run.getFailureReason(),
                    goalId,
                    requestedBy));
        }
        return views;
    }

    private static StepView toStepView(RunStep step) {
        return new StepView(
                step.getId(),
                step.getPosition(),
                step.getKind(),
                step.getDetail(),
                step.getProviderId(),
                step.getModelId(),
                step.getPromptTokens(),
                step.getCompletionTokens(),
                step.getDurationMs(),
                step.getOccurredAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
