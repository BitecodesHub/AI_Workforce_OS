package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * The human gate.
 *
 * <p>Everything here exists to make one guarantee hold: an action that leaves the workspace or
 * destroys something happened because a named person, holding the right permission, decided it
 * should - and the record of that decision cannot be altered afterwards.
 *
 * <p>Four cases are handled explicitly because each has produced a real incident in systems that
 * did not handle it: an approval decided twice by two people clicking at once; an approver whose
 * permission was removed between the request and the decision; an approval nobody answers; and a
 * run cancelled while an approval for it is still open.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final Approvals approvals;
    private final Runs runs;
    private final ObjectMapper objectMapper;
    private final AuditClient audit;
    private final TaskProgress progress;

    public ApprovalService(
            Approvals approvals, Runs runs, ObjectMapper objectMapper, AuditClient audit, TaskProgress progress) {
        this.approvals = approvals;
        this.runs = runs;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.progress = progress;
    }

    /** Raises an approval for a tool call the agent wants to make. */
    @Transactional
    public Approval raise(
            Run run, Agent agent, ToolInvocation invocation, ApprovalDecision.AwaitApproval await, ToolCall call) {
        Approval approval = new Approval();
        approval.setId(UuidV7.generate());
        approval.setOrgId(run.getOrgId());
        approval.setRunId(run.getId());
        approval.setTaskId(run.getTaskId());
        approval.setAgentId(agent.getId());
        approval.setTool(invocation.qualifiedName());
        approval.setToolCallId(call.id());
        approval.setActionClass("OUTBOUND");
        approval.setSummary(await.reason());
        approval.setPayload(toJsonPayload(invocation.argumentsJson()));
        approval.setRequiredPermission(await.approverPermission());
        approval.setExpiresAt(Instant.now().plus(DEFAULT_WINDOW));
        approvals.save(approval);

        log.info(
                "Approval {} raised for {} by agent {} in run {}",
                approval.getId(),
                invocation.qualifiedName(),
                agent.getName(),
                run.getId());
        return approval;
    }

    @Transactional(readOnly = true)
    public List<Approval> pending(UUID orgId) {
        return approvals.findPending(orgId);
    }

    /** One approval, for a caller that already knows its workspace - the resumed run, for one. */
    @Transactional(readOnly = true)
    public java.util.Optional<Approval> find(UUID orgId, UUID approvalId) {
        return approvals.findByIdAndOrgId(approvalId, orgId);
    }

    /**
     * Records a decision.
     *
     * <p>The permission is re-checked here, not only when the queue was rendered. A person can be
     * demoted between opening the page and clicking the button, and the check that matters is the
     * one made at the moment the action is authorised.
     *
     * <p>A refusal does not roll back what was written before it. The only write before a refusal
     * is marking an overdue approval expired, and that must stick: it is the truth whether or not
     * this caller's decision could be accepted.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Approval decide(UUID orgId, UUID approvalId, boolean approved, String note) {
        Actor actor = RequestContext.requireActor();
        Approval approval = approvals
                .findByIdAndOrgId(approvalId, orgId)
                .orElseThrow(() -> ApiException.notFound("approval", approvalId));

        if (!actor.hasPermission(approval.getRequiredPermission())) {
            throw ApiException.permissionDenied(approval.getRequiredPermission());
        }
        if (!approval.isPending()) {
            // Two approvers clicking at once would otherwise both succeed, and the run would act
            // twice on one request.
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED).with("status", approval.getStatus());
        }
        if (approval.hasExpired()) {
            expire(approval);
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED);
        }

        approval.decide(approved ? "approved" : "rejected", UUID.fromString(actor.id()), note);
        approvals.save(approval);

        if (!approved) {
            // A rejection ends the run rather than letting the agent look for another way to do
            // the same thing.
            runs.findById(approval.getRunId()).filter(Run::isActive).ifPresent(run -> {
                String reason = "An approver rejected the action this run needed.";
                run.finish("cancelled", reason);
                runs.save(run);
                progress.onRunFinished(run, "cancelled", null, reason);
            });
        }

        log.info("Approval {} {} by {}", approval.getId(), approved ? "approved" : "rejected", actor.id());

        // The run is named so an investigator can go from the decision straight to its trace.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approved", approved);
        detail.put("runId", approval.getRunId().toString());
        if (approval.getTool() != null) {
            detail.put("tool", approval.getTool());
        }
        audit.record(
                orgId, actor, "approval.decide", "approval", approval.getId().toString(), "succeeded", detail);

        return approval;
    }

    /**
     * Closes approvals nobody answered.
     *
     * <p>The default is to reject. An action that nobody approved must not happen because
     * everybody was busy, and a pending approval that silently becomes permission after a day is
     * not a gate at all.
     */
    @Transactional
    public int expireOverdue(int limit) {
        List<Approval> overdue = approvals.findExpired(Instant.now(), PageRequest.of(0, limit));
        for (Approval approval : overdue) {
            expire(approval);
        }
        if (!overdue.isEmpty()) {
            log.info("Expired {} approval(s) that passed their deadline", overdue.size());
        }
        return overdue.size();
    }

    private void expire(Approval approval) {
        approval.setStatus("expired");
        approval.setDecidedAt(Instant.now());
        approvals.save(approval);
        runs.findById(approval.getRunId()).ifPresent(run -> {
            if (run.isActive()) {
                String reason = "The approval this run needed expired before anybody decided.";
                run.finish("cancelled", reason);
                runs.save(run);
                progress.onRunFinished(run, "cancelled", null, reason);
            }
        });
    }

    /** Cancels open approvals for a run that has been stopped, so the queue stays truthful. */
    @Transactional
    public void cancelForRun(UUID runId) {
        withdrawForRun(runId);
    }

    /**
     * Withdraws a stopped run's pending approvals, and says how many there were.
     *
     * <p>A conditional bulk update rather than loading and saving each row: an approver deciding
     * at the same moment then simply wins or loses, and can never make the stop itself fail with
     * a version conflict.
     */
    @Transactional
    public int withdrawForRun(UUID runId) {
        return approvals.withdrawPending(runId, Instant.now());
    }

    /**
     * Runs an approver approved that are still parked, for the resume sweep: the approver's own
     * resume did not happen, most often because the service restarted in between. Only a run's
     * newest approval counts, so a run parked again on a newer one is never resumed past it.
     */
    @Transactional(readOnly = true)
    public List<RunRef> approvedAwaitingResume(Instant cutoff, int limit) {
        return approvals.findApprovedAwaitingResume(cutoff, PageRequest.of(0, limit)).stream()
                .map(approval -> new RunRef(approval.getOrgId(), approval.getRunId()))
                .distinct()
                .toList();
    }

    /**
     * The payload column is JSONB, but the model's arguments are kept as raw text upstream
     * because models produce malformed JSON often enough that it is an ordinary case (see
     * {@link os.aiworkforce.llm.model.ToolCall}). Valid text is stored as-is; anything else is
     * wrapped so the column always holds a document rather than failing the approval.
     */
    private String toJsonPayload(String argumentsJson) {
        String value = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception malformed) {
            return objectMapper.createObjectNode().put("raw", value).toString();
        }
    }

    /** What the approver sees: enough to judge the action without leaving the queue. */
    public Map<String, Object> describe(Approval approval) {
        return Map.of(
                "id", approval.getId(),
                "tool", approval.getTool() == null ? "" : approval.getTool(),
                "summary", approval.getSummary(),
                "actionClass", approval.getActionClass(),
                "requestedAt", approval.getRequestedAt(),
                "expiresAt", approval.getExpiresAt());
    }
}
