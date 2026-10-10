// @find: tests for sign in audit, audit events, failed login audit, lockout audit, unknown address fingerprint
// @what: Tests that sign-in outcomes are written to the audit log per workspace.
package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.Session;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Sessions;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.web.audit.AuditClient;

/**
 * What sign-in and its neighbours write to the audit log: every outcome, in every workspace the
 * person belongs to, and nothing a guesser could use.
 */
class AuthAuditTest {

    private static final String PASSWORD = "correct horse battery";
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000bb");

    private final Map<UUID, Session> sessionRows = new HashMap<>();
    private Sessions sessions;
    private Memberships memberships;
    private AuditClient audit;
    private AuthService auth;
    private User user;
    private String userId;

    @BeforeEach
    void setUp() {
        PlatformProperties properties = TestKeys.properties();
        Users users = mock(Users.class);
        sessions = mock(Sessions.class);
        memberships = mock(Memberships.class);
        Roles roles = mock(Roles.class);
        audit = mock(AuditClient.class);
        PasswordService passwords = new PasswordService(properties);
        SigningKeyStore store = new SigningKeyStore(
                new TestKeys.Table().repository, new EnvelopeEncryptionService(properties), properties);
        auth = new AuthService(users, sessions, memberships, roles, passwords, new TokenService(properties, store), properties, audit);

        user = new User();
        user.setEmail("maya@example.com");
        user.setDisplayName("Maya");
        user.setPasswordHash(passwords.hash(PASSWORD));
        user.setStatus("active");
        userId = user.getId().toString();
        when(users.findByEmail("maya@example.com")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        Role role = new Role();
        role.setName("manager");
        role.setPermissions(Set.of("agent:read"));
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(memberships.findByUserIdAndStatus(user.getId(), "active")).thenReturn(List.of(membership(ORG, role)));

        when(sessions.save(any(Session.class))).thenAnswer(call -> {
            Session saved = call.getArgument(0);
            sessionRows.put(saved.getId(), saved);
            return saved;
        });
        when(sessions.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(sessionRows.get(call.getArgument(0, UUID.class))));
        when(sessions.findByRefreshTokenHash(anyString())).thenAnswer(call -> sessionRows.values().stream()
                .filter(session -> session.getRefreshTokenHash().equals(call.getArgument(0)))
                .findFirst());
    }

    private Membership membership(UUID org, Role role) {
        Membership membership = new Membership();
        membership.setUserId(user.getId());
        membership.setOrgId(org);
        membership.setRoleId(role.getId());
        return membership;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailOf(ArgumentCaptor<Map<String, Object>> captor, int index) {
        return captor.getAllValues().get(index);
    }

    @Test
    @DisplayName("a successful sign-in is recorded for the person, in their workspace, with where it came from")
    void successfulSignIn() {
        auth.signIn("maya@example.com", PASSWORD, null, "Firefox", "203.0.113.9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("ip", "203.0.113.9").containsEntry("userAgent", "Firefox");
        assertThat(detail.getValue()).doesNotContainKey("reason");
    }

    @Test
    @DisplayName("a wrong password is recorded as failed with the running count, and the password is never recorded")
    void wrongPassword() {
        assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", "198.51.100.4"))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("failed"), detail.capture());
        assertThat(detail.getValue())
                .containsEntry("reason", "wrong_password")
                .containsEntry("failedAttempts", 1)
                .containsEntry("ip", "198.51.100.4");
        assertThat(detail.getValue().toString()).doesNotContain("wrong password here");
    }

    @Test
    @DisplayName("the attempt that locks the account is recorded as locked, and so is every attempt while it is")
    void lockout() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", null))
                    .isInstanceOf(ApiException.class);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(2)).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("failed"), any());
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("locked"), detail.capture());
        assertThat(detail.getValue())
                .containsEntry("reason", "too_many_failed_attempts")
                .containsEntry("failedAttempts", 3)
                .containsKey("lockedUntil");

        // The right password is refused while locked, and the log says why.
        assertThatThrownBy(() -> auth.signIn("maya@example.com", PASSWORD, null, "ua", null))
                .isInstanceOf(ApiException.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> refused = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(2)).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("locked"), refused.capture());
        assertThat(refused.getValue()).containsEntry("reason", "account_locked");
    }

    @Test
    @DisplayName("a disabled account's sign-in is recorded as denied")
    void disabledAccount() {
        user.setStatus("suspended");

        assertThatThrownBy(() -> auth.signIn("maya@example.com", PASSWORD, null, "ua", null))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("denied"), detail.capture());
        assertThat(detail.getValue()).containsEntry("reason", "account_disabled");
    }

    @Test
    @DisplayName("a failure for an address nobody holds goes to the platform log, with a fingerprint and not the address")
    void unknownAddress() {
        assertThatThrownBy(() -> auth.signIn("nobody@example.com", "whatever password", null, "ua", "203.0.113.50"))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                isNull(UUID.class),
                eq("anonymous"),
                eq("ANONYMOUS"),
                isNull(),
                eq("auth.sign_in"),
                eq("user"),
                isNull(),
                eq("failed"),
                detail.capture());
        assertThat(detail.getValue()).containsEntry("reason", "unknown_address");
        assertThat(detail.getValue().get("addressFingerprint")).isEqualTo(AuthService.fingerprint("NOBODY@example.com "));
        assertThat(detail.getValue().toString()).doesNotContain("nobody@example.com").doesNotContain("whatever password");
        verify(audit, times(1)).record(any(), anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a person in several workspaces has the event written to each; in none, to the platform log")
    void everyWorkspace() {
        Role role = new Role();
        role.setName("viewer");
        when(memberships.findByUserIdAndStatus(user.getId(), "active"))
                .thenReturn(List.of(membership(ORG, role), membership(OTHER_ORG, role)));

        assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", null))
                .isInstanceOf(ApiException.class);

        verify(audit).record(eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("failed"), any());
        verify(audit).record(eq(OTHER_ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("failed"), any());

        when(memberships.findByUserIdAndStatus(user.getId(), "active")).thenReturn(List.of());
        assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", null))
                .isInstanceOf(ApiException.class);
        verify(audit).record(isNull(UUID.class), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("failed"), any());
    }

    @Test
    @DisplayName("asking to enter a workspace the person is not in is recorded as denied")
    void workspaceNotAvailable() {
        assertThatThrownBy(() -> auth.signIn("maya@example.com", PASSWORD, OTHER_ORG, "ua", null))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.sign_in"), eq("user"), eq(userId), eq("denied"), detail.capture());
        assertThat(detail.getValue()).containsEntry("reason", "workspace_not_available");
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any(), eq("succeeded"), any());
    }

    @Test
    @DisplayName("changing the password is recorded with how many other devices it signed out, never the password")
    void passwordChange() {
        auth.signIn("maya@example.com", PASSWORD, null, "laptop", null);
        Session current = sessionRows.values().iterator().next();
        when(sessions.revokeAllForUserExcept(eq(user.getId()), eq(current.getFamilyId()), any(), anyString())).thenReturn(2);

        auth.changePassword(user.getId(), current.getId().toString(), PASSWORD, "a much longer new passphrase");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.password_change"), eq("user"), eq(userId), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("revokedSessions", 2);
        assertThat(detail.getValue().toString()).doesNotContain("passphrase").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a wrong current password is recorded as a failed password change")
    void passwordChangeRefused() {
        assertThatThrownBy(() -> auth.changePassword(user.getId(), null, "not my password", "a much longer new passphrase"))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("auth.password_change"), eq("user"), eq(userId), eq("failed"), detail.capture());
        assertThat(detail.getValue()).containsEntry("reason", "current_password_incorrect");
    }

    @Test
    @DisplayName("signing out everywhere is recorded; signing out one device is not")
    void signOutEverywhere() {
        AuthService.AuthResult laptop = auth.signIn("maya@example.com", PASSWORD, null, "laptop", null);
        when(sessions.revokeAllForUser(eq(user.getId()), any(), anyString())).thenReturn(3);

        auth.signOut(laptop.refreshToken(), false);
        verify(audit, never()).record(any(), any(), any(), any(), eq("session.revoke_all"), any(), any(), any(), any());

        auth.signOut(laptop.refreshToken(), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("session.revoke_all"), eq("session"), eq(userId), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("revokedSessions", 3);
    }

    @Test
    @DisplayName("a reused refresh token is recorded as denied, with how many sessions it ended")
    void reuseDetected() {
        AuthService.AuthResult signedIn = auth.signIn("maya@example.com", PASSWORD, null, "ua", null);
        auth.refresh(signedIn.refreshToken(), null, "ua", null);
        when(sessions.revokeFamily(any(), any(), anyString())).thenReturn(2);

        assertThatThrownBy(() -> auth.refresh(signedIn.refreshToken(), null, "ua", null))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq(ORG), eq(userId), eq("USER"), isNull(), eq("session.reuse_detected"), eq("session"), any(), eq("denied"), detail.capture());
        assertThat(detail.getValue()).containsEntry("revokedSessions", 2);
    }
}
