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
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState extends OrgScopedEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String server;

    @Column(name = "verifier_ref", nullable = false, columnDefinition = "text")
    private String verifierRef;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

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
