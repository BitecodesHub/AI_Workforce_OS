package os.aiworkforce.organisation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.organisation.domain.Invitation;
import os.aiworkforce.organisation.repository.Invitations;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Invites people to a workspace, and turns an accepted invitation into a real member.
 *
 * <p>There is no SMTP anywhere in this platform, so this does not send anything. It generates an
 * opaque token, stores only its hash - the same "never the raw value" rule as
 * {@link CredentialService} - and returns the raw token exactly once, in the create response, so
 * an administrator can copy the accept-invitation link and send it however they already reach
 * that person. Accepting it registers the person with identity-service and grants them the
 * invited role there through the internal service-to-service path, the same shape
 * {@code WorkspaceController} uses to grant a workspace's creator the owner role.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final Invitations invitations;
    private final WebClient identityClient;
    private final InternalTokenProvider tokens;

    public InvitationService(
            Invitations invitations, WebClient.Builder builder, PlatformProperties properties,
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

    @Transactional
    public InvitationView create(UUID orgId, UUID invitedBy, String email, String roleName, String originForLink) {
        String normalisedEmail = email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalisedEmail.isBlank()) {
            throw ApiException.validation("email", "must not be empty");
        }
        if (roleName == null || roleName.isBlank()) {
            throw ApiException.validation("roleName", "must not be empty");
        }

        // Re-inviting the same address replaces the still-open invitation rather than piling up
        // a second one nobody can tell apart from the first - the same open-invitation-per-
        // address rule the unique index in the database already enforces.
        invitations.findByOrgIdAndEmailIgnoreCaseAndStatus(orgId, normalisedEmail, "pending")
                .ifPresent(existing -> {
                    existing.setStatus("revoked");
                    invitations.save(existing);
                });

        String rawToken = generateToken();

        Invitation invitation = new Invitation();
        invitation.setId(UuidV7.generate());
        invitation.setOrgId(orgId);
        invitation.setEmail(normalisedEmail);
        invitation.setRoleName(roleName.strip());
        invitation.setTokenHash(hashToken(rawToken));
        invitation.setInvitedBy(invitedBy);
        invitation.setStatus("pending");
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitations.save(invitation);

        log.info("Invitation created for {} in workspace {} at role {}", normalisedEmail, orgId, roleName);

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
     * Turns an accepted invitation into a registered account with an active membership.
     *
     * <p>Two calls to identity-service, in sequence rather than one transaction spanning two
     * services: register the account, then grant the invited role. If the second call fails after
     * the first succeeds, the person has an account but no membership yet - recoverable by
     * re-running the invitation, unlike the workspace-creation path this mirrors, this is not
     * rolled back automatically, because undoing someone else's just-created account from here
     * is a worse failure mode than leaving it to a retry.
     */
    @Transactional
    public AcceptResult accept(String rawToken, String displayName, String password) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.validation("token", "must not be empty");
        }
        Invitation invitation = invitations.findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> ApiException.notFound("invitation", "token"));

        if (!invitation.isPending()) {
            throw ApiException.conflict("This invitation has already been used or withdrawn.");
        }
        if (invitation.hasExpired()) {
            invitation.setStatus("expired");
            invitations.save(invitation);
            throw ApiException.conflict("This invitation has expired. Ask for a new one.");
        }

        UUID userId = registerAccount(invitation.getEmail(), displayName, password);
        grantMembership(invitation.getOrgId(), userId, invitation.getRoleName());

        invitation.setStatus("accepted");
        invitation.setAcceptedAt(Instant.now());
        invitations.save(invitation);

        log.info("Invitation for {} accepted into workspace {}", invitation.getEmail(), invitation.getOrgId());
        return new AcceptResult(userId, invitation.getOrgId(), invitation.getEmail(), invitation.getRoleName());
    }

    private UUID registerAccount(String email, String displayName, String password) {
        try {
            var response = identityClient.post()
                    .uri("/api/auth/register")
                    .bodyValue(Map.of("email", email, "displayName", displayName, "password", password))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .block();
            String userId = response == null ? null : response.getHeaders().getFirst("X-User-Id");
            if (userId == null) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The new account could not be created.");
            }
            return UUID.fromString(userId);
        } catch (WebClientResponseException.Conflict e) {
            // The address already has an account. Registration deliberately does not say which
            // addresses exist, but here the invitation already named the address, so pointing the
            // person at signing in instead is not a new leak.
            throw ApiException.conflict(
                    "An account for " + email + " already exists. Sign in, then ask an administrator to add you.");
        } catch (WebClientResponseException e) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The new account could not be created.", e);
        }
    }

    private void grantMembership(UUID orgId, UUID userId, String roleName) {
        try {
            identityClient.post()
                    .uri("/internal/memberships/bootstrap-member")
                    .header("Authorization", "Bearer " + tokens.forService("identity"))
                    .bodyValue(Map.of("orgId", orgId, "userId", userId, "roleName", roleName))
                    .retrieve()
                    .bodyToMono(Object.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (WebClientResponseException e) {
            throw new ApiException(
                    ErrorCode.UPSTREAM_ERROR, "The account was created but could not be added to the workspace.", e);
        }
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
