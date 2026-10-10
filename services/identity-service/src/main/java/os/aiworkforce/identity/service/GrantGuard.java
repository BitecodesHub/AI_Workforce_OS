// @find: grant guard, grant role, who can give a role, role escalation, owner only, subset rule, promote to owner, assert can grant, can manage member, can compose role, GrantGuard
// @what: Single rule that nobody grants a role or permission they do not hold; only owners touch owners.
// @flow: Called by MemberController, RoleController, InternalMembershipController, PasswordResetService.
package os.aiworkforce.identity.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The one rule for handing out authority: nobody gives what they do not hold.
 *
 * <p>Every path that grants a role goes through here - changing a member's role, building or
 * editing a role, and accepting an invitation - so the rule cannot be true on one path and
 * forgotten on another. Two checks make it up:
 *
 * <ul>
 *   <li><b>Subset.</b> A role may be granted, or composed, only if every permission it carries is
 *       one the grantor already holds. That alone stops an administrator promoting themselves to
 *       owner, because the owner role carries permissions the administrator role does not.
 *   <li><b>Owners by owners.</b> Only an owner may grant the owner role, take it away, or change
 *       or remove a member who holds it. Without this, a second owner could be made by anybody
 *       holding every permission, and then used to demote the first.
 * </ul>
 *
 * <p>The grantor's authority is what their token carries <em>and</em> what their membership holds
 * now. A token can be up to one access-token lifetime old, and someone demoted in that window must
 * not keep handing out the role they just lost.
 */
@Component
public class GrantGuard {

    /** The system role that sits above every other. */
    public static final String OWNER = "owner";

    /** The reasons a grant is refused, in the words the internal check-grant call returns. */
    public static final String UNKNOWN_ROLE = "unknown_role";

    public static final String NOT_A_MEMBER = "not_a_member";
    public static final String OWNER_ONLY = "owner_only";
    public static final String EXCEEDS_GRANTOR = "exceeds_grantor";

    private final Roles roles;
    private final Memberships memberships;

    public GrantGuard(Roles roles, Memberships memberships) {
        this.roles = roles;
        this.memberships = memberships;
    }

    /**
     * Someone handing out authority, as far as it matters for the rule.
     *
     * @param userId the person
     * @param roleId the role they hold now, or null when they hold none in this workspace
     * @param permissions what they may pass on
     * @param owner whether they hold the owner role
     */
    public record Grantor(UUID userId, UUID roleId, Set<String> permissions, boolean owner) {

        public Grantor {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        /** Someone with no active membership: they can grant nothing at all. */
        static Grantor nobody(UUID userId) {
            return new Grantor(userId, null, Set.of(), false);
        }
    }

    /** Whether a grant is allowed, and if not, why. */
    public record Decision(boolean allowed, String reason) {

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision refuse(String reason) {
            return new Decision(false, reason);
        }
    }

    // @find: current caller as grantor, who is granting
    /**
     * The person making this request, as a grantor.
     *
     * <p>The permissions are those both the token carries and the current membership holds: a
     * demotion since the token was minted counts at once, and a promotion since then counts from
     * the next refresh, as it does everywhere else.
     */
    public Grantor caller(Actor actor) {
        UUID userId = UUID.fromString(actor.id());
        if (actor.orgId() == null) {
            return Grantor.nobody(userId);
        }
        Optional<Grantor> current = member(userId, UUID.fromString(actor.orgId()));
        if (current.isEmpty()) {
            return Grantor.nobody(userId);
        }
        Grantor now = current.get();
        Set<String> held = new LinkedHashSet<>(now.permissions());
        held.retainAll(actor.permissions());
        boolean owner = now.owner() && now.roleId().toString().equals(actor.roleId());
        return new Grantor(userId, now.roleId(), held, owner);
    }

    // @find: member as grantor, check invitation sender
    /**
     * A member as their current membership describes them, for a check made without their token -
     * an invitation they sent being accepted days later, after they may have been demoted.
     */
    public Optional<Grantor> member(UUID userId, UUID orgId) {
        return memberships
                .findActive(userId, orgId)
                .flatMap(membership -> roles.findById(membership.getRoleId()))
                .map(role -> new Grantor(userId, role.getId(), role.getPermissions(), isOwnerRole(role)));
    }

    // @find: check grant, may give role, refuse grant reasons
    /** Whether {@code grantor} may give {@code role} to someone, themselves included. */
    public Decision check(Grantor grantor, Role role) {
        if (isOwnerRole(role) && !grantor.owner()) {
            return Decision.refuse(OWNER_ONLY);
        }
        if (!role.isWithin(grantor.permissions())) {
            return Decision.refuse(EXCEEDS_GRANTOR);
        }
        return Decision.allow();
    }

    // @find: assert can grant role, refuse escalation
    /** Refuses with 403 unless {@code grantor} may give {@code role}. */
    public void assertCanGrant(Grantor grantor, Role role) {
        Decision decision = check(grantor, role);
        if (!decision.allowed()) {
            throw refusal(decision.reason());
        }
    }

    // @find: assert can grant role for actor
    /** {@link #assertCanGrant(Grantor, Role)} for the person making this request. */
    public void assertCanGrant(Actor actor, Role role) {
        assertCanGrant(caller(actor), role);
    }

    // @find: assert can manage member, only owner changes owner
    /**
     * Refuses with 403 unless {@code grantor} may change or remove {@code target}.
     *
     * <p>An owner only by an owner. Anyone else only by somebody holding everything the member's
     * current role carries: taking authority away is as much a decision about it as handing it
     * out, so a narrowly delegated role cannot demote or remove the administrators above it.
     */
    public void assertCanManage(Grantor grantor, Membership target) {
        Role current = roles.findById(target.getRoleId()).orElse(null);
        if (current == null) {
            // A role deleted from under its holder grants nothing, so there is nothing to protect.
            return;
        }
        if (isOwnerRole(current) && !grantor.owner()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only an owner can change or remove another owner.")
                    .with("reason", OWNER_ONLY);
        }
        if (!current.isWithin(grantor.permissions())) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "This member's role includes permissions you do not have, so you cannot change or"
                                    + " remove them.")
                    .with("reason", EXCEEDS_GRANTOR);
        }
    }

    // @find: assert can compose role, role permissions subset
    /**
     * Refuses with 403 unless {@code grantor} holds every code in {@code codes}, for composing a
     * role. Without it the subset rule could be stepped around by first building a role that
     * carries everything, then granting that.
     */
    public void assertCanCompose(Grantor grantor, Set<String> codes) {
        List<String> missing = codes.stream()
                .filter(code -> !grantor.permissions().contains(code))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "A role can only include permissions you have yourself.")
                    .with("reason", EXCEEDS_GRANTOR)
                    .with("missing", missing);
        }
    }

    public boolean isOwnerRole(Role role) {
        return role != null && role.isSystem() && role.isPlatformWide() && OWNER.equals(role.getName());
    }

    /** Whether the role with this id is the owner role. */
    public boolean isOwnerRole(UUID roleId) {
        return roleId != null && roles.findById(roleId).map(this::isOwnerRole).orElse(false);
    }

    private static ApiException refusal(String reason) {
        String message = OWNER_ONLY.equals(reason)
                ? "Only an owner can make someone an owner."
                : "That role includes permissions you do not have, so you cannot give it to anyone.";
        return new ApiException(ErrorCode.PERMISSION_DENIED, message).with("reason", reason);
    }
}
