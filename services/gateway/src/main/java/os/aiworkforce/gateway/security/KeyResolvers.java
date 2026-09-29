package os.aiworkforce.gateway.security;

import java.net.InetSocketAddress;
import java.util.Optional;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Who a rate limit is charged against.
 *
 * <p>Two resolvers, because the same identity is not available at the same point for every
 * route. Pre-authentication endpoints (signing in) have no principal yet, so they are limited by
 * the caller's address instead - the one thing every request has, authenticated or not. Every
 * other route has already passed the resource-server filter, so its request carries a verified
 * JWT and can be limited per person, which is what stops one workspace's traffic from exhausting
 * a limit shared with everybody else.
 */
@Configuration
public class KeyResolvers {

    @Bean
    public KeyResolver remoteAddressKeyResolver() {
        return exchange -> Mono.just(remoteAddress(exchange));
    }

    /**
     * Primary so the autoconfigured rate-limiter filter factory, which asks for a single
     * {@code KeyResolver} by type rather than by name, has one unambiguous default. Routes still
     * pick either resolver explicitly by name ({@code #{@principalKeyResolver}} or
     * {@code #{@remoteAddressKeyResolver}}) in application.yml regardless of which is primary.
     */
    @Primary
    @Bean
    public KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(token -> token.getToken())
                .map(KeyResolvers::subjectOf)
                .switchIfEmpty(Mono.fromSupplier(() -> remoteAddress(exchange)));
    }

    private static String subjectOf(Jwt jwt) {
        return Optional.ofNullable(jwt.getSubject()).orElse(jwt.getClaimAsString("jti"));
    }

    private static String remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }
}
