package os.aiworkforce.platform.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Proves a token signed by identity can actually be verified by another service.
 *
 * <p>This is the single point of failure for the whole platform's authentication: identity signs
 * and seven services verify through the published key set. When that pairing is
 * wrong every authenticated request returns 401 with a message about missing keys, which sends
 * somebody looking at the key set rather than at the algorithm.
 *
 * <p>The test builds the same processor the resource server uses, so a change to the decoder that
 * breaks verification fails here instead of in a running cluster.
 */
class TokenVerificationTest {

    @Test
    @DisplayName("a token signed with ES256 verifies against the published key set")
    void verifiesEs256Token() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256).keyID("local-dev").generate();

        String token = sign(signingKey);

        // Only the public half is published, exactly as the JWKS endpoint serves it.
        JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keys));

        Jwt decoded = new NimbusJwtDecoder(processor).decode(token);

        assertThat(decoded.getSubject()).isEqualTo("user-1");
        assertThat(decoded.getAudience()).contains("aiwos-api");
        assertThat(decoded.getClaimAsString("perms")).contains("agent:read");
    }

    @Test
    @DisplayName("a token signed by a different key is rejected")
    void rejectsForeignKey() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256).keyID("local-dev").generate();
        ECKey otherKey = new ECKeyGenerator(Curve.P_256).keyID("local-dev").generate();

        String token = sign(otherKey);

        JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keys));

        // Asymmetric signing is what stops any other service minting tokens. If this passed, a
        // disclosure in the least sensitive service would compromise every workspace.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NimbusJwtDecoder(processor).decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    private static String sign(ECKey key) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer("aiwos")
                .audience(List.of("aiwos-api"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("perms", "agent:read agent:run")
                .claim("pv", 1)
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.getKeyID())
                        .type(com.nimbusds.jose.JOSEObjectType.JWT)
                        .build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
