package os.aiworkforce.identity.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.service.TokenService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Issues the short-lived tokens services use to call each other.
 *
 * <p>There is a bootstrapping problem here that is worth naming: a service needs a token to be
 * trusted, and this is where it gets one, so it cannot present a token to ask. Something else has
 * to establish that the caller is a platform service.
 *
 * <p>That something is a shared secret plus the network. The secret is configuration, never a
 * user credential, and it is compared in constant time so the comparison cannot be used as an
 * oracle. The network policy in the Kubernetes manifests already restricts this port to pods
 * inside the namespace, so the secret is the second of two locks rather than the only one. In a
 * deployment that wants a third, mutual TLS replaces the header without changing anything else
 * here.
 *
 * <p>The issued token carries the original human actor. That is the whole point: an action taken
 * three services deep still names the person who started it, and an audit trail that stops at
 * "the knowledge service did it" answers nothing anybody asks of it.
 */
@RestController
@RequestMapping("/internal/tokens")
@Tag(name = "Internal")
public class InternalTokenController {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenController.class);
    private static final String SECRET_HEADER = "X-Internal-Secret";

    /** The services allowed to be named as an audience. An unknown name is refused. */
    private static final Set<String> KNOWN_SERVICES = Set.of(
            "gateway", "identity", "organisation", "orchestrator", "memory", "knowledge", "integrations", "analytics");

    private final TokenService tokens;
    private final PlatformProperties properties;

    public InternalTokenController(TokenService tokens, PlatformProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public record InternalTokenRequest(
            @NotBlank String audience, String actorId, String actorKind, String orgId, String onBehalfOf) {}

    public record InternalTokenResponse(String token, Instant expiresAt) {}

    @PostMapping
    @Operation(summary = "Internal: mint a short-lived service-to-service token")
    public InternalTokenResponse issue(
            @Valid @RequestBody InternalTokenRequest request,
            @RequestHeader(name = SECRET_HEADER, required = false) String presentedSecret) {

        if (!secretMatches(presentedSecret)) {
            // Logged, because a wrong secret on this endpoint is either a misconfigured service
            // or somebody probing it, and both are worth seeing.
            log.warn("Rejected an internal token request with a missing or incorrect secret");
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }
        if (!KNOWN_SERVICES.contains(request.audience())) {
            throw ApiException.validation("audience", "is not a service in this platform");
        }

        Actor onBehalf = new Actor(
                blankToNull(request.actorId()) == null ? "system" : request.actorId(),
                parseKind(request.actorKind()),
                blankToNull(request.orgId()),
                null,
                // Deliberately no permissions. A service token authenticates the caller; it does
                // not carry authority. The receiving service checks its own rules against the
                // human named below.
                Set.of(),
                0L,
                blankToNull(request.onBehalfOf()),
                null,
                null,
                Map.of());

        TokenService.IssuedToken issued = tokens.issueInternalToken(request.audience(), onBehalf);
        return new InternalTokenResponse(issued.token(), issued.expiresAt());
    }

    /**
     * Constant-time comparison.
     *
     * <p>A short-circuiting equals leaks how many leading characters were correct, which is
     * enough to recover a secret one byte at a time given enough attempts.
     */
    private boolean secretMatches(String presented) {
        String expected = properties.security().internalServiceSecret();
        if (presented == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static Actor.Kind parseKind(String kind) {
        if (kind == null) {
            return Actor.Kind.SYSTEM;
        }
        try {
            return Actor.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Actor.Kind.SYSTEM;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
