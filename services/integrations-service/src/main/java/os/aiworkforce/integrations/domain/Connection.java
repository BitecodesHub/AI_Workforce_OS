package os.aiworkforce.integrations.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A workspace's connection to one tool server.
 *
 * <p>{@code grantedScopes} is deliberately separate from {@code requestedScopes}. A person can
 * decline an individual permission at the provider's consent screen and the connection still
 * succeeds, so an agent granted a tool needing that scope would fail at call time with an error
 * nobody could interpret. Recording both lets the console say which permission is missing.
 */
@Entity
@Table(name = "connections")
public class Connection extends OrgScopedEntity {

    @Column(nullable = false)
    private String server;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String status = "disconnected";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "granted_scopes", nullable = false)
    private List<String> grantedScopes = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "requested_scopes", nullable = false)
    private List<String> requestedScopes = List.of();

    @Column(name = "credential_ref")
    private String credentialRef;

    /** Which account is connected, so a person can tell two Gmail connections apart. */
    @Column(name = "account_label")
    private String accountLabel;

    @Column(name = "connected_by")
    private UUID connectedBy;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "reconnect_required", nullable = false)
    private boolean reconnectRequired;

    @Column(nullable = false)
    private boolean sandbox = true;

    public boolean isUsable() {
        return !reconnectRequired && ("connected".equals(status) || "sandbox".equals(status));
    }

    /**
     * Whether the token should be refreshed now.
     *
     * <p>Ahead of expiry, not at it. A token that lapses between the check and the call fails a
     * person's action rather than a background job, and the failure looks like the integration
     * being broken.
     */
    public boolean needsRefresh(Duration margin) {
        return tokenExpiresAt != null && tokenExpiresAt.minus(margin).isBefore(Instant.now());
    }

    /** A refresh that failed needs consent again, which is a different state from never connected. */
    public void requireReconnect(String reason) {
        this.reconnectRequired = true;
        this.status = "error";
        this.lastError = reason;
    }

    public void markConnected(List<String> granted, String accountLabel, Instant expiresAt) {
        this.status = "connected";
        this.sandbox = false;
        this.grantedScopes = granted;
        this.accountLabel = accountLabel;
        this.tokenExpiresAt = expiresAt;
        this.connectedAt = Instant.now();
        this.reconnectRequired = false;
        this.lastError = null;
    }

    public List<String> missingScopes() {
        return requestedScopes.stream()
                .filter(scope -> !grantedScopes.contains(scope))
                .toList();
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getGrantedScopes() {
        return grantedScopes == null ? List.of() : grantedScopes;
    }

    public void setGrantedScopes(List<String> grantedScopes) {
        this.grantedScopes = grantedScopes;
    }

    public List<String> getRequestedScopes() {
        return requestedScopes == null ? List.of() : requestedScopes;
    }

    public void setRequestedScopes(List<String> requestedScopes) {
        this.requestedScopes = requestedScopes;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(Instant lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isReconnectRequired() {
        return reconnectRequired;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    public void setConnectedBy(UUID connectedBy) {
        this.connectedBy = connectedBy;
    }

    public UUID getConnectedBy() {
        return connectedBy;
    }
}
