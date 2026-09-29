package os.aiworkforce.orchestrator.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One thing that happened during a run.
 *
 * <p>The trace is the product, not a debugging aid. A person asked to approve an email, or to
 * trust an answer, needs to see which model produced it, which tools ran, what they returned, and
 * which earlier attempts failed. A run that cannot be explained afterwards is automation nobody
 * should authorise.
 *
 * <p>{@code detail} is JSON rather than columns because the shape differs completely by kind, and
 * modelling nine variants as nullable columns produces a table that is mostly empty.
 */
@Entity
@Table(name = "run_steps")
public class RunStep {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> detail = Map.of();

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(nullable = false, precision = 14, scale = 8)
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static RunStep of(UUID orgId, UUID runId, int position, String kind, Map<String, Object> detail) {
        RunStep step = new RunStep();
        step.orgId = orgId;
        step.runId = runId;
        step.position = position;
        step.kind = kind;
        step.detail = detail == null ? Map.of() : detail;
        return step;
    }

    public RunStep withModel(
            String providerId,
            String modelId,
            int promptTokens,
            int completionTokens,
            BigDecimal cost,
            long durationMs) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cost = cost == null ? BigDecimal.ZERO : cost;
        this.durationMs = durationMs;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getRunId() {
        return runId;
    }

    public int getPosition() {
        return position;
    }

    public String getKind() {
        return kind;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
