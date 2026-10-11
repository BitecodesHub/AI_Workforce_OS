// @find: oauth state entity, oauth_states table, sign in state, pkce verifier ref, single use, expires, used at, csrf protection, connect with oauth
// @what: Database entity for one pending OAuth sign-in: who started it, which connector, PKCE verifier reference and expiry.
// @flow: Created by OAuthService.start; consumed by OAuthService.callback
package os.aiworkforce.integrations.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One consent screen opened: who opened it, for which connector, and its PKCE verifier.
 *
 * <p>The row is what makes the signed state single use. It is marked used the moment the callback
 * claims it, in one conditional update, so two requests carrying the same state cannot both win.
 *
 * <p>Created by {@code OAuthService.start}, read by {@code OAuthService.callback} and claimed
 * through {@code OAuthStates.claim}. Expired rows are removed by {@code OAuthStates.deleteExpired}.
 * The accessors below are plain getters and setters for the columns.
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState extends OrgScopedEntity {

    /** The person who opened the consent screen; null when the actor was not a person. The callback requires it to match the signed state. */
    @Column(name = "user_id")
    private UUID userId;

    /** The connector key the sign-in is for, for example {@code gmail}. */
    @Column(nullable = false)
    private String server;

    /** The PKCE code verifier, encrypted for the workspace. Decrypted by the callback to redeem the authorization code. */
    @Column(name = "verifier_ref", nullable = false, columnDefinition = "text")
    private String verifierRef;

    /** When the state stops being accepted, set from the configured state lifetime. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When the state was claimed by a callback; null while it is still unused. */
    @Column(name = "used_at")
    private Instant usedAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getVerifierRef() {
        return verifierRef;
    }

    public void setVerifierRef(String verifierRef) {
        this.verifierRef = verifierRef;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }
}
