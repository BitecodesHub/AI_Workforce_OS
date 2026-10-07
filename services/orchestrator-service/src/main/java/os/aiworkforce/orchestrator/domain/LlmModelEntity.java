package os.aiworkforce.orchestrator.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One model offered by one provider.
 *
 * <p>Everything the router needs to disqualify a candidate without calling it lives here. That
 * matters commercially as well as technically: models are retired on a few weeks' notice, and a
 * platform that names them in code breaks on a vendor's schedule rather than its own.
 */
@Entity
@Table(name = "llm_models")
@IdClass(LlmModelEntity.Key.class)
public class LlmModelEntity {

    /** Composite key: a model identifier only means anything alongside its provider. */
    public static class Key implements Serializable {
        private String providerId;
        private String modelId;

        public Key() {}

        public Key(String providerId, String modelId) {
            this.providerId = providerId;
            this.modelId = modelId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(providerId, key.providerId) && Objects.equals(modelId, key.modelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(providerId, modelId);
        }
    }

    @Id
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Id
    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "context_window", nullable = false)
    private int contextWindow;

    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens;

    @Column(name = "supports_tools", nullable = false)
    private boolean supportsTools;

    @Column(name = "supports_json_mode", nullable = false)
    private boolean supportsJsonMode;

    @Column(name = "supports_streaming", nullable = false)
    private boolean supportsStreaming = true;

    @Column(name = "supports_vision", nullable = false)
    private boolean supportsVision;

    @Column(name = "input_cost_per_million", nullable = false, precision = 14, scale = 8)
    private BigDecimal inputCostPerMillion = BigDecimal.ZERO;

    @Column(name = "cached_cost_per_million", precision = 14, scale = 8)
    private BigDecimal cachedCostPerMillion;

    @Column(name = "output_cost_per_million", nullable = false, precision = 14, scale = 8)
    private BigDecimal outputCostPerMillion = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Set when a provider answers "no such model".
     *
     * <p>Without it, every subsequent request pays the same doomed round trip, and the operator
     * sees latency rather than the retirement that caused it.
     */
    @Column(name = "unavailable_until")
    private Instant unavailableUntil;

    @Column(name = "unavailable_reason")
    private String unavailableReason;

    /** {@code seed} for a row the migrations wrote, {@code discovered} for one a provider's model list wrote. */
    @Column(nullable = false)
    private String source = "seed";

    /** The provider charges nothing for this model. */
    @Column(nullable = false)
    private boolean free;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    public void markUnavailable(Duration duration, String reason) {
        this.unavailableUntil = Instant.now().plus(duration);
        this.unavailableReason = reason;
    }

    public boolean isCurrentlyUnavailable() {
        return unavailableUntil != null && unavailableUntil.isAfter(Instant.now());
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public boolean isSupportsTools() {
        return supportsTools;
    }

    public boolean isSupportsJsonMode() {
        return supportsJsonMode;
    }

    public boolean isSupportsStreaming() {
        return supportsStreaming;
    }

    public boolean isSupportsVision() {
        return supportsVision;
    }

    public BigDecimal getInputCostPerMillion() {
        return inputCostPerMillion;
    }

    public BigDecimal getCachedCostPerMillion() {
        return cachedCostPerMillion;
    }

    public BigDecimal getOutputCostPerMillion() {
        return outputCostPerMillion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUnavailableUntil() {
        return unavailableUntil;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String getSource() {
        return source;
    }

    public boolean isDiscovered() {
        return "discovered".equals(source);
    }

    public boolean isFree() {
        return free;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }
}
