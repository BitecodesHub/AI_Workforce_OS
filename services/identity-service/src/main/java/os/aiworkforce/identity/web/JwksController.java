package os.aiworkforce.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;

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
