package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The approvals queue.
 *
 * <p>Approving resumes the run immediately rather than waiting for a scheduler to notice, because
 * the person who just approved something is still watching the screen.
 */
@RestController
@RequestMapping("/api/approvals")
@Tag(name = "Approvals")
public class ApprovalController {

    private final ApprovalService approvals;
    private final AgentRunner runner;

    public ApprovalController(ApprovalService approvals, AgentRunner runner) {
        this.approvals = approvals;
        this.runner = runner;
    }

    public record ApprovalView(
            UUID id,
            UUID runId,
            UUID agentId,
            String tool,
            String actionClass,
            String summary,
            /** The tool call's arguments, exactly as the model produced them, as a JSON document. */
            String payload,
            String status,
            Instant requestedAt,
            Instant expiresAt) {}

    public record DecisionRequest(boolean approved, @Size(max = 1_000) String note) {}

    public record DecisionResult(UUID approvalId, String status, String runStatus) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.APPROVAL_READ)
    @Operation(summary = "Actions waiting for a decision")
    public List<ApprovalView> pending() {
        return approvals.pending(orgId()).stream().map(ApprovalController::toView).toList();
    }

    @PostMapping("/{approvalId}/decision")
    @RequiresPermission(Permission.Codes.APPROVAL_DECIDE)
    @Operation(summary = "Approve or reject an action")
    public DecisionResult decide(
            @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest request) {
        UUID orgId = orgId();
        Approval approval = approvals.decide(orgId, approvalId, request.approved(), request.note());

        if (!request.approved()) {
            return new DecisionResult(approval.getId(), approval.getStatus(), "cancelled");
        }

        // Resumed here rather than by a sweep: the approver is still looking at the screen, and
        // a queue that clears only on the next tick feels broken.
        AgentRunner.Outcome outcome = runner.resume(orgId, approval.getRunId());
        return new DecisionResult(approval.getId(), approval.getStatus(), outcome.status());
    }

    private static ApprovalView toView(Approval approval) {
        return new ApprovalView(
                approval.getId(),
                approval.getRunId(),
                approval.getAgentId(),
                approval.getTool(),
                approval.getActionClass(),
                approval.getSummary(),
                approval.getPayload(),
                approval.getStatus(),
                approval.getRequestedAt(),
                approval.getExpiresAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
