package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One step towards a goal, assigned to one agent.
 *
 * <p>Tasks form a directed acyclic graph through {@code dependsOn}. A task becomes ready only
 * when every task it depends on has completed, which is what lets research finish before the
 * draft that quotes it begins.
 */
@Entity
@Table(name = "tasks")
public class Task extends OrgScopedEntity {

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "instruction", nullable = false, columnDefinition = "text")
    private String instruction;

    @Column(name = "status", nullable = false)
    private String status = "pending";

    @Column(name = "position", nullable = false)
    private int position = 0;

    /** Incremented on each try, so a task that keeps failing stops rather than looping. */
    @Column(name = "attempt", nullable = false)
    private int attempt = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 2;

    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getGoalId() {
        return goalId;
    }

    public void setGoalId(UUID goalId) {
        this.goalId = goalId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
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

    public boolean canRetry() {
        return attempt < maxAttempts;
    }

    public boolean isTerminal() {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "skipped".equals(status);
    }
}
