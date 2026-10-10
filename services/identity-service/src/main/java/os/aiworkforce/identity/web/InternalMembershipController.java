// @find: internal memberships, bootstrap owner, bootstrap member, check grant, member permissions, accept invitation, create workspace owner, /internal/memberships, InternalMembershipController
// @what: Service-to-service endpoints granting memberships for new workspaces and accepted invitations, and checking grants.
// @flow: Called by the organisation service with a service token; uses GrantGuard, Memberships, Roles.
package os.aiworkforce.identity.web;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.identity.service.GrantGuard;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Grants memberships on behalf of the organisation service: the owner of a new workspace, and a
 * person accepting an invitation.
 *
 * <p>Organisations live in the organisation service; memberships and roles live here. Creating a
 * workspace therefore spans two services: the organisation service creates the organisation row,
 * then calls here to grant the person who created it the system {@code owner} role. Accepting an
 * invitation spans them the same way, and so does checking, before an invitation is created, that
 * the person sending it may give the role it names.
 *
 * <p>Reachable only with a service token minted through {@code /internal/tokens}, the same as
 * every other internal endpoint - never with a person's own token, whatever permissions it
 * carries. Granting a role is exactly the kind of action that must not be reachable by presenting
 * one's own credentials with a crafted body.
 *
 * <p>Idempotent: calling it twice for the same user and organisation returns the existing
 * membership rather than creating a second one or failing. The organisation service may retry a
 * timed-out call, and a retry must not produce two owners where one was asked for.
 */
@RestController
@RequestMapping("/internal/memberships")
@Tag(name = "Internal")
public class InternalMembershipController {

    private static final Logger log = LoggerFactory.getLogger(InternalMembershipController.class);

    /** The check-grant reason for an invitation to someone who is already an active member. */
    public static final String ALREADY_MEMBER = "already_member";

    private final Memberships memberships;
    private final Roles roles;
    private final Users users;
    private final GrantGuard guard;

    public InternalMembershipController(Memberships memberships, Roles roles, Users users, GrantGuard guard) {
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
        this.guard = guard;
    }

    public record BootstrapOwnerRequest(@NotNull UUID orgId, @NotNull UUID userId) {}

    /**
     * @param invitedBy who sent the invitation; their membership now, not when they sent it, decides
     *     whether the role may still be given
     * @param email the address the invitation was sent to, when known; the account must have it
     */
    public record BootstrapMemberRequest(
            @NotNull UUID orgId,
            @NotNull UUID userId,
            @NotBlank String roleName,
            @NotNull UUID invitedBy,
            String email) {}

    public record MembershipResponse(UUID membershipId, UUID orgId, UUID userId, String role) {}

    /**
     * @param email the address being invited, when the grant is for an invitation; an address that
     *     already belongs to an active member is answered {@code already_member}, since accepting
     *     would leave them exactly as they are
     */
    public record CheckGrantRequest(
            @NotNull UUID orgId, @NotNull UUID actorUserId, @NotBlank String roleName, String email) {

        public CheckGrantRequest(UUID orgId, UUID actorUserId, String roleName) {
            this(orgId, actorUserId, roleName, null);
        }
    }

    /**
     * @param reason null when allowed; otherwise {@code unknown_role}, {@code not_a_member},
     *     {@code owner_only}, {@code exceeds_grantor} or {@code already_member}
     */
    public record CheckGrantResponse(boolean allowed, String reason) {}

    public record PermissionsRequest(@NotNull UUID orgId, @NotNull UUID userId) {}

    /** @param member whether the person has an active membership; permissions are empty when not */
    public record PermissionsResponse(boolean member, java.util.List<String> permissions) {}

    // @find: bootstrap owner, workspace creator gets owner role, POST /internal/memberships/bootstrap-owner
    @PostMapping("/bootstrap-owner")
    @Operation(summary = "Internal: grant the owner role for a workspace that was just created")
    @Transactional
    public MembershipResponse bootstrapOwner(@Valid @RequestBody BootstrapOwnerRequest request) {
        requireService();

        Role ownerRole = roles.findSystemRole("owner")
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_ERROR, "The owner role has not been seeded on this platform."));

        Membership existing = memberships
                .findByUserIdAndOrgId(request.userId(), request.orgId())
                .orElse(null);
        if (existing != null) {
            return new MembershipResponse(existing.getId(), request.orgId(), request.userId(), "owner");
        }

        Membership membership = new Membership();
        membership.setId(UuidV7.generate());
        membership.setUserId(request.userId());
        membership.setOrgId(request.orgId());
        membership.setRoleId(ownerRole.getId());
        membership.setStatus("active");
        membership.setJoinedAt(Instant.now());
        memberships.save(membership);

        log.info("Granted owner in workspace {} to user {}", request.orgId(), request.userId());
        return new MembershipResponse(membership.getId(), request.orgId(), request.userId(), "owner");
    }

    // @find: bootstrap member, accept invitation, add member, POST /internal/memberships/bootstrap-member
    /**
     * Grants the role an invitation names, to the person accepting it.
     *
     * <p>The organisation service creates the invitation and, once a person accepts it - with a
     * new account or one they already had - calls here to place them in the workspace. The role is
     * checked against the inviter's membership <em>now</em> ({@link GrantGuard}): an invitation can
     * be accepted up to a week after it was sent, and an inviter demoted or removed in that time
     * can no longer give what they gave then. That also means {@code owner} is granted only when
     * the inviter is still an owner.
     *
     * <ul>
     *   <li>No membership yet: one is created at the invited role.
     *   <li>A removed or suspended membership: it is made active again at the invited role, with a
     *       new joining date. That is how a person removed by mistake, or hired back, returns.
     *   <li>An active membership: left exactly as it is, and the role it actually holds is
     *       returned. Accepting a second invitation is not a way to change one's role.
     * </ul>
     *
     * <p>Idempotent, like {@link #bootstrapOwner}: a retried call finds the active membership the
     * first one made. Behind the same internal-service-token gate.
     */
    @PostMapping("/bootstrap-member")
    @Operation(summary = "Internal: grant a named role to a person who accepted an invitation")
    @Transactional
    public MembershipResponse bootstrapMember(@Valid @RequestBody BootstrapMemberRequest request) {
        requireService();

        Role role = roles.findAvailable(request.orgId(), request.roleName())
                .orElseThrow(() -> ApiException.validation("roleName", "no such role is available to this workspace"));

        if (request.email() != null && !request.email().isBlank()) {
            // The organisation service checks this too; here it is checked against the account
            // itself, so a mistake there cannot place one person's account under another's invite.
            User user = users.findById(request.userId())
                    .orElseThrow(() -> ApiException.notFound("user", request.userId()));
            if (!normalise(user.getEmail()).equals(normalise(request.email()))) {
                throw new ApiException(
                                ErrorCode.PERMISSION_DENIED, "This invitation was sent to a different email address.")
                        .with("reason", "email_mismatch");
            }
        }

        Membership existing = memberships
                .findByUserIdAndOrgId(request.userId(), request.orgId())
                .orElse(null);
        if (existing != null && existing.isActive()) {
            String held = roles.findById(existing.getRoleId()).map(Role::getName).orElse(null);
            return new MembershipResponse(existing.getId(), request.orgId(), request.userId(), held);
        }

        GrantGuard.Grantor inviter = guard.member(request.invitedBy(), request.orgId())
                .orElseThrow(() -> new ApiException(
                                ErrorCode.PERMISSION_DENIED,
                                "The person who sent this invitation is no longer a member of the workspace.")
                        .with("reason", GrantGuard.NOT_A_MEMBER));
        guard.assertCanGrant(inviter, role);

        Instant now = Instant.now();
        if (existing != null) {
            String previous = existing.getStatus();
            existing.reactivate(role.getId(), request.invitedBy(), now);
            memberships.save(existing);
            log.info(
                    "Reactivated {} membership of user {} in workspace {} as {}",
                    previous,
                    request.userId(),
                    request.orgId(),
                    role.getName());
            return new MembershipResponse(existing.getId(), request.orgId(), request.userId(), role.getName());
        }

        Membership membership = new Membership();
        membership.setId(UuidV7.generate());
        membership.setUserId(request.userId());
        membership.setOrgId(request.orgId());
        membership.setRoleId(role.getId());
        membership.setStatus("active");
        membership.setInvitedBy(request.invitedBy());
        membership.setJoinedAt(now);
        memberships.save(membership);

        log.info("Granted {} in workspace {} to user {}", role.getName(), request.orgId(), request.userId());
        return new MembershipResponse(membership.getId(), request.orgId(), request.userId(), role.getName());
    }

    // @find: check grant, may inviter give role, POST /internal/memberships/check-grant
    /**
     * Whether a member may give a role, asked before an invitation is created.
     *
     * <p>The organisation service holds no roles, so without this an invitation naming a role that
     * does not exist, or one its sender may not give, would be refused only when accepted - after
     * the new account had been created. Answered rather than thrown: the caller decides how to say
     * no. The same rule is applied again at acceptance, since the answer can change in between.
     */
    @PostMapping("/check-grant")
    @Operation(summary = "Internal: whether a member may give a named role")
    @Transactional(readOnly = true)
    public CheckGrantResponse checkGrant(@Valid @RequestBody CheckGrantRequest request) {
        requireService();

        Role role = roles.findAvailable(request.orgId(), request.roleName()).orElse(null);
        if (role == null) {
            return new CheckGrantResponse(false, GrantGuard.UNKNOWN_ROLE);
        }
        GrantGuard.Grantor grantor =
                guard.member(request.actorUserId(), request.orgId()).orElse(null);
        if (grantor == null) {
            return new CheckGrantResponse(false, GrantGuard.NOT_A_MEMBER);
        }
        GrantGuard.Decision decision = guard.check(grantor, role);
        if (decision.allowed() && request.email() != null && !request.email().isBlank()) {
            boolean alreadyMember = users.findByEmail(normalise(request.email()))
                    .flatMap(user -> memberships.findActive(user.getId(), request.orgId()))
                    .isPresent();
            if (alreadyMember) {
                return new CheckGrantResponse(false, ALREADY_MEMBER);
            }
        }
        return new CheckGrantResponse(decision.allowed(), decision.reason());
    }

    // @find: member permissions, POST /internal/memberships/permissions
    /**
     * What a person holds in a workspace through their membership, for a sibling service that acts
     * for them without their request in flight - an agent searching documents for the person it
     * works for. Only a service token reaches this. A person who is not an active member holds
     * nothing, and is told apart from "could not ask" by the answer itself.
     */
    @PostMapping("/permissions")
    @Operation(summary = "Internal: the permissions a person holds in a workspace")
    @Transactional(readOnly = true)
    public PermissionsResponse permissions(@Valid @RequestBody PermissionsRequest request) {
        requireService();
        return memberships
                .findActive(request.userId(), request.orgId())
                .flatMap(membership -> roles.findById(membership.getRoleId()))
                .map(role -> new PermissionsResponse(true, java.util.List.copyOf(role.getPermissions())))
                .orElse(new PermissionsResponse(false, java.util.List.of()));
    }

    /**
     * A person's own token, whatever it carries, must never be able to grant a role by calling
     * these directly - only a sibling service acting through the internal token flow may.
     */
    private static void requireService() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
    }

    private static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
