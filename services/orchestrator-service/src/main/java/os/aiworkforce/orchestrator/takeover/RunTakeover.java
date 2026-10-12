// @find: run takeover entity, person takes over the browser, take control, hand back, who has control, run_takeovers table, RunTakeover
// @what: JPA entity for a person's control of one run's web browser: who holds it, since when, what they did (never typed text), how control came back and the steps given back to the run.
// @flow: Written by TakeoverService; read by AgentRunner (through TakeoverService.awaitHandBack) and RunTakeoverController
package os.aiworkforce.orchestrator.takeover;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run's takeover. The run id is the key, so there is never more than one row, and so never more
 * than one person holding a run's browser; a second takeover of the same run reuses the row.
 *
 * <p>The version column makes two people pressing "Take over" at once, or a hand back racing the
 * automatic return of control, fail cleanly for one of them instead of both winning.
 */
@Entity
@Table(name = "run_takeovers")
public class RunTakeover {

    /** A person asked for control; the agent is finishing the step it is on and has not stopped yet. */
    public static final String STARTING = "starting";
    /** A person holds control; the agent waits. */
    public static final String ACTIVE = "active";
    /** The person handed control back. */
    public static final String RELEASED = "released";
    /** Nobody handed it back within the ceiling, so control returned by itself. */
    public static final String EXPIRED = "expired";
    /** The run stopped, finished or parked for a person while someone held or was getting its browser. */
    public static final String ENDED = "ended";

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private String releasedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> actions = new LinkedHashMap<>();

    @Column(columnDefinition = "text")
    private String note;

    @Column(nullable = false)
    private String status = STARTING;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** Steps given back to the run because a takeover made a turn pointless; kept across parks and takeovers. */
    @Column(name = "steps_given_back", nullable = false)
    private int stepsGivenBack;

    /** Null until first saved, which is how Spring Data tells a new row from an existing one. */
    @Version
    private Long version;

    /**
     * A new request by {@code takenBy} at {@code at} for control of a run's browser. It starts as
     * {@link #STARTING}: the person gets control once the agent's loop has stopped ({@link #activate}).
     */
    public static RunTakeover start(UUID runId, UUID orgId, String takenBy, Instant at) {
        RunTakeover takeover = new RunTakeover();
        takeover.runId = runId;
        takeover.orgId = orgId;
        takeover.takenBy = takenBy;
        takeover.takenAt = at;
        return takeover;
    }

    /**
     * Gives the row to a new holder, as a new request ({@link #STARTING}). What an earlier holder
     * did that the agent has not been told yet (their actions and note) is kept, so it reaches the
     * agent with this holder's; anything already told is cleared. The steps given back stay.
     */
    public void retake(String person, Instant at) {
        if (!isPending()) {
            actions = new LinkedHashMap<>();
            note = null;
        }
        takenBy = person;
        takenAt = at;
        status = STARTING;
        releasedAt = null;
        releasedBy = null;
        deliveredAt = null;
    }

    /** The agent's loop has stopped: the person has control from {@code at}, which the ceiling counts from. */
    public void activate(Instant at) {
        status = ACTIVE;
        takenAt = at;
    }

    /** Whether a person holds control right now (ignoring the ceiling, which the service applies). */
    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** Whether a person asked for control and the agent has not stopped for them yet. */
    public boolean isStarting() {
        return STARTING.equals(status);
    }

    /** Whether a person holds control or is about to: {@link #STARTING} or {@link #ACTIVE}. */
    public boolean isHeld() {
        return isActive() || isStarting();
    }

    /** Control came back (handed back or by itself) and the agent has not been told what happened yet. */
    public boolean isPending() {
        return (RELEASED.equals(status) || EXPIRED.equals(status)) && deliveredAt == null;
    }

    /** Ends the hold with {@code newStatus}, as {@code by} (null for the platform), at {@code at}. */
    public void close(String newStatus, String by, Instant at) {
        status = newStatus;
        releasedBy = by;
        releasedAt = at;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getTakenBy() {
        return takenBy;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public Map<String, Object> getActions() {
        return actions == null ? Map.of() : actions;
    }

    /** Replaces the summary of what the person did; never holds typed text. */
    public void setActions(Map<String, Object> actions) {
        this.actions = actions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(actions);
    }

    public String getNote() {
        return note;
    }

    /** Sets the person's words for the agent, or clears them once the agent has been told. */
    public void setNote(String note) {
        this.note = note;
    }

    public String getStatus() {
        return status;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** Marks the agent as told what happened, at {@code at}. */
    public void setDeliveredAt(Instant at) {
        this.deliveredAt = at;
    }

    public int getStepsGivenBack() {
        return stepsGivenBack;
    }

    /** One more step given back to the run, because a takeover made a turn pointless. */
    public void giveStepBack() {
        stepsGivenBack++;
    }

    public Long getVersion() {
        return version;
    }
}
