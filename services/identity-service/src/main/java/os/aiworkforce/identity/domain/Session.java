package os.aiworkforce.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * One link in a refresh-token family.
 *
 * <p>Each use of a refresh token issues a new one and retires the old. Presenting a retired token
 * means somebody has a copy that should no longer exist, so the whole family is revoked - the
 * attacker's session and the victim's alike. Revoking both is deliberate: a false positive costs
 * one sign-in, and a false negative leaves a stolen session live for a month.
 */
@Entity
@Table(name = "sessions")
public class Session extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Shared by every token descended from one sign-in. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** Only the hash is stored; the token itself is shown once and never persisted. */
    @Column(name = "refresh_token_hash", nullable = false)
    private String refreshTokenHash;

    @Column(name = "previous_id")
    private UUID previousId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "org_id")
    private UUID orgId;

    public boolean isUsable() {
        return revokedAt == null && usedAt == null && expiresAt.isAfter(Instant.now());
    }

    public boolean isReplayed() {
        return usedAt != null;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public void revoke(String reason) {
        if (revokedAt == null) {
            revokedAt = Instant.now();
            revokedReason = reason;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void setFamilyId(UUID familyId) {
        this.familyId = familyId;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public void setRefreshTokenHash(String refreshTokenHash) {
        this.refreshTokenHash = refreshTokenHash;
    }

    public UUID getPreviousId() {
        return previousId;
    }

    public void setPreviousId(UUID previousId) {
        this.previousId = previousId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
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

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }
}
