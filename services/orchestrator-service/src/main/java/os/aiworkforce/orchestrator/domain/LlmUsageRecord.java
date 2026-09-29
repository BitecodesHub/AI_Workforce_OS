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
 * One attempt against one model, successful or not.
 *
 * <p>Failures are recorded too, and that is the point. A provider bills for a call that timed out
 * after generating most of an answer, and a spend report that counts only successes drifts from
 * the invoice. The skipped candidates are recorded as well, because "OpenRouter was never tried,
 * it had no credential" is the answer to most questions about why a run was slow.
 */
@Entity
@Table(name = "llm_usage")
public class LlmUsageRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    /** {@code SUCCEEDED}, {@code FAILED} or {@code SKIPPED}. */
    @Column(nullable = false)
    private String outcome;

    @Column
    private String failure;

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "cached_tokens", nullable = false)
    private int cachedTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(nullable = false, precision = 14, scale = 8)
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public void setFailure(String failure) {
        this.failure = failure;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public void setCachedTokens(int cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost == null ? BigDecimal.ZERO : cost;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
