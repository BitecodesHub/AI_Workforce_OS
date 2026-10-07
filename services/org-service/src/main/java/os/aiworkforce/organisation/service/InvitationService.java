package os.aiworkforce.organisation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.organisation.domain.Invitation;
import os.aiworkforce.organisation.repository.Invitations;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.audit.AuditClient;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Invites people to a workspace, and turns an accepted invitation into a real member.
 *
 * <p>There is no SMTP anywhere in this platform, so this does not send anything. It generates an
 * opaque token, stores only its hash - the same "never the raw value" rule as
 * {@link CredentialService} - and returns the raw token exactly once, in the create response, so
 * an administrator can copy the accept-invitation link and send it however they already reach
 * that person.
 *
 * <p>Accepting has two doors. A person new to the platform registers with identity-service and is
 * granted the invited role there, through the internal service-to-service path, the same shape
 * {@code WorkspaceController} uses to grant a workspace's creator the owner role. A person who
 * already has an account - someone removed and asked back, a consultant in two workspaces -
 * signs in first and accepts as themselves; the invitation's address must be theirs.
 *
 * <p>Identity decides every role question, since it holds the roles: whether the inviter may give
 * the role is asked when the invitation is created, and asked again when it is accepted, because
 * the inviter may have been demoted in the week between.
 */
@Service
public class InvitationService {

    /** Absent in a test that builds the service by hand; present in the running service. */
    @Autowired(required = false)
    private AuditClient audit;

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final Duration IDENTITY_TIMEOUT = Duration.ofSeconds(10);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    /**
     * The {@code reason} detail on the refusal sent when an invitation's address already has an
     * account. The client switches on it to offer "Sign in to accept" instead of the form.
     */
    public static final String ACCOUNT_EXISTS = "account_exists";

    private final Invitations invitations;
    private final WebClient identityClient;
    private final InternalTokenProvider tokens;

    public InvitationService(
            Invitations invitations,
            WebClient.Builder builder,
            PlatformProperties properties,
            InternalTokenProvider tokens) {
        this.invitations = invitations;
        this.identityClient = builder.baseUrl(properties.services().identity()).build();
        this.tokens = tokens;
    }

    /**
     * @param invitationId the row, for the pending-invitations list
     * @param token the raw, one-time secret; present only on the response to {@link #create}
     * @param acceptUrl a ready-to-copy link, present only alongside {@code token}
     */
    public record InvitationView(
            UUID invitationId,
            String email,
            String roleName,
            String status,
            Instant expiresAt,
            Instant acceptedAt,
            String token,
            String acceptUrl) {}

    public record AcceptResult(UUID userId, UUID orgId, String email, String roleName) {}

    /** What identity said about one person giving one role. */
    private record GrantCheck(boolean allowed, String reason) {}

    @Transactional
    public InvitationView create(UUID orgId, UUID invitedBy, String email, String roleName, String originForLink) {
        String normalisedEmail = normaliseEmail(email);
        if (normalisedEmail.isBlank()) {
            throw ApiException.validation("email", "must not be empty");
        }
        if (roleName == null || roleName.isBlank()) {
            throw ApiException.validation("roleName", "must not be empty");
        }
        String role = roleName.strip();

        // Asked before anything is written. A role that does not exist would otherwise be found
        // out only when the person accepts - after their account had already been created.
        GrantCheck check = checkGrant(orgId, invitedBy, role);
        if (!check.allowed()) {
            if ("unknown_role".equals(check.reason())) {
                throw ApiException.validation("roleName", "no such role is available to this workspace");
            }
            throw new ApiException(ErrorCode.PERMISSION_DENIED, refusalSentence(check.reason()))
                    .with("reason", check.reason());
        }

        // Re-inviting the same address replaces the still-open invitation rather than piling up
        // a second one nobody can tell apart from the first - the same open-invitation-per-
        // address rule the unique index in the database already enforces. It is also what the
        // console's Resend does.
        invitations
                .findByOrgIdAndEmailIgnoreCaseAndStatus(orgId, normalisedEmail, "pending")
                .ifPresent(existing -> {
                    existing.revoke();
                    invitations.saveAndFlush(existing);
                });

        String rawToken = generateToken();

        Invitation invitation = new Invitation();
        invitation.setId(UuidV7.generate());
        invitation.setOrgId(orgId);
        invitation.setEmail(normalisedEmail);
        invitation.setRoleName(role);
        invitation.setTokenHash(hashToken(rawToken));
        invitation.setInvitedBy(invitedBy);
        invitation.setStatus("pending");
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitations.save(invitation);
        recordAudit(orgId, "invitation.create", invitation, Map.of("role", role, "email", normalisedEmail));

        log.info("Invitation created for {} in workspace {} at role {}", normalisedEmail, orgId, role);

        String acceptUrl = originForLink + "/accept-invite?token=" + rawToken;
        return toView(invitation, rawToken, acceptUrl);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> list(UUID orgId) {
        return invitations.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(invitation -> toView(invitation, null, null))
                .toList();
    }

    /**
     * Withdraws an invitation, so its link stops working at once.
     *
     * <p>Withdrawing one already withdrawn changes nothing and is not an error. One that was
     * accepted cannot be withdrawn - the person is a member by then, and removing them is a
     * separate, deliberate step on the Members screen.
     */
    @Transactional
    public InvitationView revoke(UUID orgId, UUID invitationId) {
        Invitation invitation = invitations
                .findByIdAndOrgId(invitationId, orgId)
                .orElseThrow(() -> ApiException.notFound("invitation", invitationId));
        if (invitation.isAccepted()) {
            throw ApiException.conflict(
                    "This invitation was already accepted. Remove the member instead if they should not have access.");
        }
        if (!invitation.isRevoked()) {
            invitation.revoke();
            invitations.save(invitation);
            recordAudit(orgId, "invitation.revoke", invitation, Map.of("email", invitation.getEmail()));
            String actor = RequestContext.actor().map(Actor::id).orElse("unknown");
            log.info("Invitation for {} in workspace {} revoked by {}", invitation.getEmail(), orgId, actor);
        }
        return toView(invitation, null, null);
    }

    /**
     * Turns an accepted invitation into a registered account with an active membership.
     *
     * <p>Three calls to identity-service, in sequence rather than one transaction spanning two
     * services: check that the role can still be given, register the account, then grant the
     * role. The check comes first so a role that has since disappeared, or an inviter who has
     * since been demoted, refuses the invitation before an account exists.
     *
     * <p>If the grant fails after registration succeeded, the person has an account but no
     * membership yet. That is not undone - deleting someone else's just-created account from here
     * is a worse failure than the alternative - and it is recoverable: the invitation stays open,
     * and signing in and accepting it again ({@link #acceptSignedIn}) finishes the job.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AcceptResult accept(String rawToken, String displayName, String password) {
        Invitation invitation = openInvitation(rawToken);

        GrantCheck check = checkGrant(invitation.getOrgId(), invitation.getInvitedBy(), invitation.getRoleName());
        if (!check.allowed()) {
            throw cannotBeAccepted(check.reason());
        }

        UUID userId = registerAccount(invitation.getEmail(), displayName, password);
        String role = grantMembership(
                invitation,
                userId,
                "Your account was created, but joining the workspace did not finish. Sign in to accept the"
                        + " invitation again.");

        markAccepted(invitation);
        recordAccepted(invitation, userId.toString(), role);
        return new AcceptResult(userId, invitation.getOrgId(), invitation.getEmail(), role);
    }

    /**
     * Accepts an invitation as the person already signed in, without registering anything.
     *
     * <p>For an account that already exists: someone removed and invited back, someone joining a
     * second workspace, or someone whose first acceptance created the account but stopped before
     * the membership. The invitation's address must be the signed-in account's own, compared
     * without regard to case, so a forwarded link cannot place a different account in the
     * workspace.
     *
     * @param authorization the caller's own {@code Authorization} header, used once to ask identity
     *     for the account's address; never stored
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AcceptResult acceptSignedIn(String rawToken, String authorization) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() != Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Sign in as yourself to accept an invitation.");
        }
        Invitation invitation = openInvitation(rawToken);

        String accountEmail = signedInEmail(authorization);
        if (!normaliseEmail(accountEmail).equals(normaliseEmail(invitation.getEmail()))) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "This invitation was sent to a different email address. Sign in with that address to"
                                    + " accept it.")
                    .with("reason", "email_mismatch");
        }

        String role = grantMembership(
                invitation,
                UUID.fromString(actor.id()),
                "Joining the workspace did not finish. Try again in a moment.");

        markAccepted(invitation);
        recordAccepted(invitation, actor.id(), role);
        return new AcceptResult(UUID.fromString(actor.id()), invitation.getOrgId(), invitation.getEmail(), role);
    }

    /**
     * The invitation a token names, provided it can still be accepted. Each reason it cannot is
     * said in words the person can act on.
     */
    private Invitation openInvitation(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.validation("token", "must not be empty");
        }
        Invitation invitation = invitations
                .findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> ApiException.notFound("invitation", "token"));

        if (invitation.isRevoked()) {
            throw ApiException.conflict("This invitation was withdrawn. Ask whoever invited you for a new one.")
                    .with("reason", "revoked");
        }
        if (invitation.isAccepted()) {
            throw ApiException.conflict("This invitation has already been used. Sign in to open the workspace.")
                    .with("reason", "accepted");
        }
        if (!invitation.isPending()) {
            throw ApiException.conflict("This invitation has expired. Ask for a new one.")
                    .with("reason", "expired");
        }
        if (invitation.hasExpired()) {
            invitation.setStatus("expired");
            invitations.save(invitation);
            throw ApiException.conflict("This invitation has expired. Ask for a new one.")
                    .with("reason", "expired");
        }
        return invitation;
    }

    private void recordAudit(UUID orgId, String action, Invitation invitation, Map<String, Object> detail) {
        if (audit != null) {
            audit.record(action, "invitation", invitation.getId().toString(), "succeeded", detail);
        }
    }

    /** The person accepting is not signed in as anybody yet, so the event names them explicitly. */
    private void recordAccepted(Invitation invitation, String userId, String role) {
        if (audit != null) {
            audit.record(
                    invitation.getOrgId(),
                    userId,
                    "USER",
                    null,
                    "invitation.accept",
                    "invitation",
                    invitation.getId().toString(),
                    "succeeded",
                    Map.of("role", role == null ? "" : role));
        }
    }

    private void markAccepted(Invitation invitation) {
        invitation.setStatus("accepted");
        invitation.setAcceptedAt(Instant.now());
        invitations.save(invitation);
        log.info("Invitation for {} accepted into workspace {}", invitation.getEmail(), invitation.getOrgId());
    }

    /** Asks identity whether {@code invitedBy} may give {@code roleName} in the workspace now. */
    private GrantCheck checkGrant(UUID orgId, UUID invitedBy, String roleName) {
        if (invitedBy == null) {
            // Every invitation records who sent it; one that does not cannot be checked, and a
            // role nobody can vouch for is not granted.
            return new GrantCheck(false, "not_a_member");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("orgId", orgId);
        body.put("actorUserId", invitedBy);
        body.put("roleName", roleName);
        try {
            Map<String, Object> answer = identityClient
                    .post()
                    .uri("/internal/memberships/check-grant")
                    .header("Authorization", "Bearer " + tokens.forService("identity"))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .timeout(IDENTITY_TIMEOUT)
                    .block();
            if (answer == null) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The role could not be checked. Try again.");
            }
            boolean allowed = Boolean.TRUE.equals(answer.get("allowed"));
            Object reason = answer.get("reason");
            return new GrantCheck(allowed, reason == null ? null : reason.toString());
        } catch (WebClientResponseException e) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The role could not be checked. Try again.", e);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // A timeout or a refused connection. Failing closed: an unchecked role is not granted.
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The role could not be checked. Try again.", e);
        }
    }

    private UUID registerAccount(String email, String displayName, String password) {
        try {
            var response = identityClient
                    .post()
                    .uri("/api/auth/register")
                    .bodyValue(Map.of("email", email, "displayName", displayName, "password", password))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(IDENTITY_TIMEOUT)
                    .block();
            String userId = response == null ? null : response.getHeaders().getFirst("X-User-Id");
            if (userId == null) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The new account could not be created.");
            }
            return UUID.fromString(userId);
        } catch (WebClientResponseException.Conflict e) {
            // The address already has an account. Registration deliberately does not say which
            // addresses exist, but here the invitation already named the address, so pointing the
            // person at signing in instead is not a new leak. The reason and the flag let the
            // client offer "Sign in to accept", which finishes through acceptSignedIn.
            throw new ApiException(
                            ErrorCode.ALREADY_EXISTS,
                            "An account for " + email + " already exists. Sign in to accept the invitation.")
                    .with("reason", ACCOUNT_EXISTS)
                    .with("signInRequired", true);
        } catch (WebClientResponseException e) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The new account could not be created.", e);
        }
    }

    /**
     * Grants the invited role through identity, which checks the inviter's current membership and,
     * for a returning member, makes their old membership active again.
     *
     * @return the role the person now holds - for somebody already a member, the one they had
     */
    private String grantMembership(Invitation invitation, UUID userId, String failureSentence) {
        Map<String, Object> body = new HashMap<>();
        body.put("orgId", invitation.getOrgId());
        body.put("userId", userId);
        body.put("roleName", invitation.getRoleName());
        body.put("invitedBy", invitation.getInvitedBy());
        body.put("email", invitation.getEmail());
        try {
            Map<String, Object> granted = identityClient
                    .post()
                    .uri("/internal/memberships/bootstrap-member")
                    .header("Authorization", "Bearer " + tokens.forService("identity"))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .timeout(IDENTITY_TIMEOUT)
                    .block();
            Object role = granted == null ? null : granted.get("role");
            return role == null ? invitation.getRoleName() : role.toString();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                throw new ApiException(
                        ErrorCode.PERMISSION_DENIED,
                        problemDetail(e, "This invitation can no longer be accepted. Ask for a new one."));
            }
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
                throw cannotBeAccepted("unknown_role");
            }
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, failureSentence, e);
        } catch (RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, failureSentence, e);
        }
    }

    /** The signed-in account's own address, from identity, using the caller's own token. */
    private String signedInEmail(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }
        try {
            Map<String, Object> me = identityClient
                    .get()
                    .uri("/api/users/me")
                    .header("Authorization", authorization)
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .timeout(IDENTITY_TIMEOUT)
                    .block();
            Object email = me == null ? null : me.get("email");
            if (email == null) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR, "Your account could not be read. Try again.");
            }
            return email.toString();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
            }
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "Your account could not be read. Try again.", e);
        } catch (RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Your account could not be read. Try again.", e);
        }
    }

    /** The refusal for an invitation identity will no longer honour, in words the invitee can act on. */
    private static ApiException cannotBeAccepted(String reason) {
        if ("unknown_role".equals(reason)) {
            return ApiException.conflict(
                            "The role this invitation offered no longer exists. Ask whoever invited you for a new"
                                    + " invitation.")
                    .with("reason", reason);
        }
        return new ApiException(
                        ErrorCode.PERMISSION_DENIED,
                        "The person who invited you can no longer give this role. Ask them, or another"
                                + " administrator, for a new invitation.")
                .with("reason", reason == null ? "refused" : reason);
    }

    /** Why the person creating an invitation may not offer that role. */
    private static String refusalSentence(String reason) {
        if ("owner_only".equals(reason)) {
            return "Only an owner can invite someone as an owner.";
        }
        if ("not_a_member".equals(reason)) {
            return "Your membership of this workspace is no longer active.";
        }
        return "That role includes permissions you do not have, so you cannot invite someone to it.";
    }

    /** Identity's own sentence from a refusal, which is written for people, or a fallback. */
    private static String problemDetail(WebClientResponseException e, String fallback) {
        try {
            Map<?, ?> problem = e.getResponseBodyAs(Map.class);
            Object detail = problem == null ? null : problem.get("detail");
            return detail instanceof String text && !text.isBlank() ? text : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the raw token. Fast by design: the token is already high-entropy random. */
    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static InvitationView toView(Invitation invitation, String rawToken, String acceptUrl) {
        return new InvitationView(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRoleName(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getAcceptedAt(),
                rawToken,
                acceptUrl);
    }
}
