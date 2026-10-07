package os.aiworkforce.identity.service;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * is equally sound, and the JDK signs and verifies it on its own. The one place a third-party
 * library is involved is reading a configured PEM: Bouncy Castle, already on the classpath for
 * Argon2, derives the public point from the private key (see {@link #fromPrivateKey}).
 *
 * <p>The key comes from one of two places, and either way every replica and every restart signs
 * with the same one. A PEM in {@code aiwos.security.signing-private-key-pem} is used as given,
 * named by {@code aiwos.security.active-key-id} or, without one, by its RFC 7638 thumbprint.
 * Otherwise the key is generated once and kept in the database by {@link SigningKeyStore},
 * encrypted under the master key, and always named by its thumbprint. Either way a different key
 * has a different name, so a verifier meeting a new one refetches the key set at once rather than
 * rejecting tokens under a stale key that kept the old name.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * How long the stored keys behind {@link #jwks()} are reused before the table is read again.
     *
     * <p>The key set is public and unauthenticated, so without this every anonymous request for it
     * is a database query. Five seconds is far inside the overlap a retiring key stays published
     * for, so a cached set never drops a key that tokens still need. It is kept short because a
     * key another replica has just adopted is missing from this replica's set until the next read:
     * during a rotation with several replicas, a verifier can meet the new kid up to five seconds
     * before every replica publishes it.
     */
    static final Duration PUBLISHED_KEYS_TTL = Duration.ofSeconds(5);

    private final PlatformProperties.Security config;
    private final SigningKeyStore keyStore;
    private final Clock clock;
    private final AtomicReference<ECKey> activeKey = new AtomicReference<>();
    private volatile StoredKeys storedKeys;

    /** The stored public keys as last read, and when. */
    private record StoredKeys(List<JWK> keys, Instant readAt) {}

    @Autowired
    public TokenService(PlatformProperties properties, SigningKeyStore keyStore) {
        this(properties, keyStore, Clock.systemUTC());
    }

    TokenService(PlatformProperties properties, SigningKeyStore keyStore, Clock clock) {
        this.config = properties.security();
        this.keyStore = keyStore;
        this.clock = clock;
        ECPrivateKey configured = config.signingPrivateKey();
        if (configured != null) {
            ECKey key = fromPrivateKey(configured, config.activeKeyId());
            keyStore.adoptExternalKey(key);
            this.activeKey.set(key);
            log.info("Token signing key {} loaded from configuration", key.getKeyID());
        } else {
            this.activeKey.set(keyStore.activeKey());
            log.info("Token signing key {} loaded from the database", activeKey.get().getKeyID());
        }
    }

    /**
     * An access token for a person acting in one workspace.
     *
     * <p>The permission set is written into the token so that seven services can authorise a
     * request without asking identity about it. That is a deliberate trade: it removes a network
     * call from every request, and it means a permission removed or a membership ended is not
     * felt until the token expires. No service compares {@code pv} with the role's current
     * version yet; it is carried for that check and for the audit trail. What actually bounds the
     * delay is the refresh: {@link AuthService} reads the role and membership afresh every time,
     * so a change takes effect at the next refresh, within the access-token lifetime
     * ({@code aiwos.security.access-token-ttl}, five minutes).
     */
    public IssuedToken issueAccessToken(
            UUID userId, UUID orgId, UUID roleId, Set<String> permissions, long permissionVersion, UUID sessionId) {
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

    /**
     * The public key set every other service fetches to verify tokens.
     *
     * <p>This process's own key is always in it, whatever the database says, and so is every
     * stored key still inside its overlap: the active key another replica may have just started
     * using, and retiring keys whose tokens have not expired yet. The stored keys are read at most
     * once per {@link #PUBLISHED_KEYS_TTL}. If the table cannot be read, the set keeps the keys
     * read last time - or this process's key alone - rather than failing outright.
     */
    public Map<String, Object> jwks() {
        ECKey own = activeKey.get();
        List<JWK> published = new ArrayList<>();
        published.add(own.toPublicJWK());
        for (JWK stored : storedPublicKeys()) {
            if (!own.getKeyID().equals(stored.getKeyID())) {
                published.add(stored);
            }
        }
        return new JWKSet(published).toJSONObject();
    }

    private List<JWK> storedPublicKeys() {
        Instant now = clock.instant();
        StoredKeys cached = storedKeys;
        if (cached != null && now.isBefore(cached.readAt().plus(PUBLISHED_KEYS_TTL))) {
            return cached.keys();
        }
        List<JWK> keys;
        try {
            keys = List.copyOf(keyStore.publishedKeys());
        } catch (RuntimeException e) {
            // Remembered as read now as well, so a database outage is retried once per interval
            // rather than once per anonymous request.
            log.warn("Stored signing keys could not be read; publishing the keys read last time", e);
            keys = cached == null ? List.of() : cached.keys();
        }
        storedKeys = new StoredKeys(keys, now);
        return keys;
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

    /**
     * The signing JWK for a configured private key.
     *
     * <p>A PKCS#8 file holds the private scalar but not reliably the public point, so the point is
     * computed from it (Q = d x G on P-256). Bouncy Castle's provider, already on the classpath
     * for Argon2, does the arithmetic; Nimbus's own PEM parser would need the separate bcpkix
     * library, which nothing else here uses.
     *
     * @param keyId the configured key identifier; blank means the key's RFC 7638 thumbprint
     */
    public static ECKey fromPrivateKey(ECPrivateKey privateKey, String keyId) {
        try {
            var p256 = NISTNamedCurves.getByName("P-256");
            var q = new FixedPointCombMultiplier().multiply(p256.getG(), privateKey.getS()).normalize();
            BigInteger x = q.getAffineXCoord().toBigInteger();
            BigInteger y = q.getAffineYCoord().toBigInteger();
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), privateKey.getParams()));

            ECKey key = new ECKey.Builder(Curve.P_256, publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .build();
            String kid = keyId == null || keyId.isBlank()
                    ? key.computeThumbprint().toString()
                    : keyId.strip();
            return new ECKey.Builder(key).keyID(kid).build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("aiwos.security.signing-private-key-pem could not be turned into a key", e);
        }
    }

    /**
     * @param token the serialised token
     * @param expiresAt when it stops being accepted, so the client can refresh ahead of time
     */
    public record IssuedToken(String token, Instant expiresAt) {}
}
