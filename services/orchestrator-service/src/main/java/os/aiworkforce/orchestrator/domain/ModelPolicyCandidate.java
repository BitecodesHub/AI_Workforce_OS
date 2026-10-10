// @find: model policy candidate, routing chain, model fallback order, priority, candidate model, weight, temperature override, model_policy_candidates, ModelPolicyCandidate
// @what: Entity for one model at a fixed position in a routing chain.
// @flow: Owned by ModelPolicyEntity; read by the model router.
// @find: model policy candidate, routing chain, model fallback order, priority, candidate model, weight, temperature override, model_policy_candidates, ModelPolicyCandidate
// @what: Entity for one model at a fixed position in a routing chain.
// @flow: Owned by ModelPolicyEntity; read by the model router.
package os.aiworkforce.orchestrator.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One model in a routing chain, at a fixed position. */
@Entity
@Table(name = "model_policy_candidates")
@IdClass(ModelPolicyCandidate.Key.class)
public class ModelPolicyCandidate {

    @Embeddable
    public static class Key implements Serializable {
        private UUID policyId;
        private Integer position;

        public Key() {}

        public Key(UUID policyId, Integer position) {
            this.policyId = policyId;
            this.position = position;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(policyId, key.policyId) && Objects.equals(position, key.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(policyId, position);
        }
    }

    @Id
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    /** The position is the priority. Reordering the chain is an update, not a migration. */
    @Id
    @Column(nullable = false)
    private Integer position;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(nullable = false)
    private int weight;

    // @find: create policy candidate, add model to routing chain
    // @find: create policy candidate, add model to routing chain
    public static ModelPolicyCandidate of(UUID policyId, int position, String providerId, String modelId) {
        ModelPolicyCandidate candidate = new ModelPolicyCandidate();
        candidate.policyId = policyId;
        candidate.position = position;
        candidate.providerId = providerId;
        candidate.modelId = modelId;
        return candidate;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
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

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
