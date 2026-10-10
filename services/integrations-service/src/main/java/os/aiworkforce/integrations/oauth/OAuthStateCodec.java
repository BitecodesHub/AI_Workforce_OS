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

    /** What a verified state says. */
    public record Claims(UUID stateId, UUID orgId, UUID userId, String server, Instant expiresAt, String binding) {}

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    public OAuthStateCodec(String secret) {
        // A purpose label keeps this key distinct from anything else derived from the same secret.
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "aiwos-oauth-state-v1".getBytes(StandardCharsets.UTF_8));
    }

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

    /** The claims of a state this codec signed and that has not expired, or empty. */
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
