package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * An action waiting for a person.
 *
 * <p>The payload is stored in full so the approver sees exactly what will be sent rather than a
 * paraphrase of it. Approving "send an email to the candidate" without seeing the email is not
 * approval in any useful sense.
 */
@Entity
@Table(name = "approvals")
public class Approval extends OrgScopedEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "action_class", nullable = false)
    private String actionClass;

    @Column(name = "tool")
    private String tool;

    /** The model's own id for the tool call this approval answers, so a resumed run can invoke it. */
    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    /** The tool call's arguments, exactly as the model produced them, as a JSON document. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload = "{}";

    @Column(name = "status", nullable = false)
    private String status = "pending";

    @Column(name = "required_permission", nullable = false)
    private String requiredPermission = "approval:decide";

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    /** Defaults to rejection: an action nobody approved must not happen because everybody was busy. */
    @Column(name = "on_expiry", nullable = false)
    private String onExpiry = "reject";

    @Column(name = "escalated_to")
    private UUID escalatedTo;

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getActionClass() {
        return actionClass;
    }

    public void setActionClass(String actionClass) {
        this.actionClass = actionClass;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public void setRequiredPermission(String requiredPermission) {
        this.requiredPermission = requiredPermission;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(UUID decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public String getOnExpiry() {
        return onExpiry;
    }

    public void setOnExpiry(String onExpiry) {
        this.onExpiry = onExpiry;
    }

    public UUID getEscalatedTo() {
        return escalatedTo;
    }

    public void setEscalatedTo(UUID escalatedTo) {
        this.escalatedTo = escalatedTo;
    }

    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean hasExpired() {
        return isPending() && expiresAt.isBefore(Instant.now());
    }

    /**
     * Records a decision, refusing a second one.
     *
     * <p>Two approvers clicking at once would otherwise both succeed, and the run would act twice
     * on one request. The caller catches this and reports that the decision was already made.
     */
    public void decide(String outcome, UUID decidedBy, String note) {
        if (!isPending()) {
            throw new IllegalStateException("Approval has already been decided: " + status);
        }
        this.status = outcome;
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.decidedAt = Instant.now();
    }
}
