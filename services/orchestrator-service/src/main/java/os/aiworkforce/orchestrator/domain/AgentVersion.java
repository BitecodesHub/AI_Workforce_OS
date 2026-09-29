package os.aiworkforce.orchestrator.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * A frozen snapshot of how an agent was configured.
 *
 * <p>The system prompt lives here, as a row. Nothing in the codebase contains an agent's
 * instructions, so changing how the HR agent behaves is an edit with a visible diff rather than a
 * deployment - and, just as importantly, the version a run used can still be read afterwards.
 *
 * <p>A version is sealed the first time a run touches it. An unsealed version can be edited
 * freely; a sealed one cannot be edited at all, because a trace that refers to a prompt somebody
 * has since rewritten is a record of something that never happened.
 */
@Entity
@Table(name = "agent_versions")
public class AgentVersion {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(nullable = false, columnDefinition = "text")
    private String goals = "";

    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    /**
     * How many model turns one run may take before it is stopped.
     *
     * <p>An agent that calls a tool, reads the result and calls it again can loop indefinitely
     * when the result never satisfies it. The cap turns an unbounded spend into a visible failure.
     */
    @Column(name = "max_steps", nullable = false)
    private int maxSteps = 12;

    @Column(nullable = false)
    private boolean sealed;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    /** Called when a run first uses this version. Idempotent, because many runs will use it. */
    public void seal() {
        if (!sealed) {
            sealed = true;
            sealedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getGoals() {
        return goals;
    }

    public void setGoals(String goals) {
        this.goals = goals;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public boolean isSealed() {
        return sealed;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
