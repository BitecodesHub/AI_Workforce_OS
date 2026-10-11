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
 *
 * <p>{@code status} is one of {@code disconnected} (the initial value), {@code sandbox} (practice
 * data after a disconnect), {@code connected} or {@code error}. The state methods below change
 * several fields together so that callers cannot leave a half-updated row. Saved through the
 * {@code Connections} repository by {@code ConnectorService} (pasted tokens, test, disconnect) and
 * {@code OAuthService} (sign-in, refresh, reconnect). The accessors at the end are plain getters
 * and setters for the columns; {@code credentialRef} is the encrypted credential and is never
 * returned by an endpoint.
 */
@Entity
@Table(name = "connections")
public class Connection extends OrgScopedEntity {

    /** The connector key, for example {@code gmail}. Unique per workspace. */
    @Column(nullable = false)
    private String server;

    /** The connector's name as shown in the console. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Lifecycle state: {@code disconnected}, {@code sandbox}, {@code connected} or {@code error}. */
    @Column(nullable = false)
    private String status = "disconnected";

    /** Permissions the provider reports as approved at consent. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "granted_scopes", nullable = false)
    private List<String> grantedScopes = List.of();

    /** Permissions asked for and checked afterwards; compared with the granted scopes by {@link #missingScopes()}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "requested_scopes", nullable = false)
    private List<String> requestedScopes = List.of();

    /** The token or tokens, encrypted for the workspace and serialised; null when nothing is stored. */
    @Column(name = "credential_ref")
    private String credentialRef;

    /** Which account is connected, so a person can tell two Gmail connections apart. */
    @Column(name = "account_label")
    private String accountLabel;

    /** The person who connected the connector, when known. */
    @Column(name = "connected_by")
    private UUID connectedBy;

    /** When the connection was last made. */
    @Column(name = "connected_at")
    private Instant connectedAt;

    /** When the access token expires; null for tokens that do not expire, such as pasted ones. */
    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /** When the access token was last renewed or first issued by a sign-in. */
    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    /** A plain-language reason for the last failed check or refresh; null when healthy. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** True when a refresh was refused and only a new consent will restore the connection. */
    @Column(name = "reconnect_required", nullable = false)
    private boolean reconnectRequired;

    /** True while agents use practice data because no live credential is stored. */
    @Column(nullable = false)
    private boolean sandbox = true;

    /** When the credential was last verified with the provider. */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /**
     * Whether agents can use this connection: either practice data, or a live connection that does
     * not need a reconnect.
     *
     * @return true when the status is {@code connected} or {@code sandbox} and no reconnect is required
     */
    public boolean isUsable() {
        return !reconnectRequired && ("connected".equals(status) || "sandbox".equals(status));
    }

    /**
     * Whether the token should be refreshed now.
     *
     * <p>Ahead of expiry, not at it. A token that lapses between the check and the call fails a
     * person's action rather than a background job, and the failure looks like the integration
     * being broken.
     *
     * <p>Pure check against the current time; changes nothing. Nothing in this module calls it at
     * present, because {@code OAuthService} compares the expiry held in the decrypted tokens.
     *
     * @param margin how long before expiry the refresh should happen
     * @return true when an expiry is known and it falls within the margin
     */
    public boolean needsRefresh(Duration margin) {
        return tokenExpiresAt != null && tokenExpiresAt.minus(margin).isBefore(Instant.now());
    }

    // @find: mark needs reconnect, expired sign in
    /**
     * A refresh that failed needs consent again, which is a different state from never connected.
     *
     * <p>Sets {@code reconnectRequired} and the status {@code error} and records the reason. Called
     * through {@code OAuthService}; the caller saves the row.
     *
     * @param reason a plain sentence for the administrator, never a token
     */
    public void requireReconnect(String reason) {
        this.reconnectRequired = true;
        this.status = "error";
        this.lastError = reason;
    }

    /**
     * Marks the connection as live after a successful sign-in, replacing the granted scopes and the
     * expiry and clearing any earlier error or reconnect flag. It does not touch the stored credential.
     * Nothing in this module calls it at present; sign-ins use {@link #connectWithOAuth}.
     *
     * @param granted the approved permissions
     * @param accountLabel which account is connected
     * @param expiresAt when the access token expires, or null
     */
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
     * <p>Sets the credential, the live status, the account, the person and the time, and clears any
     * error, reconnect flag and expiry. Called by {@code ConnectorService.connect}; the caller saves.
     *
     * @param encryptedCredential the token, already encrypted for this workspace
     * @param accountLabel which account the token belongs to
     * @param connectedBy the person connecting, or null when unknown
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
     * <p>Applies {@link #connectWithToken} first, then records the requested and granted scopes and
     * the expiry, and marks the connection as just refreshed. Called by {@code OAuthService.callback};
     * the caller saves.
     *
     * @param encryptedCredential the access and refresh tokens, already encrypted for this workspace
     * @param accountLabel which account signed in
     * @param connectedBy the person who completed the sign-in
     * @param requested the permissions that were asked for and are checked afterwards
     * @param granted what the provider says was approved
     * @param expiresAt when the access token expires, or null
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
    /**
     * Stores the tokens after a successful refresh. The account and the consent are unchanged.
     *
     * <p>Replaces the credential and expiry, stamps the refresh time, clears any error and reconnect
     * flag, and returns the status to {@code connected}. Called by {@code OAuthService} after a
     * successful token call; the caller saves.
     *
     * @param expiresAt the new access token expiry, or null
     */
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
     *
     * <p>Called by {@code ConnectorService.test}; the caller saves. Practice data connections only have
     * their check time and error updated, and their status is left alone.
     *
     * @param ok whether the provider accepted the credential
     * @param error the reason shown when {@code ok} is false
     */
    public void recordCheck(boolean ok, String error) {
        this.lastCheckedAt = Instant.now();
        this.lastError = ok ? null : error;
        if (!sandbox) {
            this.status = ok ? "connected" : "error";
        }
    }

    /**
     * Notes that a check ran, without changing the state a refresh failure left behind.
     *
     * <p>Used by {@code ConnectorService.test} when a reconnect is required, since only a new consent
     * clears that state.
     */
    public void setLastCheckedAtNow() {
        this.lastCheckedAt = Instant.now();
    }

    // @find: disconnect connector, remove credential
    /**
     * Forgets the token and returns the connector to practice data.
     *
     * <p>Clears the credential, account, times, error, reconnect flag and granted scopes, and sets the
     * status to {@code sandbox}. The requested scopes and display name are kept. Called by
     * {@code ConnectorService.disconnect}; the caller saves.
     */
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

    /**
     * Whether a live token is stored that agents should use.
     *
     * <p>Used by {@code ConnectorService.credential} for pasted-token connectors. A connection in the
     * {@code error} state still counts, so agents reach the real account and see its failure.
     *
     * @return true when not practice data, a credential is stored, no reconnect is required and the status is connected or error
     */
    public boolean hasLiveCredential() {
        return !sandbox
                && credentialRef != null
                && !reconnectRequired
                && ("connected".equals(status) || "error".equals(status));
    }

    /**
     * The requested permissions that the provider did not grant, which the console uses to say which
     * permission is missing.
     *
     * @return the requested scopes absent from the granted scopes, empty when all were approved
     */
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
