// @find: membership, workspace member, member role, user in workspace, org member, removed member, reactivate member, Membership entity, memberships table
// @what: JPA entity for a person's membership and role in one workspace.
// @flow: Used by Memberships repository, AuthService, GrantGuard, MemberController, InternalMembershipController.
package os.aiworkforce.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/** A person's place in one workspace, and the role they hold there. */
@Entity
@Table(name = "memberships")
public class Membership extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    public boolean isActive() {
        return "active".equals(status);
    }

    // @find: reactivate member, re-invite removed member, suspended member returns, accept invitation again
    /**
     * Brings a removed or suspended member back, at the role their new invitation names.
     *
     * <p>The row is reused rather than replaced: the unique index on user and workspace allows one
     * row per person, and that row is also the record that they were a member before.
     */
    public void reactivate(UUID newRoleId, UUID inviter, Instant now) {
        this.status = "active";
        this.roleId = newRoleId;
        this.invitedBy = inviter;
        this.joinedAt = now;
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

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public void setInvitedAt(Instant invitedAt) {
        this.invitedAt = invitedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
