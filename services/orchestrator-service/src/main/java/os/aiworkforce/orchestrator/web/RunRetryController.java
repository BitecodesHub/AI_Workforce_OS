package os.aiworkforce.orchestrator.web;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Try again for a run that was started directly on an agent.
 *
 * <p>A run that belongs to a goal is retried through the goal, which knows which step to start from
 * and what had already been done; this is for the other kind, which has only its instruction. A new
 * run is started with the original instruction and the old one is left exactly as it was, so the
 * history of what happened the first time stays readable.
 */
@RestController
@RequestMapping("/api/runs")
@Tag(name = "Runs")
public class RunRetryController {

    private static final Set<String> RETRYABLE = Set.of("failed", "abandoned", "cancelled");

    private final Runs runs;
    private final RunSteps steps;
    private final AgentRunner runner;
    private final RunExecutor executor;

    public RunRetryController(Runs runs, RunSteps steps, AgentRunner runner, RunExecutor executor) {
        this.runs = runs;
        this.steps = steps;
        this.runner = runner;
        this.executor = executor;
    }

    public record Retried(UUID runId, UUID retryOf, String status) {}

    @PostMapping("/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.AGENT_RUN)
    @Operation(summary = "Start a failed, abandoned or cancelled direct run again with its original instruction")
    public Retried retry(@PathVariable UUID runId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Actor actor = RequestContext.requireActor();
        Run run = runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));

        if (run.getTaskId() != null) {
            throw ApiException.conflict("This run is part of a goal. Try the goal again instead.");
        }
        if (!RETRYABLE.contains(run.getStatus())) {
            throw ApiException.conflict("Only a run that failed, stopped responding or was cancelled can be tried again.");
        }
        boolean requester = actor.humanId() != null && actor.humanId().equals(run.getCreatedBy());
        if (!requester && !actor.hasPermission(Permission.Codes.RUN_CANCEL)) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED,
                    "Only the person who started this run, or someone who can stop runs, can try it again.");
        }

        String instruction = instructionOf(runId);
        if (instruction == null) {
            throw ApiException.conflict("This run has no recorded instruction to start again from.");
        }
        UUID fresh = runner.prepare(orgId, run.getAgentId(), null, instruction, "manual");
        executor.submitDrive(orgId, fresh, actor);
        return new Retried(fresh, runId, "running");
    }

    /** The instruction the run was given, from the first note step of its trace. */
    private String instructionOf(UUID runId) {
        for (RunStep step : steps.findByRunIdOrderByPosition(runId)) {
            if ("note".equals(step.getKind()) && "instruction".equals(step.getDetail().get("type"))) {
                Object content = step.getDetail().get("content");
                return content instanceof String text && !text.isBlank() ? text : null;
            }
        }
        return null;
    }
}
