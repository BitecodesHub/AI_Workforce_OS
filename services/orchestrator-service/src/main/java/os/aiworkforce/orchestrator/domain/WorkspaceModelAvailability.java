package os.aiworkforce.orchestrator.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A model one workspace has set aside for a while: its own provider account cannot pay for it
 * (out of credit for this model, or out of quota altogether), or the provider told this
 * workspace the model does not exist.
 *
 * <p>Those failures say something about one workspace's account, so they must not reach
 * {@link LlmModelEntity#getUnavailableUntil()}, which every workspace reads. A "does not exist"
 * reaches it only once a second workspace reports the same model; see {@code JpaProviderRegistry}.
 *
 * <p>Written only through the native upsert on {@code WorkspaceModelAvailabilities}.
 */
@Entity
@Table(name = "workspace_model_availability")
@IdClass(WorkspaceModelAvailability.Key.class)
public class WorkspaceModelAvailability {

    /** Composite key: the workspace, the provider and the model. */
    public static class Key implements Serializable {
        private UUID orgId;
        private String providerId;
        private String modelId;

        public Key() {}

        public Key(UUID orgId, String providerId, String modelId) {
            this.orgId = orgId;
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
            return Objects.equals(orgId, key.orgId)
                    && Objects.equals(providerId, key.providerId)
                    && Objects.equals(modelId, key.modelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, providerId, modelId);
        }
    }

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Id
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Id
    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "unavailable_until", nullable = false)
    private Instant unavailableUntil;

    /** The {@code ProviderFailure} name that set it aside. */
    @Column(name = "cause", nullable = false)
    private String cause;

    @Column(name = "reason")
    private String reason;

    protected WorkspaceModelAvailability() {}

    public WorkspaceModelAvailability(
            UUID orgId, String providerId, String modelId, Instant unavailableUntil, String cause, String reason) {
        this.orgId = Objects.requireNonNull(orgId, "orgId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.unavailableUntil = Objects.requireNonNull(unavailableUntil, "unavailableUntil");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.reason = reason;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public Instant getUnavailableUntil() {
        return unavailableUntil;
    }

    public String getCause() {
        return cause;
    }

    public String getReason() {
        return reason;
    }
}
