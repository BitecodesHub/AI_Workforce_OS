package os.aiworkforce.platform.web.security;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Resource-server security for the seven business services.
 *
 * <p>The posture is deny by default: everything that is not explicitly permitted needs a verified
 * token. Only three groups are open, and each for a stated reason - the health and readiness
 * probes, which a platform must reach before the service can authenticate anything; the API
 * documentation, which describes the contract rather than the data; and pre-flight requests,
 * which carry no credentials by definition.
 *
 * <p>Sessions are stateless. There is no server-side session to fixate, no CSRF surface that a
 * cookie would create, and no affinity a load balancer has to preserve.
 */
@Configuration
@EnableMethodSecurity
public class ResourceServerConfig {

    private static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        // Authentication itself cannot require a token: a person who cannot sign in has none
        // to present. The demo listing sits here for the same reason.
        "/api/auth/**",
        "/.well-known/**",
        // Accepting an invitation is how a brand-new person gets their first token; they cannot
        // present one yet. The endpoint itself checks the invitation's own token, hashed and
        // matched server-side, so this is not an open door - it is the same shape as
        // /api/auth/register with the invite's token standing in for a password check.
        "/api/invitations/accept",
        /*
         * A provider's redirect after the consent screen carries no bearer token. The endpoint
         * honours it only with a state this service signed, single use and short lived, plus a
         * cookie from the browser that started the sign-in; see OAuthController.
         */
        "/api/oauth/callback",
        /*
         * The token-issuing endpoint is the one place that cannot require a token, because it is
         * where a service gets one. It is not unprotected: it checks a shared internal secret in
         * constant time, and the network policy restricts the port to pods inside the namespace.
         * Every other /internal path stays authenticated - credentials and embeddings in
         * particular must never be reachable this way.
         */
        "/internal/tokens",
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, JwtActorConverter converter, PlatformProperties properties) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .requestMatchers("/internal/**")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .build();
    }

    /**
     * Verifies tokens against the identity service's published keys.
     *
     * <p>Asymmetric signing means no other service holds a key that can mint a token: identity
     * signs, everybody else verifies. Issuer and audience are both checked, so a token minted for
     * another deployment cannot be replayed into this one.
     */
    /**
     * Verifies tokens against the identity service's published keys.
     *
     * <p>Asymmetric signing means no other service holds a key that can mint a token: identity
     * signs, everybody else verifies. Issuer and audience are both checked, so a token minted for
     * another deployment cannot be replayed into this one.
     *
     * <p>The key selector is built explicitly rather than through
     * {@code NimbusJwtDecoder.withJwkSetUri}, because that builder defaults to RS256 and rejects
     * anything else with "no matching key(s) found" - a message that sends somebody looking for a
     * missing key when the algorithm is the actual problem.
     */
    /** The shortest gap between two fetches of the identity service's published keys. */
    static final long JWKS_MIN_FETCH_INTERVAL_MS = 5_000;

    @Bean
    public JwtDecoder jwtDecoder(PlatformProperties properties) {
        PlatformProperties.Security security = properties.security();
        try {
            JWKSource<SecurityContext> keys = JWKSourceBuilder.create(new URI(security.jwksUri()).toURL())
                    .cache(
                            security.jwksCacheTtl().toMillis(),
                            security.jwksRefreshCooldown().toMillis())
                    // Nimbus's default allows one fetch per 30 s. A service that starts before the
                    // identity service is ready fails its first fetch and then refused every token
                    // with 401 for that whole window, which stranded a chat send after a restart.
                    // Five seconds still stops a stream of unknown-kid tokens from hammering identity.
                    .rateLimited(JWKS_MIN_FETCH_INTERVAL_MS)
                    .retrying(true)
                    .build();

            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keys));

            NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new OAuth2TokenValidator<Jwt>() {
                @Override
                public OAuth2TokenValidatorResult validate(Jwt token) {
                    List<String> audience = token.getAudience();
                    boolean audienceOk = audience != null && audience.contains(security.audience());
                    boolean issuerOk = security.issuer().equals(token.getClaimAsString("iss"));
                    if (audienceOk && issuerOk) {
                        return OAuth2TokenValidatorResult.success();
                    }
                    return OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Issuer or audience does not match", null));
                }
            }));
            return decoder;
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IllegalStateException("aiwos.security.jwks-uri is not a valid URL: " + security.jwksUri(), e);
        }
    }

    private CorsConfigurationSource corsSource(PlatformProperties properties) {
        PlatformProperties.Http http = properties.http();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(http.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key", "X-Workspace-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "X-RateLimit-Remaining"));
        config.setAllowCredentials(http.corsAllowCredentials());
        config.setMaxAge(http.corsMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
