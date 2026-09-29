package os.aiworkforce.gateway.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The edge's own token check.
 *
 * <p>The gateway verifies a token is genuine and not expired; it does not decide what the caller
 * may do with it. That decision belongs to the service that owns the resource, checked again
 * there with {@code @RequiresPermission} - the gateway's check exists so an unauthenticated
 * request never reaches a business service at all, not to replace that service's own check.
 *
 * <p>Mirrors {@code platform-web}'s servlet {@code ResourceServerConfig} (same public paths, same
 * ES256/issuer/audience validation), but the gateway is reactive and cannot depend on
 * {@code platform-web}, which pulls in a servlet container.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/api/*/v3/api-docs",
        // Authentication itself cannot require a token: a person who cannot sign in has none to
        // present. The identity service enforces its own rate limit on these underneath.
        "/api/auth/**",
        "/.well-known/**",
        // Same reasoning as /api/auth/**: accepting an invitation is how a brand-new person gets
        // their first token. The endpoint checks the invitation's own hashed token itself.
        "/api/invitations/accept",
    };

    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http, PlatformProperties properties) {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource(properties)))
                .authorizeExchange(auth -> auth.pathMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .pathMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }

    /**
     * Verifies tokens against the identity service's published keys.
     *
     * <p>Built with an explicit JWS algorithm rather than the plain {@code withJwkSetUri(uri)}
     * builder, which defaults to RS256 and rejects an ES256 token with "no matching key(s)
     * found" - a message that sends somebody looking for a missing key when the algorithm is the
     * actual problem. Same key, same algorithm, same issuer/audience check as {@code
     * platform-web}'s servlet decoder, so a token good enough for a business service is good
     * enough for the edge.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(PlatformProperties properties) {
        PlatformProperties.Security security = properties.security();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(security.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
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
