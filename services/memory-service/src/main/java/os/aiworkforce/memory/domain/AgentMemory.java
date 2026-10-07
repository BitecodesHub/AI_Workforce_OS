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

    public static final Set<String> KINDS = Set.of("fact", "preference", "instruction", "note");
    public static final Set<String> SOURCES = Set.of("agent", "person");
    public static final int MAX_CONTENT = 1_000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(nullable = false)
    private String kind = "fact";

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private String source = "agent";

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "recall_count", nullable = false)
    private int recallCount;

    @Column(name = "last_recalled_at")
    private Instant lastRecalledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

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

    public void change(String kind, String content, String by) {
        this.kind = kind;
        this.content = content;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

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
