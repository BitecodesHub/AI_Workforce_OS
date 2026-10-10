// @find: jwks, public keys, well-known jwks.json, verify tokens, key set, JwksController, GET /.well-known/jwks.json
// @what: Publishes the public keys other services use to verify access tokens.
// @flow: Reads TokenService.jwks; fetched and cached by every other service.
package os.aiworkforce.identity.web;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.service.TokenService;

/**
 * Publishes the public keys that verify this platform's tokens.
 *
 * <p>Deliberately unauthenticated: a public key is public, and every other service needs it before
 * it can verify anything, including a token that would authenticate a request for the key itself.
 *
 * <p>The set holds the active key and every retiring key still inside its overlap, so a token
 * signed just before a key change keeps verifying until it expires. A verifier does not wait for
 * the cache below to lapse before it sees a new key: each key is named by its own thumbprint, and
 * a token naming a key the verifier has not seen makes it fetch this set again at once.
 *
 * <p>The cache header is a balance. Too long, and a rotated key takes hours to propagate; too
 * short, and seven services fetch this endpoint on every request they cannot verify. Ten minutes
 * with a stale-while-revalidate window keeps verification working through a brief outage here.
 */
@RestController
@Tag(name = "Keys")
public class JwksController {

    private final TokenService tokens;

    public JwksController(TokenService tokens) {
        this.tokens = tokens;
    }

    // @find: jwks, public keys, GET /.well-known/jwks.json
    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "Public keys used to verify access tokens")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10))
                        .cachePublic()
                        .staleWhileRevalidate(java.time.Duration.ofHours(1)))
                .body(tokens.jwks());
    }
}
