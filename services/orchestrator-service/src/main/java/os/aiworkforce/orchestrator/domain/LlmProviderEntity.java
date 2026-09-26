package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * What the last call learned about the credential.
     *
     * <p>Written back by the router when a provider rejects a key, so the console can say "the
     * key was refused" rather than leaving an operator to infer it from latency.
     */
    @Column(name = "credential_status", nullable = false)
    private String credentialStatus = "unknown";

    @Column(name = "credential_checked_at")
    private Instant credentialCheckedAt;

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public String getCredentialStatus() {
        return credentialStatus;
    }

    public void markCredentialRejected() {
        this.credentialStatus = "rejected";
        this.credentialCheckedAt = Instant.now();
    }

    public void markCredentialValid() {
        this.credentialStatus = "valid";
        this.credentialCheckedAt = Instant.now();
    }

    public Instant getCredentialCheckedAt() {
        return credentialCheckedAt;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public long getVersion() {
        return version;
    }
}
