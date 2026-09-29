package os.aiworkforce.identity.service;

import java.time.Instant;
import java.util.List;
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
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Registration, sign in, refresh and sign out.
 *
 * <p>Two principles run through the whole class. First, a failed sign-in reveals nothing about
 * which accounts exist: a wrong password, an unknown address and a federated-only account all
 * produce the same response after comparable work. Second, a refresh token is single use, and
 * seeing one twice is treated as theft rather than as a client bug.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Users users;
    private final Sessions sessions;
    private final Memberships memberships;
    private final Roles roles;
    private final PasswordService passwords;
    private final TokenService tokens;
    private final PlatformProperties.Security config;

    public AuthService(
            Users users,
            Sessions sessions,
            Memberships memberships,
            Roles roles,
            PasswordService passwords,
            TokenService tokens,
            PlatformProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.memberships = memberships;
        this.roles = roles;
        this.passwords = passwords;
        this.tokens = tokens;
        this.config = properties.security();
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

    @Transactional
    public User register(String email, String displayName, String rawPassword) {
        String normalised = email == null ? "" : email.strip();
        if (users.existsByEmail(normalised)) {
            // The address is already taken, and saying so out loud turns registration into an
            // account-enumeration oracle. The caller is told to sign in or reset instead.
            throw new ApiException(
                    ErrorCode.ALREADY_EXISTS, "If that address can be used, you will receive an email shortly.");
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

    @Transactional
    public AuthResult signIn(String email, String rawPassword, UUID requestedOrgId, String userAgent) {
        Optional<User> found = users.findByEmail(email == null ? "" : email.strip());

        if (found.isEmpty()) {
            // Hash anyway, so an unknown address takes about as long as a wrong password.
            passwords.wasteTime();
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();

        if (user.isLocked()) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED)
                    .with("until", user.getLockedUntil().toString());
        }
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!passwords.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin(config.maxFailedLogins(), config.lockoutDuration());
            users.save(user);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (config.requireEmailVerification() && !user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // Re-hash on sign-in when the cost parameters have been raised: it is the only moment the
        // password is available in clear, so the alternative is never upgrading it at all.
        if (passwords.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwords.hash(rawPassword));
        }

        user.recordSuccessfulLogin();
        users.save(user);

        return issue(user, requestedOrgId, UUID.randomUUID(), null, userAgent);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>The token is retired as it is used. Seeing a retired token again means a copy exists
     * somewhere it should not, so the entire family is revoked - the legitimate session as well as
     * the stolen one. Ending one person's session is a small price for closing a window that would
     * otherwise stay open for the full thirty days.
     */
    @Transactional
    public AuthResult refresh(String refreshToken, UUID requestedOrgId, String userAgent) {
        String hash = tokens.hashRefreshToken(refreshToken);
        Session session =
                sessions.findByRefreshTokenHash(hash).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        if (session.isReplayed()) {
            int revoked = sessions.revokeFamily(session.getFamilyId(), Instant.now(), "refresh token reused");
            log.warn(
                    "Refresh token reuse detected for user {}; revoked {} session(s) in family {}",
                    session.getUserId(),
                    revoked,
                    session.getFamilyId());
            throw new ApiException(ErrorCode.SESSION_REUSE_DETECTED);
        }
        if (!session.isUsable()) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }

        session.markUsed();
        sessions.save(session);

        User user = users.findById(session.getUserId()).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        if (!user.isActive()) {
            sessions.revokeFamily(session.getFamilyId(), Instant.now(), "account is no longer active");
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        UUID orgId = requestedOrgId != null ? requestedOrgId : session.getOrgId();
        return issue(user, orgId, session.getFamilyId(), session.getId(), userAgent);
    }

    @Transactional
    public void signOut(String refreshToken, boolean allDevices) {
        String hash = tokens.hashRefreshToken(refreshToken);
        sessions.findByRefreshTokenHash(hash).ifPresent(session -> {
            if (allDevices) {
                sessions.revokeFamily(session.getFamilyId(), Instant.now(), "signed out everywhere");
            } else {
                session.revoke("signed out");
                sessions.save(session);
            }
        });
        // A token that does not exist produces the same empty success: a caller must not be able
        // to probe which tokens are live.
    }

    /**
     * Builds the token pair for a user in a workspace.
     *
     * <p>The permission set is read from the role at this moment rather than carried forward from
     * a previous token, so a refresh always picks up a role change that has happened since.
     */
    private AuthResult issue(User user, UUID requestedOrgId, UUID familyId, UUID previousId, String userAgent) {
        UUID orgId = requestedOrgId;
        UUID roleId = null;
        String roleName = null;
        Set<String> permissions = Set.of();
        long permissionVersion = 0;

        List<Membership> active = memberships.findByUserIdAndStatus(user.getId(), "active");
        if (orgId == null && active.size() == 1) {
            // With exactly one workspace, choosing it is unambiguous and saves a round trip.
            orgId = active.get(0).getOrgId();
        }

        if (orgId != null) {
            final UUID scope = orgId;
            Membership membership = active.stream()
                    .filter(m -> m.getOrgId().equals(scope))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.MEMBERSHIP_INACTIVE));
            Role role = roles.findById(membership.getRoleId())
                    .orElseThrow(() -> new ApiException(ErrorCode.PERMISSION_DENIED));
            roleId = role.getId();
            roleName = role.getName();
            permissions = Set.copyOf(role.getPermissions());
            permissionVersion = role.getPermissionVersion();
        }

        Session session = new Session();
        session.setId(UuidV7.generate());
        session.setUserId(user.getId());
        session.setFamilyId(familyId);
        session.setPreviousId(previousId);
        session.setOrgId(orgId);
        session.setUserAgent(truncate(userAgent));
        session.setExpiresAt(Instant.now().plus(config.refreshTokenTtl()));

        String refreshToken = tokens.generateRefreshToken();
        session.setRefreshTokenHash(tokens.hashRefreshToken(refreshToken));
        sessions.save(session);

        TokenService.IssuedToken access =
                tokens.issueAccessToken(user.getId(), orgId, roleId, permissions, permissionVersion, session.getId());

        // The interface needs to say who is signed in. Without the name in the session it fell
        // back to a hard-coded one, so every demo account appeared as the same person.
        return new AuthResult(
                access.token(),
                refreshToken,
                access.expiresAt(),
                user.getId(),
                orgId,
                permissions,
                user.getDisplayName(),
                user.getEmail(),
                roleName);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 400 ? value : value.substring(0, 400);
    }
}
