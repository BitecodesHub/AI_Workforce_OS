package os.aiworkforce.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * A one-time link that lets a person choose a new password, created by an administrator.
 *
 * <p>There is no outbound email on this platform, so the link is handed to the administrator to
 * pass on, exactly as an invitation link is. Only the SHA-256 of the token is stored: a database
 * read must not be enough to reset anybody's password.
 *
 * <p>The workspace and the issuer are kept so the link can be checked again when it is redeemed:
 * the person may have joined another workspace or become an owner since, and the issuer may have
 * lost the right to manage members. {@code issued_by} is set explicitly rather than read from
 * {@code created_by}, which is filled from whatever actor the request carried and could read
 * "system".
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The workspace whose administrator issued the link. */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** The person who issued the link. */
    @Column(name = "issued_by", nullable = false)
    private UUID issuedBy;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the link is redeemed, or when a newer link for the same person replaces it. */
    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(UUID issuedBy) {
        this.issuedBy = issuedBy;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
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
