// @find: connection entity, connections table, connector connection, connected status, credential ref, encrypted token, granted scopes, requested scopes, account label, reconnect required, token expiry, last checked, connect disconnect, connectors, integrations, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook
// @what: Database entity for one workspace connection to one connector, with status, encrypted credential reference, scopes and check results.
// @flow: Stored through Connections repository; changed by ConnectorService and OAuthService
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

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

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

    // @find: mark needs reconnect, expired sign in
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

    // @find: connect with token, store pasted token, mark connected
    /**
     * Stores a token that its provider has just accepted.
     *
     * @param encryptedCredential the token, already encrypted for this workspace
     */
    public void connectWithToken(String encryptedCredential, String accountLabel, UUID connectedBy) {
        this.credentialRef = encryptedCredential;
        this.status = "connected";
        this.sandbox = false;
        this.accountLabel = accountLabel;
        this.connectedBy = connectedBy;
        this.connectedAt = Instant.now();
        this.lastCheckedAt = this.connectedAt;
        this.lastError = null;
        this.reconnectRequired = false;
        this.tokenExpiresAt = null;
    }

    // @find: connect with oauth, store sign-in tokens
    /**
     * Stores the tokens of a completed sign-in.
     *
     * @param encryptedCredential the access and refresh tokens, already encrypted for this workspace
     * @param requested the permissions that were asked for and are checked afterwards
     * @param granted what the provider says was approved
     */
    public void connectWithOAuth(
            String encryptedCredential,
            String accountLabel,
            UUID connectedBy,
            List<String> requested,
            List<String> granted,
            Instant expiresAt) {
        connectWithToken(encryptedCredential, accountLabel, connectedBy);
        this.requestedScopes = requested;
        this.grantedScopes = granted;
        this.tokenExpiresAt = expiresAt;
        this.lastRefreshedAt = this.connectedAt;
    }

    // @find: record refreshed oauth token
    /** Stores the tokens after a successful refresh. The account and the consent are unchanged. */
    public void recordRefresh(String encryptedCredential, Instant expiresAt) {
        this.credentialRef = encryptedCredential;
        this.tokenExpiresAt = expiresAt;
        this.lastRefreshedAt = Instant.now();
        this.lastError = null;
        this.reconnectRequired = false;
        if (!"connected".equals(status)) {
            this.status = "connected";
        }
    }

    // @find: record connection test result
    /**
     * Records the result of checking the stored token again.
     *
     * <p>A failed check marks the connection as needing attention but keeps the token, so a
     * provider that was briefly unreachable does not cost the administrator a reconnect, and
     * agents keep reaching the real account - where a failure is reported - rather than quietly
     * falling back to practice data.
     */
    public void recordCheck(boolean ok, String error) {
        this.lastCheckedAt = Instant.now();
        this.lastError = ok ? null : error;
        if (!sandbox) {
            this.status = ok ? "connected" : "error";
        }
    }

    /** Notes that a check ran, without changing the state a refresh failure left behind. */
    public void setLastCheckedAtNow() {
        this.lastCheckedAt = Instant.now();
    }

    // @find: disconnect connector, remove credential
    /** Forgets the token and returns the connector to practice data. */
    public void disconnect() {
        this.credentialRef = null;
        this.status = "sandbox";
        this.sandbox = true;
        this.accountLabel = null;
        this.connectedBy = null;
        this.connectedAt = null;
        this.tokenExpiresAt = null;
        this.lastRefreshedAt = null;
        this.lastCheckedAt = null;
        this.lastError = null;
        this.reconnectRequired = false;
        this.grantedScopes = List.of();
    }

    /** Whether a live token is stored that agents should use. */
    public boolean hasLiveCredential() {
        return !sandbox
                && credentialRef != null
                && !reconnectRequired
                && ("connected".equals(status) || "error".equals(status));
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

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }
}
