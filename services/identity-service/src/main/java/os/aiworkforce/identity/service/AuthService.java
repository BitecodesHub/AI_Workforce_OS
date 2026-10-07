package os.aiworkforce.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.audit.AuditClient;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Registration, sign in, refresh and sign out.
 *
 * <p>Two principles run through the whole class. First, a failed sign-in reveals nothing about
 * which accounts exist or which are locked: a wrong password, an unknown address, a locked or
 * disabled account and a federated-only account all produce the same response after comparable
 * work. Second, a refresh token is single use, and seeing one twice is treated as theft rather
 * than as a client bug.
 *
 * <p>Sign-in and refresh do not roll back on the failures they raise deliberately. A failed
 * password must leave its count behind, and a reused refresh token must leave its family revoked,
 * or the lockout and the theft response undo themselves the moment they fire. Each method is
 * ordered so that every check able to refuse a legitimate request runs before anything is
 * written; what is left to fail after a write is only the failure that write was for.
 *
 * <p>Every sign-in, successful or not, is written to the audit log in the same transaction that
 * records its effect on the account, so the entries survive the refusal exactly as the failed-
 * password count does. A person's account is global but their audit trail is a workspace's, so an
 * event about a person is written to every workspace they belong to - an administrator sees their
 * own members' failed and locked sign-ins - and to the platform's own log when they belong to none.
 * A failed attempt at an address nobody holds belongs to no workspace at all; it is written to the
 * platform log with a fingerprint of the address, never the address itself, since what a person
 * typed in that box is sometimes a password.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * The one refusal a sign-in gives, whatever the reason. Naming the lockout separately would
     * tell a guesser that the address exists and that the last guess was close to the limit.
     */
    static final String SIGN_IN_REFUSED =
            "Those details did not work, or there have been too many attempts. Try again later.";

    private final Users users;
    private final Sessions sessions;
    private final Memberships memberships;
    private final Roles roles;
    private final PasswordService passwords;
    private final TokenService tokens;
    private final PlatformProperties.Security config;
    private final AuditClient audit;

    public AuthService(
            Users users,
            Sessions sessions,
            Memberships memberships,
            Roles roles,
            PasswordService passwords,
            TokenService tokens,
            PlatformProperties properties,
            AuditClient audit) {
        this.users = users;
        this.sessions = sessions;
        this.memberships = memberships;
        this.roles = roles;
        this.passwords = passwords;
        this.tokens = tokens;
        this.config = properties.security();
        this.audit = audit;
    }

    /**
     * @param accessToken short-lived bearer token
     * @param refreshToken single-use token, returned once and never stored in clear
     * @param expiresAt when the access token stops being accepted
     * @param userId the signed-in account
     * @param orgId the workspace in scope, which may be null before one is chosen
     * @param permissions what this session may do, for the interface to render against
     */
    public record AuthResult(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            UUID userId,
            UUID orgId,
            Set<String> permissions,
            String displayName,
            String email,
            String roleName) {}

    /**
     * One signed-in device: the live link of one refresh-token family.
     *
     * @param familyId what to pass to end this session
     * @param issuedAt when the session last renewed, which is when the device was last active
     * @param signedInAt when the family began, which is when the device signed in
     * @param current whether this is the session the caller is using now
     */
    public record SessionSummary(
            UUID familyId,
            String userAgent,
            String ipAddress,
            Instant issuedAt,
            Instant signedInAt,
            Instant expiresAt,
            boolean current) {}

    /** The workspace a token is issued for, and what the role held there allows. */
    private record Scope(UUID orgId, UUID roleId, String roleName, Set<String> permissions, long permissionVersion) {

        static final Scope NONE = new Scope(null, null, null, Set.of(), 0);
    }

    @Transactional
    public User register(String email, String displayName, String rawPassword) {
        String normalised = email == null ? "" : email.strip();
        if (users.existsByEmail(normalised)) {
            // Still a 409: accepting an invitation relies on it to tell an existing account from a
            // new one. The sentence promises nothing this platform does not do - there is no
            // outbound email - and points at the two ways back in that do exist.
            throw new ApiException(
                    ErrorCode.ALREADY_EXISTS,
                    "That email address cannot be used for a new account. If it is yours, sign in, or ask your"
                            + " workspace administrator for a password reset link.");
        }
        User user = new User();
        user.setEmail(normalised);
        user.setDisplayName(displayName == null || displayName.isBlank() ? normalised : displayName.strip());
        user.setPasswordHash(passwords.hash(rawPassword));
        user.setStatus("active");
        if (!config.requireEmailVerification()) {
            user.setEmailVerifiedAt(Instant.now());
        }
        return users.save(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult signIn(
            String email, String rawPassword, UUID requestedOrgId, String userAgent, String ipAddress) {
        Optional<User> found = users.findByEmail(email == null ? "" : email.strip());

        if (found.isEmpty()) {
            // Hash anyway, so an unknown address takes about as long as a wrong password.
            passwords.wasteTime();
            auditUnknownAddress(email, userAgent, ipAddress);
            throw refused();
        }
        User user = found.get();

        // Checked before the password and refused in the same words as a wrong one. Checking the
        // password first would turn a locked account into an oracle: the right guess would say
        // "locked" and a wrong one "incorrect", and the lockout would stop nothing. The audit log
        // is where the difference is recorded, for the people allowed to read it.
        if (user.isLocked() || !user.isActive()) {
            passwords.wasteTime();
            if (user.isLocked()) {
                auditSignIn(user, "locked", "account_locked", userAgent, ipAddress);
            } else {
                auditSignIn(user, "denied", "account_disabled", userAgent, ipAddress);
            }
            throw refused();
        }
        if (!passwords.matches(rawPassword, user.getPasswordHash())) {
            // Committed despite the exception (noRollbackFor), or the count never reaches the limit.
            user.recordFailedLogin(config.maxFailedLogins(), config.lockoutDuration());
            users.save(user);
            Map<String, Object> detail = signInDetail(
                    user.isLocked() ? "too_many_failed_attempts" : "wrong_password", userAgent, ipAddress);
            detail.put("failedAttempts", user.getFailedLoginCount());
            if (user.isLocked()) {
                detail.put("lockedUntil", user.getLockedUntil().toString());
            }
            auditAuthentication(
                    user.getId(),
                    "auth.sign_in",
                    "user",
                    user.getId().toString(),
                    user.isLocked() ? "locked" : "failed",
                    detail);
            throw refused();
        }
        if (config.requireEmailVerification() && !user.isEmailVerified()) {
            auditSignIn(user, "denied", "email_not_verified", userAgent, ipAddress);
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        Scope scope;
        try {
            scope = resolveScope(user, requestedOrgId);
        } catch (ApiException refusal) {
            auditSignIn(user, "denied", "workspace_not_available", userAgent, ipAddress);
            throw refusal;
        }

        // Re-hash on sign-in when the cost parameters have been raised: it is the only moment the
        // password is available in clear, so the alternative is never upgrading it at all. Not a
        // change of password, so password_changed_at is left alone.
        if (passwords.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwords.hash(rawPassword));
        }

        user.recordSuccessfulLogin();
        users.save(user);

        AuthResult result = issue(user, scope, UUID.randomUUID(), null, userAgent, ipAddress);
        auditSignIn(user, "succeeded", null, userAgent, ipAddress);
        return result;
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>The token is retired as it is used. Seeing a retired token again means a copy exists
     * somewhere it should not, so the entire family is revoked - the legitimate session as well as
     * the stolen one. Ending one person's session is a small price for closing a window that would
     * otherwise stay open for the full thirty days.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String refreshToken, UUID requestedOrgId, String userAgent, String ipAddress) {
        String hash = tokens.hashRefreshToken(refreshToken);
        Session session =
                sessions.findByRefreshTokenHash(hash).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        if (session.isReplayed()) {
            // Committed despite the exception (noRollbackFor): a revocation that rolled back with
            // the refusal would leave the stolen copy working.
            int revoked = sessions.revokeFamily(session.getFamilyId(), Instant.now(), "refresh token reused");
            log.warn(
                    "Refresh token reuse detected for user {}; revoked {} session(s) in family {}",
                    session.getUserId(),
                    revoked,
                    session.getFamilyId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("revokedSessions", revoked);
            auditAuthentication(
                    session.getUserId(),
                    "session.reuse_detected",
                    "session",
                    session.getFamilyId().toString(),
                    "denied",
                    detail);
            throw new ApiException(ErrorCode.SESSION_REUSE_DETECTED);
        }
        if (!session.isUsable()) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }

        User user = users.findById(session.getUserId()).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        if (!user.isActive()) {
            sessions.revokeFamily(session.getFamilyId(), Instant.now(), "account is no longer active");
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        // Resolved before the presented token is retired. A request for a workspace the person
        // cannot enter is refused with their token still good, so choosing the wrong workspace
        // costs a message rather than the session.
        UUID orgId = requestedOrgId != null ? requestedOrgId : session.getOrgId();
        Scope scope = resolveScope(user, orgId);

        AuthResult result = issue(user, scope, session.getFamilyId(), session.getId(), userAgent, ipAddress);
        session.markUsed();
        sessions.save(session);
        return result;
    }

    @Transactional
    public void signOut(String refreshToken, boolean allDevices) {
        String hash = tokens.hashRefreshToken(refreshToken);
        sessions.findByRefreshTokenHash(hash).ifPresent(session -> {
            if (allDevices) {
                // Every family the person has, not only this one: each sign-in starts its own
                // family, so revoking the presented family reached this device alone.
                int revoked =
                        sessions.revokeAllForUser(session.getUserId(), Instant.now(), "signed out everywhere");
                log.info("User {} signed out everywhere; revoked {} session(s)", session.getUserId(), revoked);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("revokedSessions", revoked);
                auditAuthentication(
                        session.getUserId(),
                        "session.revoke_all",
                        "session",
                        session.getUserId().toString(),
                        "succeeded",
                        detail);
            } else {
                session.revoke("signed out");
                sessions.save(session);
            }
        });
        // A token that does not exist produces the same empty success: a caller must not be able
        // to probe which tokens are live.
    }

    /**
     * The devices a person is signed in on, newest activity first.
     *
     * @param currentSessionId the {@code sid} of the caller's access token, to mark their own device
     */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions(UUID userId, String currentSessionId) {
        List<Session> live = sessions.findLiveByUserId(userId, Instant.now());
        if (live.isEmpty()) {
            return List.of();
        }
        UUID currentFamily = familyOf(currentSessionId);
        Map<UUID, Instant> started = new HashMap<>();
        for (Object[] row : sessions.findFamilyStarts(
                userId, live.stream().map(Session::getFamilyId).toList())) {
            started.put((UUID) row[0], (Instant) row[1]);
        }
        return live.stream()
                .map(session -> new SessionSummary(
                        session.getFamilyId(),
                        session.getUserAgent(),
                        session.getIpAddress(),
                        session.getIssuedAt(),
                        started.getOrDefault(session.getFamilyId(), session.getIssuedAt()),
                        session.getExpiresAt(),
                        session.getFamilyId().equals(currentFamily)))
                .toList();
    }

    /** Signs one of a person's devices out. A family that is not theirs is reported as not found. */
    @Transactional
    public void endSession(UUID userId, UUID familyId) {
        if (!sessions.existsByFamilyIdAndUserId(familyId, userId)) {
            throw ApiException.notFound("session", familyId);
        }
        sessions.revokeFamily(familyId, Instant.now(), "signed out from another device");
    }

    /**
     * Changes a signed-in person's password, and signs out every other device.
     *
     * <p>The current password is required even though the caller holds a valid token: a token
     * left open on a shared screen must not be enough to take the account over. A wrong current
     * password counts towards the same lockout as a wrong sign-in, and is not rolled back, so
     * this form cannot be used to guess a password faster than the sign-in form can.
     *
     * <p>The device making the change stays signed in; every other session is revoked, because
     * changing a password is what a person does when they think somebody else has it.
     *
     * @param currentSessionId the {@code sid} of the caller's access token
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(UUID userId, String currentSessionId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_AUTHENTICATED));
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (user.isLocked()) {
            passwords.wasteTime();
            auditAuthentication(
                    userId, "auth.password_change", "user", userId.toString(), "locked", reason("account_locked"));
            throw ApiException.validation("currentPassword", "could not be checked after too many attempts; try again later");
        }
        if (!passwords.matches(currentPassword, user.getPasswordHash())) {
            user.recordFailedLogin(config.maxFailedLogins(), config.lockoutDuration());
            users.save(user);
            auditAuthentication(
                    userId,
                    "auth.password_change",
                    "user",
                    userId.toString(),
                    user.isLocked() ? "locked" : "failed",
                    reason(user.isLocked() ? "too_many_failed_attempts" : "current_password_incorrect"));
            throw ApiException.validation("currentPassword", "is not correct");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.validation("newPassword", "must be different from the current password");
        }

        user.changePassword(passwords.hash(newPassword));
        users.save(user);

        Instant now = Instant.now();
        UUID keep = familyOf(currentSessionId);
        int revoked = keep == null
                ? sessions.revokeAllForUser(userId, now, "password changed")
                : sessions.revokeAllForUserExcept(userId, keep, now, "password changed");
        log.info("User {} changed their password; revoked {} other session(s)", userId, revoked);
        // The fact of it and what it ended; never the password, old or new.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revokedSessions", revoked);
        auditAuthentication(userId, "auth.password_change", "user", userId.toString(), "succeeded", detail);
    }

    /**
     * The workspace a token is for, and what the role held there allows.
     *
     * <p>The permission set is read from the role at this moment rather than carried forward from
     * a previous token, so a refresh always picks up a role or membership change that has
     * happened since. Writes nothing, so a refusal here costs the caller nothing.
     */
    private Scope resolveScope(User user, UUID requestedOrgId) {
        UUID orgId = requestedOrgId;
        List<Membership> active = memberships.findByUserIdAndStatus(user.getId(), "active");
        if (orgId == null && active.size() == 1) {
            // With exactly one workspace, choosing it is unambiguous and saves a round trip.
            orgId = active.get(0).getOrgId();
        }
        if (orgId == null) {
            return Scope.NONE;
        }

        final UUID scope = orgId;
        Membership membership = active.stream()
                .filter(m -> m.getOrgId().equals(scope))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBERSHIP_INACTIVE));
        Role role = roles.findById(membership.getRoleId()).orElseThrow(() -> new ApiException(ErrorCode.PERMISSION_DENIED));
        return new Scope(
                orgId, role.getId(), role.getName(), Set.copyOf(role.getPermissions()), role.getPermissionVersion());
    }

    /**
     * Builds the token pair for a user in a workspace.
     *
     * <p>The access token is signed before the session row is written, so a signing failure
     * leaves nothing half-issued behind.
     */
    private AuthResult issue(
            User user, Scope scope, UUID familyId, UUID previousId, String userAgent, String ipAddress) {
        Session session = new Session();
        session.setId(UuidV7.generate());
        session.setUserId(user.getId());
        session.setFamilyId(familyId);
        session.setPreviousId(previousId);
        session.setOrgId(scope.orgId());
        session.setUserAgent(truncate(userAgent));
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(config.refreshTokenTtl()));

        TokenService.IssuedToken access = tokens.issueAccessToken(
                user.getId(),
                scope.orgId(),
                scope.roleId(),
                scope.permissions(),
                scope.permissionVersion(),
                session.getId());

        String refreshToken = tokens.generateRefreshToken();
        session.setRefreshTokenHash(tokens.hashRefreshToken(refreshToken));
        sessions.save(session);

        // The interface needs to say who is signed in. Without the name in the session it fell
        // back to a hard-coded one, so every demo account appeared as the same person.
        return new AuthResult(
                access.token(),
                refreshToken,
                access.expiresAt(),
                user.getId(),
                scope.orgId(),
                scope.permissions(),
                user.getDisplayName(),
                user.getEmail(),
                scope.roleName());
    }

    /** The family an access token's {@code sid} belongs to, or null when it names no session. */
    private UUID familyOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessions.findById(UUID.fromString(sessionId)).map(Session::getFamilyId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ---- Audit ------------------------------------------------------------------------------ */

    private void auditSignIn(User user, String outcome, String reason, String userAgent, String ipAddress) {
        auditAuthentication(
                user.getId(),
                "auth.sign_in",
                "user",
                user.getId().toString(),
                outcome,
                signInDetail(reason, userAgent, ipAddress));
    }

    /**
     * An attempt at an address that belongs to nobody. It has no workspace, so it goes to the
     * platform's own log; the address is recorded only as a short fingerprint, because the box it
     * was typed in is one where people sometimes type a password.
     */
    private void auditUnknownAddress(String email, String userAgent, String ipAddress) {
        Map<String, Object> detail = signInDetail("unknown_address", userAgent, ipAddress);
        detail.put("addressFingerprint", fingerprint(email));
        audit.record(null, "anonymous", "ANONYMOUS", null, "auth.sign_in", "user", null, "failed", detail);
    }

    /**
     * Writes an event about a person's own authentication to every workspace they belong to, or to
     * the platform's log when they belong to none. Read inside the transaction the caller is in, so
     * a membership the same request just changed is seen as it now stands.
     */
    private void auditAuthentication(
            UUID userId, String action, String resourceType, String resourceId, String outcome, Map<String, Object> detail) {
        String actor = userId.toString();
        List<UUID> workspaces = memberships.findByUserIdAndStatus(userId, "active").stream()
                .map(Membership::getOrgId)
                .distinct()
                .toList();
        if (workspaces.isEmpty()) {
            audit.record(null, actor, "USER", null, action, resourceType, resourceId, outcome, detail);
            return;
        }
        for (UUID workspace : workspaces) {
            audit.record(workspace, actor, "USER", null, action, resourceType, resourceId, outcome, detail);
        }
    }

    private static Map<String, Object> signInDetail(String reason, String userAgent, String ipAddress) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (reason != null) {
            detail.put("reason", reason);
        }
        if (ipAddress != null) {
            detail.put("ip", ipAddress);
        }
        if (userAgent != null) {
            detail.put("userAgent", userAgent.length() <= 200 ? userAgent : userAgent.substring(0, 200));
        }
        return detail;
    }

    private static Map<String, Object> reason(String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        return detail;
    }

    /** The first sixteen hex digits of the SHA-256 of the address, lower-cased: enough to match, not to read. */
    static String fingerprint(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((email == null ? "" : email.strip().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static ApiException refused() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, SIGN_IN_REFUSED);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 400 ? value : value.substring(0, 400);
    }
}
