// @find: tests for AuthService, sign in, lockout, locked account, disabled account, sign out everywhere, refresh workspace
// @what: Unit tests of sign-in, lockout and session behaviour in AuthService.
package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;

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
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.audit.AuditClient;

/**
 * Sign-in, refresh, sign-out and password change: what they refuse, in which words, and what
 * they leave behind when they refuse.
 */
class AuthServiceTest {

    private static final String PASSWORD = "correct horse battery";
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000bb");

    private final Map<UUID, Session> sessionRows = new HashMap<>();
    private Users users;
    private Sessions sessions;
    private Memberships memberships;
    private Roles roles;
    private PasswordService passwords;
    private TokenService tokens;
    private AuthService auth;
    private User user;
    private Role role;

    @BeforeEach
    void setUp() {
        PlatformProperties properties = TestKeys.properties();
        users = mock(Users.class);
        sessions = mock(Sessions.class);
        memberships = mock(Memberships.class);
        roles = mock(Roles.class);
        passwords = new PasswordService(properties);
        SigningKeyStore store = new SigningKeyStore(
                new TestKeys.Table().repository, new EnvelopeEncryptionService(properties), properties);
        tokens = new TokenService(properties, store);
        auth = new AuthService(users, sessions, memberships, roles, passwords, tokens, properties, mock(AuditClient.class));

        user = new User();
        user.setEmail("maya@example.com");
        user.setDisplayName("Maya");
        user.setPasswordHash(passwords.hash(PASSWORD));
        user.setStatus("active");
        when(users.findByEmail("maya@example.com")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        role = new Role();
        role.setName("manager");
        role.setPermissions(Set.of("agent:read"));
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        Membership membership = new Membership();
        membership.setUserId(user.getId());
        membership.setOrgId(ORG);
        membership.setRoleId(role.getId());
        when(memberships.findByUserIdAndStatus(user.getId(), "active")).thenReturn(List.of(membership));

        when(sessions.save(any(Session.class))).thenAnswer(call -> {
            Session saved = call.getArgument(0);
            sessionRows.put(saved.getId(), saved);
            return saved;
        });
        when(sessions.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(sessionRows.get(call.getArgument(0, UUID.class))));
        when(sessions.findByRefreshTokenHash(anyString())).thenAnswer(call -> sessionRows.values().stream()
                .filter(session -> session.getRefreshTokenHash().equals(call.getArgument(0)))
                .findFirst());
    }

    @Test
    @DisplayName("a wrong password is counted, and the count survives the refusal")
    void failedLoginPersists() throws Exception {
        assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(e.getMessage()).isEqualTo(AuthService.SIGN_IN_REFUSED);
                });
        verify(users).save(user);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);

        // Spring rolls back on any runtime exception unless told otherwise, which silently
        // undid every failed-login count and every reuse revocation before this change.
        for (String name : List.of("signIn", "refresh", "changePassword")) {
            Method method = java.util.Arrays.stream(AuthService.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional.noRollbackFor()).as(name).contains(ApiException.class);
        }
    }

    @Test
    @DisplayName("a locked account is refused in the same words as a wrong password, even with the right one")
    void lockedAccountLooksLikeWrongPassword() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> auth.signIn("maya@example.com", "wrong password here", null, "ua", null))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(user.isLocked()).isTrue();

        assertThatThrownBy(() -> auth.signIn("maya@example.com", PASSWORD, null, "ua", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(e.getMessage()).isEqualTo(AuthService.SIGN_IN_REFUSED);
                    assertThat(e.details()).doesNotContainKey("until");
                });
        assertThat(sessionRows).isEmpty();
    }

    @Test
    @DisplayName("a disabled account is refused in the same words as a wrong password")
    void disabledAccountLooksLikeWrongPassword() {
        user.setStatus("suspended");
        assertThatThrownBy(() -> auth.signIn("maya@example.com", PASSWORD, null, "ua", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(e.getMessage()).isEqualTo(AuthService.SIGN_IN_REFUSED);
                });
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    @DisplayName("sign-in records where it came from, and a rehash is not a password change")
    void signInRecordsAddressWithoutStampingPasswordChange() {
        AuthService.AuthResult result = auth.signIn("maya@example.com", PASSWORD, null, "Firefox", "203.0.113.9");

        assertThat(result.orgId()).isEqualTo(ORG);
        Session session = sessionRows.values().iterator().next();
        assertThat(session.getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(session.getUserAgent()).isEqualTo("Firefox");
        assertThat(user.getPasswordChangedAt()).isNull();
    }

    @Test
    @DisplayName("sign out everywhere revokes every family the person has, not only this device's")
    void signOutEverywhere() {
        AuthService.AuthResult laptop = auth.signIn("maya@example.com", PASSWORD, null, "laptop", null);
        auth.signIn("maya@example.com", PASSWORD, null, "phone", null);

        auth.signOut(laptop.refreshToken(), true);

        verify(sessions).revokeAllForUser(eq(user.getId()), any(Instant.class), eq("signed out everywhere"));
        verify(sessions, never()).revokeFamily(any(), any(), any());
    }

    @Test
    @DisplayName("asking to enter a workspace you are not in is refused with the refresh token still good")
    void refusedWorkspaceKeepsTheSession() {
        AuthService.AuthResult signedIn = auth.signIn("maya@example.com", PASSWORD, null, "ua", null);

        assertThatThrownBy(() -> auth.refresh(signedIn.refreshToken(), OTHER_ORG, "ua", null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMBERSHIP_INACTIVE));
        Session original = sessionRows.values().iterator().next();
        assertThat(original.getUsedAt()).isNull();

        AuthService.AuthResult renewed = auth.refresh(signedIn.refreshToken(), ORG, "ua", "198.51.100.4");
        assertThat(renewed.orgId()).isEqualTo(ORG);
        assertThat(original.getUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("a reused refresh token revokes its family")
    void reuseRevokesFamily() {
        AuthService.AuthResult signedIn = auth.signIn("maya@example.com", PASSWORD, null, "ua", null);
        auth.refresh(signedIn.refreshToken(), null, "ua", null);

        assertThatThrownBy(() -> auth.refresh(signedIn.refreshToken(), null, "ua", null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_REUSE_DETECTED));
        verify(sessions).revokeFamily(any(UUID.class), any(Instant.class), eq("refresh token reused"));
    }

    @Test
    @DisplayName("changing the password keeps this device and signs out every other one")
    void changePasswordRevokesOtherSessions() {
        AuthService.AuthResult here = auth.signIn("maya@example.com", PASSWORD, null, "laptop", null);
        Session current = sessionRows.values().iterator().next();
        String sid = current.getId().toString();

        auth.changePassword(user.getId(), sid, PASSWORD, "a much longer new passphrase");

        verify(sessions).revokeAllForUserExcept(
                eq(user.getId()), eq(current.getFamilyId()), any(Instant.class), eq("password changed"));
        assertThat(passwords.matches("a much longer new passphrase", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordChangedAt()).isNotNull();
        assertThat(here.userId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("a wrong current password is refused, counted, and changes nothing")
    void changePasswordNeedsTheCurrentOne() {
        String before = user.getPasswordHash();
        assertThatThrownBy(() -> auth.changePassword(user.getId(), null, "not my password", "a much longer new passphrase"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "currentPassword");
                });
        assertThat(user.getPasswordHash()).isEqualTo(before);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        verify(sessions, never()).revokeAllForUser(any(), any(), any());
        verify(sessions, never()).revokeAllForUserExcept(any(), any(), any(), any());
    }

    @Test
    @DisplayName("lists live devices, marking the one in use")
    void listsSessions() {
        auth.signIn("maya@example.com", PASSWORD, null, "laptop", "203.0.113.9");
        Session current = sessionRows.values().iterator().next();
        Session other = new Session();
        other.setUserId(user.getId());
        other.setFamilyId(UUID.randomUUID());
        other.setUserAgent("phone");
        other.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
        other.setRefreshTokenHash("other");
        when(sessions.findLiveByUserId(eq(user.getId()), any(Instant.class))).thenReturn(List.of(current, other));
        when(sessions.findFamilyStarts(eq(user.getId()), any())).thenReturn(List.<Object[]>of(
                new Object[] {current.getFamilyId(), Instant.parse("2026-10-01T09:00:00Z")}));

        List<AuthService.SessionSummary> listed = auth.listSessions(user.getId(), current.getId().toString());

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).current()).isTrue();
        assertThat(listed.get(0).ipAddress()).isEqualTo("203.0.113.9");
        assertThat(listed.get(0).signedInAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(listed.get(1).current()).isFalse();
        assertThat(listed.get(1).signedInAt()).isEqualTo(other.getIssuedAt());
    }

    @Test
    @DisplayName("ending a session that belongs to someone else is reported as not found")
    void endSessionChecksOwnership() {
        UUID family = UUID.randomUUID();
        when(sessions.existsByFamilyIdAndUserId(family, user.getId())).thenReturn(false);
        assertThatThrownBy(() -> auth.endSession(user.getId(), family))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(sessions, never()).revokeFamily(any(), any(), any());

        when(sessions.existsByFamilyIdAndUserId(family, user.getId())).thenReturn(true);
        auth.endSession(user.getId(), family);
        ArgumentCaptor<UUID> revoked = ArgumentCaptor.forClass(UUID.class);
        verify(sessions).revokeFamily(revoked.capture(), any(Instant.class), anyString());
        assertThat(revoked.getValue()).isEqualTo(family);
    }

    @Test
    @DisplayName("registering an address in use is still a 409, and promises no email")
    void registerConflict() {
        when(users.existsByEmail("maya@example.com")).thenReturn(true);
        assertThatThrownBy(() -> auth.register("maya@example.com", "Maya", PASSWORD))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).doesNotContainIgnoringCase("email shortly");
                    assertThat(e.getMessage()).contains("reset link");
                });
    }
}
