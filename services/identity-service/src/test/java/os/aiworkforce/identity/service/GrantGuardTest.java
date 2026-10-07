package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;

/** The grant rule on its own: nobody gives what they do not hold, and owners only by owners. */
class GrantGuardTest {

    private final GrantFixture workspace = new GrantFixture();
    private final GrantGuard guard = workspace.guard;

    @Test
    @DisplayName("an owner may give any role, the owner role included")
    void ownerGivesAnything() {
        UUID owner = workspace.member("Olivia Owner", workspace.owner, "active");
        GrantGuard.Grantor grantor = guard.caller(workspace.tokenOf(owner));

        assertThat(grantor.owner()).isTrue();
        assertThat(guard.check(grantor, workspace.owner).allowed()).isTrue();
        assertThat(guard.check(grantor, workspace.admin).allowed()).isTrue();
    }

    @Test
    @DisplayName("an admin may give the admin role but never the owner role")
    void adminCannotMakeOwners() {
        UUID admin = workspace.member("Arjun Admin", workspace.admin, "active");
        GrantGuard.Grantor grantor = guard.caller(workspace.tokenOf(admin));

        assertThat(guard.check(grantor, workspace.admin).allowed()).isTrue();
        assertThat(guard.check(grantor, workspace.owner))
                .isEqualTo(GrantGuard.Decision.refuse(GrantGuard.OWNER_ONLY));
    }

    @Test
    @DisplayName("a role carrying a permission the grantor lacks is refused")
    void subsetRule() {
        Role lead = workspace.customRole("lead", Permission.Codes.MEMBER_INVITE, Permission.Codes.MEMBER_READ);
        UUID inviter = workspace.member("Lena Lead", lead, "active");
        GrantGuard.Grantor grantor = guard.member(inviter, GrantFixture.ORG).orElseThrow();

        assertThat(guard.check(grantor, workspace.admin))
                .isEqualTo(GrantGuard.Decision.refuse(GrantGuard.EXCEEDS_GRANTOR));
        assertThat(guard.check(grantor, lead).allowed()).isTrue();
        assertThatThrownBy(() -> guard.assertCanGrant(grantor, workspace.employee))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                    assertThat(e.details()).containsEntry("reason", GrantGuard.EXCEEDS_GRANTOR);
                });
    }

    @Test
    @DisplayName("a token older than a demotion carries only what the membership still holds")
    void demotionCountsAtOnce() {
        UUID admin = workspace.member("Arjun Admin", workspace.admin, "active");
        Actor staleToken = workspace.tokenOf(admin);
        workspace.membershipOf(admin).setRoleId(workspace.employee.getId());

        GrantGuard.Grantor grantor = guard.caller(staleToken);

        assertThat(grantor.permissions()).isEqualTo(workspace.employee.getPermissions());
        assertThat(guard.check(grantor, workspace.admin).allowed()).isFalse();
    }

    @Test
    @DisplayName("someone removed from the workspace can grant nothing, whatever their token says")
    void removedMemberGrantsNothing() {
        UUID owner = workspace.member("Olivia Owner", workspace.owner, "active");
        Actor token = workspace.tokenOf(owner);
        workspace.membershipOf(owner).setStatus("removed");

        GrantGuard.Grantor grantor = guard.caller(token);

        assertThat(grantor.permissions()).isEmpty();
        assertThat(grantor.owner()).isFalse();
        assertThat(guard.member(owner, GrantFixture.ORG)).isEmpty();
    }

    @Test
    @DisplayName("only an owner may change or remove an owner")
    void ownersManagedByOwners() {
        UUID owner = workspace.member("Olivia Owner", workspace.owner, "active");
        UUID admin = workspace.member("Arjun Admin", workspace.admin, "active");

        GrantGuard.Grantor byAdmin = guard.caller(workspace.tokenOf(admin));
        assertThatThrownBy(() -> guard.assertCanManage(byAdmin, workspace.membershipOf(owner)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.details()).containsEntry("reason", GrantGuard.OWNER_ONLY));

        GrantGuard.Grantor byOwner = guard.caller(workspace.tokenOf(owner));
        guard.assertCanManage(byOwner, workspace.membershipOf(admin));
    }

    @Test
    @DisplayName("composing a role is refused for any permission the author lacks, naming them")
    void compositionNamesMissingCodes() {
        UUID manager = workspace.member("Maya Manager", workspace.manager, "active");
        GrantGuard.Grantor grantor = guard.caller(workspace.tokenOf(manager));

        assertThatThrownBy(() -> guard.assertCanCompose(
                        grantor, Set.of(Permission.Codes.AGENT_READ, Permission.Codes.MEMBER_REMOVE)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.details()).containsEntry("missing", java.util.List.of("member:remove")));
        guard.assertCanCompose(grantor, Set.of(Permission.Codes.AGENT_READ));
    }

    @Test
    @DisplayName("a workspace role named owner is not the owner role")
    void onlyTheSystemOwnerRoleCounts() {
        Role imposter = workspace.customRole("owner", Permission.Codes.WORKSPACE_READ);
        assertThat(guard.isOwnerRole(imposter)).isFalse();
        assertThat(guard.isOwnerRole(workspace.owner)).isTrue();
        assertThat(guard.isOwnerRole(workspace.owner.getId())).isTrue();
    }
}
