// @find: workspace provider setting, provider enabled for workspace, credential status, key rejected, key last checked, per workspace provider, workspace_provider_settings, WorkspaceProviderSetting, Providers page
// @what: Entity for one workspace's own state for one provider (switched off, credential status).
// @flow: Written by upserts in WorkspaceProviderSettings; combined with platform values by the provider registry.
// @find: workspace provider setting, provider enabled for workspace, credential status, key rejected, key last checked, per workspace provider, workspace_provider_settings, WorkspaceProviderSetting, Providers page
// @what: Entity for one workspace's own state for one provider (switched off, credential status).
// @flow: Written by upserts in WorkspaceProviderSettings; combined with platform values by the provider registry.
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
 * One workspace's own state for one provider: whether it has switched the provider off, and what
 * its own key last did.
 *
 * <p>{@link LlmProviderEntity} is the platform catalogue that every workspace reads. Writing a
 * workspace's choice onto that shared row is what once let one workspace switch OpenRouter off for
 * all of them, and show every workspace a "key refused" that belonged to one. This row is where
 * those facts live instead, keyed by the workspace.
 *
 * <p>Every write goes through the native upserts on {@code WorkspaceProviderSettings}, so a double
 * click or two failures racing each other never trip the primary key. The entity is only read.
 */
@Entity
@Table(name = "workspace_provider_settings")
@IdClass(WorkspaceProviderSetting.Key.class)
public class WorkspaceProviderSetting {

    /** Composite key: the workspace and the provider. */
    public static class Key implements Serializable {
        private UUID orgId;
        private String providerId;

        public Key() {}

        public Key(UUID orgId, String providerId) {
            this.orgId = orgId;
            this.providerId = providerId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(orgId, key.orgId) && Objects.equals(providerId, key.providerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, providerId);
        }
    }

    /** The credential states a workspace row can hold. Absent means nothing has been learned yet. */
    public static final String UNKNOWN = "unknown";

    public static final String VALID = "valid";
    public static final String REJECTED = "rejected";

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Id
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    /** Null inherits the platform value; see {@code JpaProviderRegistry} for how they combine. */
    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "credential_status")
    private String credentialStatus;

    @Column(name = "credential_checked_at")
    private Instant credentialCheckedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

    protected WorkspaceProviderSetting() {}

    public WorkspaceProviderSetting(UUID orgId, String providerId) {
        this.orgId = Objects.requireNonNull(orgId, "orgId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getProviderId() {
        return providerId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getCredentialStatus() {
        return credentialStatus;
    }

    public void setCredentialStatus(String credentialStatus) {
        this.credentialStatus = credentialStatus;
    }

    public Instant getCredentialCheckedAt() {
        return credentialCheckedAt;
    }

    public void setCredentialCheckedAt(Instant credentialCheckedAt) {
        this.credentialCheckedAt = credentialCheckedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
