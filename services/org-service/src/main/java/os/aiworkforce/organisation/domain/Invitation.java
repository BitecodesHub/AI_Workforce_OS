// @find: invitation entity, invitation status, pending accepted revoked expired, token hash, invited by, expires at, invitations table
// @what: JPA entity for a workspace invitation and its lifecycle states.
// @flow: Mapped to the invitations table; used by InvitationService.
package os.aiworkforce.organisation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * An open offer to join a workspace, at a named role.
 *
 * <p>Only the token's hash is stored, the same reasoning as {@link Credential}: an invitation row
 * in a database dump must not be a usable invitation link. The raw token is handed back once, at
 * creation, and never again - {@link os.aiworkforce.organisation.service.InvitationService}
 * returns it in the create response so an administrator can copy the accept-invitation link, and
 * nothing later reveals it a second time.
 */
@Entity
@Table(name = "invitations")
public class Invitation extends OrgScopedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    public boolean isPending() {
        return "pending".equals(status);
    }

    /** Withdrawn by an administrator, or replaced by a newer invitation to the same address. */
    public boolean isRevoked() {
        return "revoked".equals(status);
    }

    public boolean isAccepted() {
        return "accepted".equals(status);
    }

    /** Stops the link working. The row is kept, so the list still shows it was sent and withdrawn. */
    public void revoke() {
        this.status = "revoked";
    }

    public boolean hasExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    /** Keeps the hash out of logs and stack traces. */
    @Override
    public String toString() {
        return "Invitation[email=" + email + ", role=" + roleName + ", status=" + status + "]";
    }
}
