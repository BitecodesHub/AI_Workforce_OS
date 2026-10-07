package os.aiworkforce.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The edge verifies tokens signed by a key it has not seen yet without a restart.
 *
 * <p>Its previous key source cached the key set forever and, with every key published as
 * "local-dev", never noticed identity had restarted with a new key: every token was rejected until
 * the gateway restarted too. A served key set that changes underneath the decoder is exactly that
 * restart.
 */
class GatewaySecurityConfigTest {

    private final AtomicReference<String> published = new AtomicReference<>();
    private final AtomicInteger fetches = new AtomicInteger();
    private HttpServer identity;
    private NimbusReactiveJwtDecoder decoder;

    @BeforeEach
    void startIdentity() throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/.well-known/jwks.json", exchange -> {
            fetches.incrementAndGet();
            byte[] body = published.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        identity.start();
        String jwksUri = "http://127.0.0.1:" + identity.getAddress().getPort() + "/.well-known/jwks.json";
        decoder = GatewaySecurityConfig.decoder(security(jwksUri));
    }

    @AfterEach
    void stopIdentity() {
        identity.stop(0);
    }

    @Test
    @DisplayName("a token under a new key is accepted once the published set carries it")
    void refetchesOnUnknownKey() throws Exception {
        ECKey before = key();
        publish(before);
        assertThat(decode(sign(before)).getSubject()).isEqualTo("user-1");
        assertThat(decode(sign(before)).getSubject()).isEqualTo("user-1");
        assertThat(fetches.get()).as("a known key is served from the cache").isEqualTo(1);

        // Identity restarts with a new key, named by its own thumbprint.
        ECKey after = key();
        publish(after);
        assertThat(decode(sign(after)).getSubject()).isEqualTo("user-1");
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a token from a key nobody published is still rejected")
    void rejectsUnpublishedKey() throws Exception {
        publish(key());
        String forged = sign(key());
        assertThatThrownBy(() -> decode(forged)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    private Jwt decode(String token) {
        return decoder.decode(token).block(Duration.ofSeconds(10));
    }

    private void publish(ECKey key) {
        published.set(new JWKSet(key.toPublicJWK()).toString());
    }

    private static ECKey key() throws Exception {
        return new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
    }

    private static String sign(ECKey key) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer("aiwos")
                .audience(List.of("aiwos-api"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static PlatformProperties.Security security(String jwksUri) {
        return new PlatformProperties.Security(
                "aiwos",
                "aiwos-api",
                jwksUri,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                null,
                null,
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                "test-secret",
                "test-internal-secret",
                new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                new PlatformProperties.Encryption(null, "test", "AES/GCM/NoPadding", 12, 128),
                12,
                8,
                Duration.ofMinutes(15),
                false);
    }
}
