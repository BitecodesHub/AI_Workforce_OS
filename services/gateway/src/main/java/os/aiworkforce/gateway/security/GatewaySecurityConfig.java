// @find: gateway security, token check, verify jwt, access token, bearer token, public paths, unauthenticated, login open, CORS, allowed origins, JWKS, ES256, issuer, audience, jwt decoder, GatewaySecurityConfig, filterChain, CSRF, invitations accept, oauth callback, 401 unauthorized
// @what: Edge security for the gateway: which paths are public, JWT verification against identity's published keys, and CORS rules.
// @flow: Runs before every proxied route; reads PlatformProperties security and http settings; mirrors platform-web ResourceServerConfig.
package os.aiworkforce.gateway.security;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        // present. The identity service enforces its own rate limit on these underneath. This
        // covers /api/auth/password-reset too, where the reset link's own token is the credential.
        "/api/auth/**",
        "/.well-known/**",
        // Same reasoning as /api/auth/**: accepting an invitation is how a brand-new person gets
        // their first token. The endpoint checks the invitation's own hashed token itself.
        "/api/invitations/accept",
        // A provider's redirect after the consent screen carries no bearer token. The integrations
        // service honours it only with its own signed, single-use state and a browser cookie.
        "/api/oauth/callback",
    };

    // @find: gateway security filter chain, which paths need a token, public paths, permit all, CSRF off, CORS, oauth2 resource server, who can call the api without logging in
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
     * <p>Built with an explicit JWS algorithm, because the plain {@code withJwkSetUri(uri)} builder
     * defaults to RS256 and rejects an ES256 token with "no matching key(s) found" - a message
     * that sends somebody looking for a missing key when the algorithm is the actual problem.
     * Same key, same algorithm, same issuer/audience check as {@code platform-web}'s servlet
     * decoder, so a token good enough for a business service is good enough for the edge.
     */
    // @find: jwt decoder bean, verify access token, ES256 token, issuer and audience check
    @Bean
    public ReactiveJwtDecoder jwtDecoder(PlatformProperties properties) {
        return decoder(properties.security());
    }

    // @find: build jwt decoder, ES256, issuer audience validator, token rejected invalid_token
    static NimbusReactiveJwtDecoder decoder(PlatformProperties.Security security) {
        JWKSource<SecurityContext> keys = publishedKeys(security);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(jwt -> keysFor(keys, jwt))
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

    /**
     * The identity service's key set, cached exactly as {@code platform-web} caches it.
     *
     * <p>The reactive {@code withJwkSetUri} source this replaces cached the set with no expiry,
     * and refetched only when a token named a key it did not hold. After identity restarted with
     * a new key under the old name, the edge rejected every token until the gateway itself was
     * restarted. This source expires after {@code jwks-cache-ttl}, and still refetches at once -
     * rate-limited - when a token names an unknown key, which every new key now does, because
     * identity names each key by its own thumbprint.
     */
    // @find: identity public keys, JWKS cache, jwks-uri, key refresh cooldown, unknown key refetch, new signing key
    static JWKSource<SecurityContext> publishedKeys(PlatformProperties.Security security) {
        try {
            return JWKSourceBuilder.create(new URI(security.jwksUri()).toURL())
                    .cache(security.jwksCacheTtl().toMillis(), security.jwksRefreshCooldown().toMillis())
                    .build();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("aiwos.security.jwks-uri is not a valid URL: " + security.jwksUri(), e);
        }
    }

    /**
     * The published keys that could have signed {@code jwt}.
     *
     * <p>The Nimbus source blocks while it fetches, so it runs on the bounded elastic scheduler
     * rather than on an event-loop thread. A cache hit returns at once.
     */
    private static Flux<JWK> keysFor(JWKSource<SecurityContext> keys, SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        return Mono.fromCallable(() -> keys.get(selector, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    // @find: CORS configuration, allowed origins, allowed headers, exposed headers, X-Request-Id, X-Workspace-Id, Idempotency-Key
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
