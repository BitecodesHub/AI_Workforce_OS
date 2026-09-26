package os.aiworkforce.identity.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Mints access tokens and publishes the keys that verify them.
 *
 * <p>Signing is asymmetric. Identity holds the private key; every other service verifies with the
 * public one fetched from the JWKS endpoint. With a shared secret, any of the eight services
 * could mint a token for any workspace and any role, and a disclosure in the least sensitive
 * service would compromise all of them.
 *
 * <p>ES256 rather than RSA: the signatures are a fraction of the size, which matters on a token
 * sent with every request, and verification is fast enough that no service needs to cache the
 * result.
 *
 * <p>ES256 rather than Ed25519, which was the first choice: Nimbus documents
 * {@code OctetKeyPair.toPublicKey()} as unsupported, so an Ed25519 key published through a JWKS
 * endpoint cannot be turned into a verifier by the standard JWT processor. The failure surfaces
 * as "no matching key(s) found", which points at the key set rather than at the algorithm. ES256
 * is supported natively by the JDK, needs no third-party crypto library, and is equally sound.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformProperties.Security config;
    private final AtomicReference<ECKey> activeKey = new AtomicReference<>();

    public TokenService(PlatformProperties properties) {
        this.config = properties.security();
        this.activeKey.set(generateKey(config.activeKeyId() == null ? "local-dev" : config.activeKeyId()));
        log.info("Token signing key {} ready", activeKey.get().getKeyID());
    }

    /**
     * An access token for a person acting in one workspace.
     *
     * <p>The permission set is written into the token so that seven services can authorise a
     * request without asking identity about it. That is a deliberate trade: it removes a network
     * call from every request, and it means a permission removed is not felt until the token
     * expires. {@code pv} is what closes that gap - a service compares it with the role's current
     * version and rejects a token that is behind.
     */
    public IssuedToken issueAccessToken(
            UUID userId,
            UUID orgId,
            UUID roleId,
            Set<String> permissions,
            long permissionVersion,
            UUID sessionId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(config.accessTokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(config.issuer())
                .audience(config.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .notBeforeTime(Date.from(now.minus(config.clockSkewLeeway())))
                .jwtID(UUID.randomUUID().toString())
                .claim("kind", "user")
                .claim("org", orgId == null ? null : orgId.toString())
                .claim("role", roleId == null ? null : roleId.toString())
                // Space-delimited, following the OAuth convention: a workspace owner holds
                // forty-six codes, and a JSON array of them makes every request header larger.
                .claim("perms", String.join(" ", permissions))
                .claim("pv", permissionVersion)
                .claim("sid", sessionId == null ? null : sessionId.toString())
                .build();

        return new IssuedToken(sign(claims), expiry);
    }

    /**
     * A short-lived token for one service calling another.
     *
     * <p>It carries the original human actor, so an action taken three services deep still names
     * the person who started it. An audit trail that stops at "the orchestrator did it" cannot
     * answer the only question anybody asks of it.
     */
    public IssuedToken issueInternalToken(String audienceService, Actor onBehalfOf) {
        Instant now = Instant.now();
        Instant expiry = now.plus(config.internalTokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(onBehalfOf.id())
                .issuer(config.issuer())
                .audience(List.of(config.audience(), "service:" + audienceService))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                // Always a service token. Copying the human's kind here made every internal
                // endpoint refuse it - they reject user tokens by design - so no provider
                // credential could ever be resolved. The person is carried in "obo" instead.
                .claim("kind", onBehalfOf.isAgent() ? "agent" : "system")
                .claim("org", onBehalfOf.orgId())
                .claim("perms", String.join(" ", onBehalfOf.permissions()))
                .claim("pv", onBehalfOf.permissionVersion())
                .claim("obo", onBehalfOf.humanId())
                .claim("agent", onBehalfOf.agentId())
                .build();

        return new IssuedToken(sign(claims), expiry);
    }

    /**
     * A refresh token, returned once and stored only as a hash.
     *
     * <p>Opaque rather than a JWT: a refresh token's whole purpose is to be revocable, and a
     * self-contained token cannot be revoked without the server-side record that makes being
     * self-contained pointless.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of a refresh token. Fast by design: the token is already high-entropy random. */
    public String hashRefreshToken(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /** The public key set every other service fetches to verify tokens. */
    public Map<String, Object> jwks() {
        return new JWKSet(activeKey.get().toPublicJWK()).toJSONObject();
    }

    public String activeKeyId() {
        return activeKey.get().getKeyID();
    }

    private String sign(JWTClaimsSet claims) {
        try {
            ECKey key = activeKey.get();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(key.getKeyID())
                            .type(com.nimbusds.jose.JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "The session could not be created.", e);
        }
    }

    private static ECKey generateKey(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }

    /**
     * @param token the serialised token
     * @param expiresAt when it stops being accepted, so the client can refresh ahead of time
     */
    public record IssuedToken(String token, Instant expiresAt) {}
}
