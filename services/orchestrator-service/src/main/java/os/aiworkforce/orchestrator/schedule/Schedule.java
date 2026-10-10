// @find: schedule entity, recurring work, schedule record, name, instruction, cron, run at, timezone, enabled, paused, overlap policy, next run, last run, consecutive failures, owner, requested by, schedules table
// @what: JPA entity for one schedule (recurring or one-off work given to an agent).
// @flow: Stored in the schedules table; handled by ScheduleService and Schedules
package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A standing instruction to create a goal, later - once, or over and over.
 *
 * <p>Firing is what {@link ScheduleService} does; this row is only the state that decides when
 * that next happens and what its last few attempts looked like. {@code nextRunAt} is kept as a
 * plain column, recomputed whenever the schedule changes, rather than derived from {@code cron}
 * on every read: the sweep needs one indexed comparison across every workspace, not a cron
 * expression evaluated row by row.
 */
@Entity
@Table(name = "schedules")
public class Schedule extends OrgScopedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false, columnDefinition = "text")
    private String instruction;

    /** {@code recurring} or {@code once}. */
    @Column(nullable = false)
    private String kind;

    /** A Spring six-field cron expression, set when {@code kind} is {@code recurring}. */
    @Column
    private String cron;

    /** The single instant to run at, set when {@code kind} is {@code once}. */
    @Column(name = "run_at")
    private Instant runAt;

    /** The workspace timezone this schedule was created in, so a later sweep need not ask again. */
    @Column(nullable = false)
    private String timezone;

    /** The schedule phrase, echoed back in plain words. */
    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    /** {@code skip} or {@code queue} - what happens when this schedule is due and its last goal is still active. */
    @Column(name = "overlap_policy", nullable = false)
    private String overlapPolicy = "skip";

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_goal_id")
    private UUID lastGoalId;

    /** The last goal's own status word (planning, running, completed, failed, cancelled…). */
    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "paused_reason")
    private String pausedReason;

    /**
     * Who this schedule fires as - the person it was created for, read from the request context
     * when it was created. Distinct from the inherited, audit {@code createdBy}: nothing in this
     * service populates that one, the same as every other table here, since no {@code
     * AuditorAware} is registered.
     */
    @Column(name = "requested_by")
    private UUID requestedBy;

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public void setRunAt(Instant runAt) {
        this.runAt = runAt;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOverlapPolicy() {
        return overlapPolicy;
    }

    public void setOverlapPolicy(String overlapPolicy) {
        this.overlapPolicy = overlapPolicy;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public UUID getLastGoalId() {
        return lastGoalId;
    }

    public void setLastGoalId(UUID lastGoalId) {
        this.lastGoalId = lastGoalId;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getPausedReason() {
        return pausedReason;
    }

    public void setPausedReason(String pausedReason) {
        this.pausedReason = pausedReason;
    }

    public boolean isRecurring() {
        return "recurring".equals(kind);
    }
}
