package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
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

    private final Runs runs;
    private final RunSteps steps;
    private final ApprovalService approvals;

    public RunController(
            Runs runs,
            RunSteps steps,
            ApprovalService approvals) {
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
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
            String failureReason) {}

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

    @GetMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Recent runs in this workspace")
    public List<RunView> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return runs.findByOrgIdOrderByStartedAtDesc(orgId(), PageRequest.of(page, Math.min(size, 100)))
                .map(RunController::toView)
                .toList();
    }

    @GetMapping("/{runId}")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "One run")
    public RunView get(@PathVariable UUID runId) {
        return toView(runs.findByIdAndOrgId(runId, orgId())
                .orElseThrow(() -> ApiException.notFound("run", runId)));
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

    @PostMapping("/{runId}/cancel")
    @RequiresPermission(Permission.Codes.RUN_CANCEL)
    @Transactional
    @Operation(summary = "Stop a run in progress")
    public RunView cancel(@PathVariable UUID runId) {
        Run run = runs.findByIdAndOrgId(runId, orgId())
                .orElseThrow(() -> ApiException.notFound("run", runId));
        if (!run.isActive()) {
            throw new ApiException(ErrorCode.CONFLICT, "That run has already finished.");
        }
        run.finish("cancelled", "Cancelled by " + RequestContext.requireActor().id());
        runs.save(run);
        // Open approvals for a cancelled run must not stay in the queue: an approver deciding
        // one would resume a run somebody has already stopped.
        approvals.cancelForRun(runId);
        return toView(run);
    }

    private static RunView toView(Run run) {
        return new RunView(
                run.getId(), run.getAgentId(), run.getTaskId(), run.getStatus(), run.getTrigger(),
                run.getStepCount(), run.getTotalPromptTokens(), run.getTotalCompletionTokens(),
                run.getTotalCost(), run.getStartedAt(), run.getCompletedAt(), run.getFailureReason());
    }

    private static StepView toStepView(RunStep step) {
        return new StepView(
                step.getId(), step.getPosition(), step.getKind(), step.getDetail(),
                step.getProviderId(), step.getModelId(), step.getPromptTokens(),
                step.getCompletionTokens(), step.getDurationMs(), step.getOccurredAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
