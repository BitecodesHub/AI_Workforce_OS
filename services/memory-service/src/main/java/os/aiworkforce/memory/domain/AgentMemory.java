// @find: agent memory, agent notes, memories, note, remember, pinned memory, agent_memories table, editable note, memory kind, source
// @what: Database entity for one note an AI employee keeps, which people can edit, pin or delete.
// @flow: Used by AgentMemoryService; table from V2__agent_memories.sql
package os.aiworkforce.memory.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One note an AI employee keeps, or a person wrote for it.
 *
 * <p>Unlike an {@link Episode}, a note is meant to be changed: people correct it and delete it from
 * the agent's page, and an agent that learns something better replaces it. It belongs to exactly
 * one agent and is read back only to that agent.
 */
@Entity
@Table(name = "agent_memories")
public class AgentMemory {

    /** Allowed note kinds. */
    public static final Set<String> KINDS = Set.of("fact", "preference", "instruction", "note");
    /**
     * Allowed sources: agent when it kept the note while working, person when somebody wrote it.
     */
    public static final Set<String> SOURCES = Set.of("agent", "person");
    /** Longest note text in characters; one fact per note keeps recall short. */
    public static final int MAX_CONTENT = 1_000;

    /** Time-ordered identifier generated when the object is created. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    /** Organisation (workspace) that owns the note; fixed at creation. */
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** The one agent the note belongs to; fixed at creation. */
    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    /** Kind of note; one of {@link #KINDS}. */
    @Column(nullable = false)
    private String kind = "fact";

    /** The note text, at most {@link #MAX_CONTENT} characters and never a secret. */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Who wrote it; one of {@link #SOURCES}. */
    @Column(nullable = false)
    private String source = "agent";

    /** Run in which an agent kept the note, when known. */
    @Column(name = "run_id")
    private UUID runId;

    /** How many times the note has been recalled to an agent. */
    @Column(name = "recall_count", nullable = false)
    private int recallCount;

    /** When the note was last recalled; null if never. */
    @Column(name = "last_recalled_at")
    private Instant lastRecalledAt;

    /** When the note was first written. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Who first wrote the note. */
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    /** When the note was last changed or re-written; used for newest-first ordering. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Who last changed the note. */
    @Column(name = "updated_by")
    private String updatedBy;

    /** Always recalled, and listed first; only a person pins a note. */
    @Column(nullable = false)
    private boolean pinned;

    /**
     * When the note was pinned; null when not pinned. Pinned notes are recalled most recently
     * pinned first.
     */
    @Column(name = "pinned_at")
    private Instant pinnedAt;

    /**
     * Builds a new note. The same person or agent identifier is stored as creator and last editor, the
     * dates start now and nothing is persisted until the repository saves it.
     */
    public static AgentMemory of(UUID orgId, UUID agentId, String kind, String content, String source, String by) {
        AgentMemory memory = new AgentMemory();
        memory.orgId = orgId;
        memory.agentId = agentId;
        memory.kind = kind;
        memory.content = content;
        memory.source = source;
        memory.createdBy = by;
        memory.updatedBy = by;
        return memory;
    }

    /**
     * Replaces the kind and text, and records who changed it and when. Callers validate both
     * values first.
     */
    public void change(String kind, String content, String by) {
        this.kind = kind;
        this.content = content;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    /** Pins or unpins the note; neither counts as changing what it says. */
    public void pin(boolean pin) {
        this.pinned = pin;
        this.pinnedAt = pin ? Instant.now() : null;
    }

    /**
     * Accessors below expose the fields documented above; state changes go through {@link
     * #change}, {@link #pin}, {@link #touch} and {@link #recalled}.
     */
    public boolean isPinned() {
        return pinned;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }

    /**
     * Moves the last-changed date to now without changing the text; used when the same note is
     * written again.
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** Counts one recall and records the time; called for every note an agent is reminded of. */
    public void recalled() {
        this.recallCount++;
        this.lastRecalledAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public int getRecallCount() {
        return recallCount;
    }

    public Instant getLastRecalledAt() {
        return lastRecalledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
