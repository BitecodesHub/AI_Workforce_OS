package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.PasswordResetToken;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.PasswordResetTokens;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Sessions;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Reset links: who may create one, that each works once, briefly, and signs everything out, and
 * that the rules for creating one still hold when it is redeemed.
 */
class PasswordResetServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final String NEW_PASSWORD = "a brand new passphrase";

    private final Map<UUID, PasswordResetToken> tokenRows = new HashMap<>();
    private PasswordResetTokens tokens;
    private Users users;
    private Memberships memberships;
    private Roles roles;
    private Sessions sessions;
    private PasswordService passwords;
    private PasswordResetService resets;

    private Role owner;
    private Role admin;
    private Role viewer;
    private User target;
    private UUID issuer;
    private Actor adminCaller;

    @BeforeEach
    void setUp() {
        tokens = mock(PasswordResetTokens.class);
        users = mock(Users.class);
        memberships = mock(Memberships.class);
        roles = mock(Roles.class);
        sessions = mock(Sessions.class);
        passwords = new PasswordService(TestKeys.properties());
        resets = new PasswordResetService(
                tokens, users, memberships, roles, sessions, passwords, new GrantGuard(roles, memberships));

        owner = role("owner", "member:read", "member:update");
        admin = role("admin", "member:read", "member:update");
        viewer = role("viewer", "member:read");
        when(roles.findSystemRole("owner")).thenReturn(Optional.of(owner));

        target = new User();
        target.setEmail("eli@example.com");
        target.setDisplayName("Eli");
        target.setPasswordHash(passwords.hash("the old passphrase"));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        memberIn(target, ORG, admin);

        issuer = UUID.randomUUID();
        adminCaller = Actor.user(
                issuer.toString(), ORG.toString(), admin.getId().toString(), Set.of("member:read", "member:update"), 1);
        issuerIn(issuer, ORG, admin);

        when(tokens.save(any(PasswordResetToken.class))).thenAnswer(call -> {
            PasswordResetToken saved = call.getArgument(0);
            tokenRows.put(saved.getId(), saved);
            return saved;
        });
        when(tokens.findByTokenHash(anyString())).thenAnswer(call -> tokenRows.values().stream()
                .filter(row -> row.getTokenHash().equals(call.getArgument(0)))
                .findFirst());
        when(tokens.claim(any(UUID.class), any(Instant.class))).thenAnswer(call -> {
            PasswordResetToken row = tokenRows.get(call.getArgument(0, UUID.class));
            if (row == null || row.getUsedAt() != null) {
                return 0;
            }
            row.setUsedAt(call.getArgument(1));
            return 1;
        });
    }

    @Test
    @DisplayName("a link is single use, stored only as a hash, and lasts thirty minutes")
    void singleUse() {
        PasswordResetService.ResetLink link = resets.createLink(adminCaller, target.getId());

        assertThat(link.url()).startsWith("/sign-in?reset=");
        String raw = link.url().substring("/sign-in?reset=".length());
        PasswordResetToken row = tokenRows.values().iterator().next();
        assertThat(row.getTokenHash()).isEqualTo(PasswordResetService.hashToken(raw)).isNotEqualTo(raw);
        assertThat(row.getIssuedBy()).isEqualTo(issuer);
        assertThat(row.getOrgId()).isEqualTo(ORG);
        assertThat(Duration.between(Instant.now(), link.expiresAt())).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30));
        verify(tokens).closeOpenFor(eq(target.getId()), any(Instant.class));

        target.recordFailedLogin(1, Duration.ofMinutes(15));
        resets.redeem(raw, NEW_PASSWORD);

        assertThat(passwords.matches(NEW_PASSWORD, target.getPasswordHash())).isTrue();
        assertThat(target.getPasswordChangedAt()).isNotNull();
        assertThat(target.isLocked()).isFalse();
        assertThat(target.getFailedLoginCount()).isZero();
        verify(sessions).revokeAllForUser(eq(target.getId()), any(Instant.class), eq("password reset"));

        assertThatThrownBy(() -> resets.redeem(raw, "yet another passphrase"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo(PasswordResetService.LINK_UNUSABLE);
                });
        assertThat(passwords.matches(NEW_PASSWORD, target.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("an expired link is refused and sets nothing")
    void expired() {
        PasswordResetService.ResetLink link = resets.createLink(adminCaller, target.getId());
        String raw = link.url().substring("/sign-in?reset=".length());
        tokenRows.values().iterator().next().setExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> resets.redeem(raw, NEW_PASSWORD))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(passwords.matches("the old passphrase", target.getPasswordHash())).isTrue();
        verify(sessions, never()).revokeAllForUser(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown link and a too-short password are refused, the latter with the link still good")
    void unknownAndShort() {
        assertThatThrownBy(() -> resets.redeem("not-a-real-token", NEW_PASSWORD))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo(PasswordResetService.LINK_UNUSABLE);
                });

        String raw = resets.createLink(adminCaller, target.getId()).url().substring("/sign-in?reset=".length());
        assertThatThrownBy(() -> resets.redeem(raw, "short"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(tokenRows.values().iterator().next().getUsedAt()).isNull();
        resets.redeem(raw, NEW_PASSWORD);
    }

    @Test
    @DisplayName("someone who also belongs to another workspace cannot be reset from this one")
    void crossWorkspaceRefused() {
        Membership here = membership(target, ORG, admin);
        Membership elsewhere = membership(target, OTHER_ORG, owner);
        when(memberships.findByUserIdAndStatus(target.getId(), "active")).thenReturn(List.of(here, elsewhere));

        assertThatThrownBy(() -> resets.createLink(adminCaller, target.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(tokens, never()).save(any());
    }

    @Test
    @DisplayName("someone with no active membership in this workspace is not found")
    void outsiderNotFound() {
        when(memberships.findByUserIdAndStatus(target.getId(), "active"))
                .thenReturn(List.of(membership(target, OTHER_ORG, admin)));

        assertThatThrownBy(() -> resets.createLink(adminCaller, target.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("only an owner can create a link for an owner, and nobody for themselves")
    void ownersAndSelf() {
        memberIn(target, ORG, owner);
        assertThatThrownBy(() -> resets.createLink(adminCaller, target.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));

        UUID ownerId = UUID.randomUUID();
        issuerIn(ownerId, ORG, owner);
        Actor ownerCaller = Actor.user(
                ownerId.toString(), ORG.toString(), owner.getId().toString(), Set.of("member:read", "member:update"), 1);
        String url = resets.createLink(ownerCaller, target.getId()).url();
        assertThat(url).startsWith("/sign-in?reset=");
        resets.redeem(url.substring("/sign-in?reset=".length()), NEW_PASSWORD);
        assertThat(passwords.matches(NEW_PASSWORD, target.getPasswordHash())).isTrue();

        Actor self = Actor.user(target.getId().toString(), ORG.toString(), owner.getId().toString(), Set.of("member:update"), 1);
        assertThatThrownBy(() -> resets.createLink(self, target.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SELF_ACTION_FORBIDDEN));
    }

    @Test
    @DisplayName("a role that can update members but holds less than the person's own role cannot create a link")
    void narrowerRoleRefused() {
        Role helper = role("helper", "member:update");
        UUID helperId = UUID.randomUUID();
        issuerIn(helperId, ORG, helper);
        Actor helperCaller =
                Actor.user(helperId.toString(), ORG.toString(), helper.getId().toString(), Set.of("member:update"), 1);

        assertThatThrownBy(() -> resets.createLink(helperCaller, target.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(tokens, never()).save(any());
    }

    @Test
    @DisplayName("a link is refused if the account of the administrator who issued it has been deactivated")
    void issuerDeactivatedBeforeRedeeming() {
        User issuerAccount = org.mockito.Mockito.mock(User.class);
        when(issuerAccount.isActive()).thenReturn(false);
        String raw = rawToken(resets.createLink(adminCaller, target.getId()));
        when(users.findById(issuer)).thenReturn(Optional.of(issuerAccount));

        assertRefusedAndUnchanged(raw);
    }

    @Test
    @DisplayName("a link is refused, and closed, if the person joins another workspace before using it")
    void targetJoinsAnotherWorkspaceBeforeRedeeming() {
        String raw = rawToken(resets.createLink(adminCaller, target.getId()));
        when(memberships.findByUserIdAndStatus(target.getId(), "active"))
                .thenReturn(List.of(membership(target, ORG, admin), membership(target, OTHER_ORG, owner)));

        assertRefusedAndUnchanged(raw);

        // Closed by the refusal: leaving the other workspace again does not reopen it.
        memberIn(target, ORG, admin);
        assertRefusedAndUnchanged(raw);
    }

    @Test
    @DisplayName("a link from an administrator is refused if the person has been made an owner since")
    void targetPromotedToOwnerBeforeRedeeming() {
        String raw = rawToken(resets.createLink(adminCaller, target.getId()));
        memberIn(target, ORG, owner);

        assertRefusedAndUnchanged(raw);
    }

    @Test
    @DisplayName("a link is refused if its issuer has been removed, or can no longer manage members")
    void issuerRemovedOrDemotedBeforeRedeeming() {
        String removed = rawToken(resets.createLink(adminCaller, target.getId()));
        when(memberships.findActive(issuer, ORG)).thenReturn(Optional.empty());
        assertRefusedAndUnchanged(removed);

        issuerIn(issuer, ORG, admin);
        String demoted = rawToken(resets.createLink(adminCaller, target.getId()));
        issuerIn(issuer, ORG, viewer);
        assertRefusedAndUnchanged(demoted);
    }

    private void assertRefusedAndUnchanged(String raw) {
        assertThatThrownBy(() -> resets.redeem(raw, NEW_PASSWORD))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo(PasswordResetService.LINK_UNUSABLE);
                });
        assertThat(passwords.matches("the old passphrase", target.getPasswordHash())).isTrue();
        assertThat(tokenRows.values().stream().filter(row -> row.getTokenHash().equals(PasswordResetService.hashToken(raw))))
                .singleElement()
                .satisfies(row -> assertThat(row.getUsedAt()).as("the refused link is closed").isNotNull());
        verify(sessions, never()).revokeAllForUser(any(), any(), any());
    }

    private static String rawToken(PasswordResetService.ResetLink link) {
        return link.url().substring("/sign-in?reset=".length());
    }

    private Role role(String name, String... permissions) {
        Role role = new Role();
        role.setName(name);
        role.setPermissions(new LinkedHashSet<>(List.of(permissions)));
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        return role;
    }

    /** The issuer's own membership and active account, as redemption looks them up. */
    private void issuerIn(UUID userId, UUID orgId, Role role) {
        if (!userId.equals(target.getId())) {
            lenient().when(users.findById(userId)).thenReturn(Optional.of(new User()));
        }
        Membership membership = new Membership();
        membership.setUserId(userId);
        membership.setOrgId(orgId);
        membership.setRoleId(role.getId());
        when(memberships.findActive(userId, orgId)).thenReturn(Optional.of(membership));
    }

    private void memberIn(User user, UUID orgId, Role role) {
        when(memberships.findByUserIdAndStatus(user.getId(), "active")).thenReturn(List.of(membership(user, orgId, role)));
    }

    private static Membership membership(User user, UUID orgId, Role role) {
        Membership membership = new Membership();
        membership.setUserId(user.getId());
        membership.setOrgId(orgId);
        membership.setRoleId(role.getId());
        return membership;
    }
}
