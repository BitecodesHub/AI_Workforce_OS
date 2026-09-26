package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One agent's attempt at one task.
 *
 * <p>The lease is what makes a crash survivable. A worker renews {@code leaseExpiresAt} while it
 * works; if the process dies, the lease lapses and a reaper marks the run abandoned rather than
 * leaving it showing as running forever. Without it, a restart during a deployment leaves runs
 * that never finish and never fail.
 */
@Entity
@Table(name = "runs")
public class Run extends OrgScopedEntity {

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Pinned, so the trace can be read against the prompt that actually produced it. */
    @Column(name = "agent_version_id", nullable = false)
    private UUID agentVersionId;

    @Column(name = "status", nullable = false)
    private String status = "running";

    @Column(name = "trigger", nullable = false)
    private String trigger = "task";

    @Column(name = "step_count", nullable = false)
    private int stepCount = 0;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "total_prompt_tokens", nullable = false)
    private int totalPromptTokens = 0;

    @Column(name = "total_completion_tokens", nullable = false)
    private int totalCompletionTokens = 0;

    @Column(name = "total_cost", nullable = false, precision = 14, scale = 8)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

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

    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    public void setAgentVersionId(UUID agentVersionId) {
        this.agentVersionId = agentVersionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public int getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public void setTotalPromptTokens(int totalPromptTokens) {
        this.totalPromptTokens = totalPromptTokens;
    }

    public int getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    public void setTotalCompletionTokens(int totalCompletionTokens) {
        this.totalCompletionTokens = totalCompletionTokens;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public boolean isActive() {
        return "running".equals(status) || "waiting_approval".equals(status);
    }

    /** Extends the lease. Called after every step, so a long run is not reaped mid-flight. */
    public void renewLease(String workerId, java.time.Duration duration) {
        this.workerId = workerId;
        this.leaseExpiresAt = Instant.now().plus(duration);
    }

    public void finish(String status, String failureReason) {
        this.status = status;
        this.failureReason = failureReason;
        this.completedAt = Instant.now();
        this.leaseExpiresAt = null;
    }
}
