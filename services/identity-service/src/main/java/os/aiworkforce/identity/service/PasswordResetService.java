// @find: password reset, reset password, forgot password, reset link, admin creates reset link, redeem link, POST /api/auth/password-reset, PasswordResetService
// @what: Creates and redeems single-use password reset links issued by workspace administrators.
// @flow: Called by PasswordController.createResetLink and AuthController.resetPassword.
package os.aiworkforce.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import os.aiworkforce.platform.rbac.Permission;

/**
 * Password reset by a link an administrator creates and passes on.
 *
 * <p>No email leaves this platform, so "forgot password" cannot be self-service yet. Instead an
 * administrator creates a link, copies it, and gives it to the person however they already reach
 * them - the same shape as an invitation. The token is random, shown once, stored only as its
 * SHA-256 (as {@code InvitationService} stores invitations), single use, and valid for thirty
 * minutes.
 *
 * <p>Accounts are global and one person can belong to several workspaces, so the administrator of
 * one workspace must not be able to take over an account that also reaches another. A link can be
 * created only for someone whose one active membership is in the administrator's workspace, and
 * only an owner can create one for an owner.
 *
 * <p>The same rules are checked again when the link is redeemed, against the memberships and roles
 * as they are then. Thirty minutes is long enough for the person to accept an invitation to
 * another workspace or be made an owner, and for the administrator who issued the link to be
 * removed or lose the right to manage members. A link that no longer passes is closed and refused
 * in the same words as a used one.
 *
 * <p>Identity has no writer for the platform audit trail yet, so issuing, redeeming and refusing a
 * link each write one structured log line (event, issuer, target, workspace, token id) at INFO or
 * WARN, which is what an operator searches until those events reach the audit trail.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final Duration LINK_TTL = Duration.ofMinutes(30);

    /** The same words whether the link never existed, was used, or ran out: all mean "ask again". */
    static final String LINK_UNUSABLE =
            "This reset link has expired or has already been used. Ask your workspace administrator for a new one.";

    private final PasswordResetTokens tokens;
    private final Users users;
    private final Memberships memberships;
    private final Roles roles;
    private final Sessions sessions;
    private final PasswordService passwords;
    private final GrantGuard grants;

    public PasswordResetService(
            PasswordResetTokens tokens,
            Users users,
            Memberships memberships,
            Roles roles,
            Sessions sessions,
            PasswordService passwords,
            GrantGuard grants) {
        this.tokens = tokens;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.sessions = sessions;
        this.passwords = passwords;
        this.grants = grants;
    }

    /**
     * @param url the path to give the person, relative to the console's own address
     * @param expiresAt when the link stops working
     */
    public record ResetLink(String url, Instant expiresAt) {}

    // @find: create reset link, admin reset password, POST /api/users/{userId}/password-reset-link
    /**
     * Creates a reset link for a member of the caller's workspace.
     *
     * <p>Creating one closes any earlier link for the same person, so only the newest works.
     */
    @Transactional
    public ResetLink createLink(Actor caller, UUID targetUserId) {
        if (caller.kind() != Actor.Kind.USER || caller.orgId() == null) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only a person signed in to a workspace can create a reset link.");
        }
        UUID orgId = UUID.fromString(caller.orgId());
        if (targetUserId.toString().equals(caller.id())) {
            // A token with member:update would otherwise be enough to replace its own holder's
            // password without knowing it, which is exactly what the profile form refuses.
            throw new ApiException(ErrorCode.SELF_ACTION_FORBIDDEN, "Change your own password from your profile instead.");
        }

        List<Membership> active = memberships.findByUserIdAndStatus(targetUserId, "active");
        Membership here = active.stream()
                .filter(membership -> membership.getOrgId().equals(orgId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("member", targetUserId));
        if (active.size() > 1) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED,
                    "This person also belongs to another workspace, so their password cannot be reset from here."
                            + " Ask the person who runs this platform to reset it.");
        }

        Optional<Role> owner = roles.findSystemRole("owner");
        boolean targetIsOwner = owner.map(role -> role.getId().equals(here.getRoleId())).orElse(false);
        boolean callerIsOwner = owner.map(role -> role.getId().toString().equals(caller.roleId())).orElse(false);
        if (targetIsOwner && !callerIsOwner) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only an owner can create a reset link for another owner.");
        }
        // Setting someone's password is taking over their account, so it follows the rule for
        // changing their role: only someone who holds everything the person's role carries.
        grants.assertCanManage(grants.caller(caller), here);

        User user = users.findById(targetUserId).orElseThrow(() -> ApiException.notFound("member", targetUserId));

        Instant now = Instant.now();
        tokens.closeOpenFor(user.getId(), now);

        String raw = generateToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setOrgId(orgId);
        token.setIssuedBy(UUID.fromString(caller.id()));
        token.setTokenHash(hashToken(raw));
        token.setExpiresAt(now.plus(LINK_TTL));
        tokens.save(token);

        audit("password_reset.link_issued", token, "succeeded", null);
        return new ResetLink("/sign-in?reset=" + raw, token.getExpiresAt());
    }

    // @find: redeem reset link, set new password from link, POST /api/auth/password-reset
    /**
     * Sets a new password from a reset link.
     *
     * <p>Clears any lockout, because the person locked out is usually the one asking for the link,
     * and signs out every session the account has: whoever prompted the reset may be holding one.
     *
     * <p>Does not roll back on a refusal: a link refused because the rules no longer allow it is
     * closed by that refusal, and the closure must stay closed.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void redeem(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.validation("token", "must not be empty");
        }
        PasswordResetToken token = tokens.findByTokenHash(hashToken(rawToken.strip()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, LINK_UNUSABLE).with("resource", "password_reset"));
        Instant now = Instant.now();
        if (token.isUsed() || token.isExpiredAt(now)) {
            throw ApiException.conflict(LINK_UNUSABLE);
        }
        Optional<String> refusal = noLongerAllowed(token);
        if (refusal.isPresent()) {
            tokens.claim(token.getId(), now);
            audit("password_reset.refused", token, "denied", refusal.get());
            throw ApiException.conflict(LINK_UNUSABLE);
        }

        // Hashed before the link is claimed: a password that is too short is refused with the
        // link still good, so the person can simply try again.
        String hash = passwords.hash(newPassword);
        if (tokens.claim(token.getId(), now) == 0) {
            throw ApiException.conflict(LINK_UNUSABLE);
        }

        User user = users.findById(token.getUserId()).orElseThrow(() -> ApiException.conflict(LINK_UNUSABLE));
        user.changePassword(hash);
        user.clearLockout();
        users.save(user);

        int revoked = sessions.revokeAllForUser(user.getId(), now, "password reset");
        audit("password_reset.redeemed", token, "succeeded", "revoked " + revoked + " session(s)");
    }

    /**
     * Why a link issued earlier must not be honoured now, or empty when it still may be.
     *
     * <p>The rules {@link #createLink} applies, against the memberships and roles as they are at
     * redemption rather than as they were when the link was issued.
     */
    private Optional<String> noLongerAllowed(PasswordResetToken token) {
        UUID orgId = token.getOrgId();
        UUID issuerId = token.getIssuedBy();
        if (orgId == null || issuerId == null) {
            return Optional.of("the link does not record its workspace and issuer");
        }

        List<Membership> active = memberships.findByUserIdAndStatus(token.getUserId(), "active");
        if (active.size() != 1 || !active.getFirst().getOrgId().equals(orgId)) {
            return Optional.of("the person no longer belongs to the issuing workspace alone");
        }

        if (users.findById(issuerId).filter(User::isActive).isEmpty()) {
            return Optional.of("the issuer's account is no longer active");
        }
        Optional<Role> issuerRole = memberships.findActive(issuerId, orgId)
                .flatMap(membership -> roles.findById(membership.getRoleId()));
        if (issuerRole.isEmpty() || !issuerRole.get().getPermissions().contains(Permission.Codes.MEMBER_UPDATE)) {
            return Optional.of("the issuer can no longer manage members of the workspace");
        }

        Optional<Role> owner = roles.findSystemRole("owner");
        boolean targetIsOwner = owner.map(role -> role.getId().equals(active.getFirst().getRoleId())).orElse(false);
        boolean issuerIsOwner = owner.map(role -> role.getId().equals(issuerRole.get().getId())).orElse(false);
        if (targetIsOwner && !issuerIsOwner) {
            return Optional.of("the person is now an owner and the issuer is not");
        }
        return Optional.empty();
    }

    /**
     * One structured line per reset event, standing in for an audit-trail entry until identity has
     * a writer for one. The fields are logged as key-value pairs for the JSON encoder and repeated
     * in the message for a plain console.
     */
    private static void audit(String event, PasswordResetToken token, String outcome, String reason) {
        var line = "denied".equals(outcome) ? log.atWarn() : log.atInfo();
        line.addKeyValue("event", event)
                .addKeyValue("outcome", outcome)
                .addKeyValue("issuer", token.getIssuedBy())
                .addKeyValue("target", token.getUserId())
                .addKeyValue("org", token.getOrgId())
                .addKeyValue("tokenId", token.getId())
                .addKeyValue("reason", reason == null ? "-" : reason)
                .log(
                        "event={} outcome={} issuer={} target={} org={} tokenId={} reason={}",
                        event,
                        outcome,
                        token.getIssuedBy(),
                        token.getUserId(),
                        token.getOrgId(),
                        token.getId(),
                        reason == null ? "-" : '"' + reason + '"');
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the raw token. Fast by design: the token is already high-entropy random. */
    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }
}
