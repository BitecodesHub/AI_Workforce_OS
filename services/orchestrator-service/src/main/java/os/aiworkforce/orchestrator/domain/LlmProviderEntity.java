package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configured model provider.
 *
 * <p>This row is the whole reason the platform is not tied to a vendor. Adding OpenRouter, a
 * self-hosted endpoint, or a provider that does not exist yet is an insert plus a credential:
 * {@code kind} picks the adapter, {@code baseUrl} points it somewhere, and the router takes it
 * from there.
 */
@Entity
@Table(name = "llm_providers")
public class LlmProviderEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Which adapter speaks to it. Four providers share {@code OPENAI_COMPATIBLE}. */
    @Column(nullable = false)
    private String kind;

    @Column(name = "base_url", nullable = false)
    private String baseUrl = "";

    /** A pointer into the credential store, never the credential itself. */
    @Column(name = "credential_ref")
    private String credentialRef;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Whether a workspace that has made no choice of its own has this provider on. Only means
     * something on a platform-wide row; see V10 for how it and {@link #enabled} divide the work.
     */
    @Column(name = "workspace_default_enabled", nullable = false)
    private boolean workspaceDefaultEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_headers", nullable = false)
    private Map<String, String> defaultHeaders = Map.of();

    /** Ordered. Bedrock walks this list before the router is told the provider failed. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> regions = List.of();

    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @Column(name = "max_concurrent")
    private Integer maxConcurrent;

    @Column(nullable = false)
    private int priority;

    /*
     * The table still carries credential_status and credential_checked_at, but they are not mapped.
     * Keys are stored per workspace, so whether one works is a fact about one workspace: it lives in
     * WorkspaceProviderSetting. Written here, one workspace's refused key read as refused for all.
     */

    /** Null for a platform-wide provider that every workspace inherits. */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    /**
     * On a platform-wide row, whether the provider is offered to workspaces at all: when it is
     * not, no workspace can have it on. On a workspace's own row, whether that workspace has it
     * on. Either way a workspace's effective state is worked out in {@code JpaProviderRegistry}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Only for a row the calling workspace owns; a platform-wide row is never written by a workspace. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** On a platform-wide row: on for a workspace that has not turned it on or off itself. */
    public boolean isWorkspaceDefaultEnabled() {
        return workspaceDefaultEnabled;
    }

    public void setWorkspaceDefaultEnabled(boolean workspaceDefaultEnabled) {
        this.workspaceDefaultEnabled = workspaceDefaultEnabled;
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders == null ? Map.of() : defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public List<String> getRegions() {
        return regions == null ? List.of() : regions;
    }

    public void setRegions(List<String> regions) {
        this.regions = regions;
    }

    public Integer getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public Integer getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getPriority() {
        return priority;
    }

    public UUID getOrgId() {
        return orgId;
    }

    /**
     * Whether a workspace may see this provider at all: a platform-wide row, or one the workspace
     * added for itself. A provider scoped to another workspace must be invisible, not merely
     * unusable, so every lookup by id goes through this.
     */
    public boolean isVisibleTo(UUID callerOrgId) {
        return orgId == null || orgId.equals(callerOrgId);
    }

    /** True for a platform-wide row, which no workspace action may change. */
    public boolean isPlatformWide() {
        return orgId == null;
    }

    public long getVersion() {
        return version;
    }
}
