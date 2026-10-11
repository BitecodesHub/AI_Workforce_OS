// @find: oauth state codec, sign state, verify state, signed state parameter, csrf, browser binding, expiry, oauth security
// @what: Signs and verifies the OAuth state value so a callback can be trusted and tied to the browser that started it.
// @flow: Used by OAuthService.start and callback
package os.aiworkforce.integrations.oauth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and checks the {@code state} sent to a provider.
 *
 * <p>The state names the stored row and carries the workspace, the person and the connector it
 * was issued for, with an expiry, all under an HMAC. A callback whose state was altered, issued
 * for someone else, or has run out is refused before the database is asked anything. Single use is
 * then enforced by the row itself (see {@code OAuthStates.claim}). The {@code binding} is a hash of
 * a random value held in a cookie in the browser that started the sign-in, so a callback link
 * opened in any other browser is refused too.
 */
public final class OAuthStateCodec {

    /**
     * What a verified state says.
     *
     * <p>{@code stateId} is the stored {@code OAuthState} row, {@code userId} may be null, and
     * {@code binding} is the hash of the browser nonce (null when none was bound).
     */
    public record Claims(UUID stateId, UUID orgId, UUID userId, String server, Instant expiresAt, String binding) {}

    /** URL-safe Base64 without padding, so the state travels in a query string unescaped. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** The HMAC key, derived from the configured secret and a purpose label. */
    private final byte[] key;

    /**
     * Derives the signing key from the secret. It performs no I/O and holds no other state.
     *
     * @param secret the shared state secret from platform security settings; never logged
     */
    public OAuthStateCodec(String secret) {
        // A purpose label keeps this key distinct from anything else derived from the same secret.
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "aiwos-oauth-state-v1".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encodes the claims and signs them.
     *
     * <p>Flow: the six fields are joined with a separator (a dash stands for a missing user or
     * binding), Base64 encoded, and followed by an HMAC-SHA256 of that body. Called by
     * {@code OAuthService.start}. No side effects.
     *
     * @param claims what the state should say
     * @return the {@code body.signature} string to send to the provider as {@code state}
     */
    public String sign(Claims claims) {
        String payload = String.join(
                "|",
                claims.stateId().toString(),
                claims.orgId().toString(),
                claims.userId() == null ? "-" : claims.userId().toString(),
                claims.server(),
                Long.toString(claims.expiresAt().getEpochSecond()),
                claims.binding() == null ? "-" : claims.binding());
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(hmac(key, body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The claims of a state this codec signed and that has not expired, or empty.
     *
     * <p>Flow: the length and shape are checked, the signature is compared in constant time, the body
     * is decoded and parsed, and the expiry is compared with {@code now}. Any malformed, altered or
     * expired value yields empty rather than an exception, and no reason is given. Called by
     * {@code OAuthService.callback}. No side effects.
     *
     * @param state the value the provider returned, possibly null or tampered with
     * @param now the current time
     * @return the claims, or empty when the state is not acceptable
     */
    public Optional<Claims> verify(String state, Instant now) {
        if (state == null || state.length() > 1024) {
            return Optional.empty();
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot == state.length() - 1) {
            return Optional.empty();
        }
        String body = state.substring(0, dot);
        try {
            byte[] given = DECODER.decode(state.substring(dot + 1));
            byte[] expected = hmac(key, body.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(given, expected)) {
                return Optional.empty();
            }
            String[] parts = new String(DECODER.decode(body), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 6) {
                return Optional.empty();
            }
            Claims claims = new Claims(
                    UUID.fromString(parts[0]),
                    UUID.fromString(parts[1]),
                    "-".equals(parts[2]) ? null : UUID.fromString(parts[2]),
                    parts[3],
                    Instant.ofEpochSecond(Long.parseLong(parts[4])),
                    "-".equals(parts[5]) ? null : parts[5]);
            return claims.expiresAt().isAfter(now) ? Optional.of(claims) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Computes HMAC-SHA256.
     *
     * @throws IllegalStateException when the algorithm is unavailable, which cannot happen on a standard JVM
     */
    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
