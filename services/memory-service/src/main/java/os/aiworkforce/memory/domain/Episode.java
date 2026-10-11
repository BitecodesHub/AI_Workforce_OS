// @find: episode, episodic memory, episodes table, what an agent did, observed, decided, importance, run id, append only record, expiry
// @what: Database entity for one append-only record of something an agent observed, decided or did.
// @flow: Written by EpisodicMemory.record; read by MemoryController
package os.aiworkforce.memory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One thing an agent observed, decided or did.
 *
 * <p>Append-only. An episode is a record of something that happened, and editing it would turn
 * the memory into a record of something that did not - which is exactly the failure that makes
 * an audit trail worthless.
 *
 * <p>{@code importance} drives compaction. Old low-importance episodes are folded into a summary;
 * a decision or an approval outcome is kept verbatim for far longer, because those are the ones
 * somebody will need to reconstruct afterwards.
 */
@Entity
@Table(name = "episodes")
public class Episode {

    /**
     * The kinds the {@code episodes_kind_valid} constraint accepts.
     *
     * <p>Checked before saving so an unknown kind is reported to the caller as a bad value rather
     * than reaching the database and coming back as a server fault.
     */
    public static final Set<String> KINDS =
            Set.of("observation", "decision", "handoff", "outcome", "preference", "summary");

    /** Time-ordered identifier generated when the object is created. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    /** Organisation (workspace) that owns the episode; every query is scoped by it. */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Agent the episode is about; null for workspace-wide episodes. */
    @Column(name = "agent_id")
    private UUID agentId;

    /** Run during which it happened, when there was one. */
    @Column(name = "run_id")
    private UUID runId;

    /** Task it relates to, when there is one. */
    @Column(name = "task_id")
    private UUID taskId;

    /** Category; one of {@link #KINDS}. */
    @Column(nullable = false)
    private String kind;

    /** Short text that is full-text searched and recalled to agents. */
    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    /** Structured extra data, stored as JSON; never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> detail = Map.of();

    /** Weight from 1 to 10; higher survives compaction longer. */
    @Column(nullable = false)
    private int importance = 5;

    /** The episodes this summary replaced, so a compacted memory is still traceable. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<UUID> supersedes = List.of();

    /** True once the episode has been folded into a summary; recall skips compacted episodes. */
    @Column(nullable = false)
    private boolean compacted;

    /** When the thing happened, which orders recall and replay. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    /** When the retention purge may delete the episode; null means it is kept. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** When the row was written. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Identifier of the actor that recorded the episode, when known. */
    @Column(name = "created_by")
    private String createdBy;

    /**
     * Builds a new episode for an organisation with the given kind, summary and importance. The identifier
     * and the occurred and created dates start now; run, task, detail and expiry are set afterwards.
     * Nothing is persisted until the repository saves it.
     */
    public static Episode of(UUID orgId, UUID agentId, String kind, String summary, int importance) {
        Episode episode = new Episode();
        episode.orgId = orgId;
        episode.agentId = agentId;
        episode.kind = kind;
        episode.summary = summary;
        episode.importance = importance;
        return episode;
    }

    /**
     * Accessors and mutators below simply expose the fields documented above; setters do not
     * validate.
     */
    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail == null ? Map.of() : detail;
    }

    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public List<UUID> getSupersedes() {
        return supersedes == null ? List.of() : supersedes;
    }

    public void setSupersedes(List<UUID> supersedes) {
        this.supersedes = supersedes;
    }

    public boolean isCompacted() {
        return compacted;
    }

    public void setCompacted(boolean compacted) {
        this.compacted = compacted;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
