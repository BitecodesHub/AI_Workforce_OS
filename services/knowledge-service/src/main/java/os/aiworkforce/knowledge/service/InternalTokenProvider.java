// @find: internal token, service to service token, service token cache, call other services, knowledge calls llm gateway, actor attribution, InternalTokenProvider
// @what: Obtains and caches short-lived tokens for calling sibling services on behalf of the acting person.
// @flow: Used by EmbeddingService (embedding calls) and AuditWiring.
package os.aiworkforce.knowledge.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * Obtains short-lived tokens for calling a sibling service.
 *
 * <p>The token carries the original human actor, so an ingestion started by a person is still
 * attributable to them when the orchestrator records what the embedding cost.
 *
 * <p>Cached by callee and actor together. A cache keyed by callee alone would hand one person's
 * authority to another person's request.
 */
@Component
public class InternalTokenProvider {

    /** Identifies one cached token: the callee, the actor and the workspace. */
    private record CacheKey(String service, String actorId, String orgId) {}

    /** A token with the time it expires. */
    private record CachedToken(String token, Instant expiresAt) {
        /** True while the token has more than ten seconds of life left. */
        boolean isFresh() {
            // Renewed early, so a token never expires between being fetched and being used.
            return Instant.now().isBefore(expiresAt.minus(Duration.ofSeconds(10)));
        }
    }

    /** Tokens already obtained, so that most calls make no request to the identity service. */
    private final Map<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();
    private final WebClient client;
    private final Duration ttl;
    private final String internalSecret;

    /**
     * Creates the provider.
     *
     * @param builder the shared HTTP client builder
     * @param properties supplies the identity service address, the token lifetime and the shared internal secret
     */
    public InternalTokenProvider(WebClient.Builder builder, PlatformProperties properties) {
        this.client = builder.baseUrl(properties.services().identity()).build();
        this.ttl = properties.security().internalTokenTtl();
        this.internalSecret = properties.security().internalServiceSecret();
    }

    /**
     * A token for calling a sibling service as the current actor.
     *
     * <p>Flow: takes the actor of the current request, or the platform's system actor when there is none, and
     * returns a cached token while it is fresh. Otherwise it calls {@code POST /internal/tokens} on the identity
     * service, authenticated with the internal secret, waits at most five seconds and caches the result for the
     * configured lifetime. Used by {@code EmbeddingService}, {@code InternalSearchController} and {@code AuditWiring}.
     *
     * @param service the callee, which becomes the token's audience, such as {@code orchestrator}
     * @return the token, or null when the identity service returned none
     * @throws RuntimeException when the identity service cannot be reached or answers with an error
     */
    public String forService(String service) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        CacheKey key = new CacheKey(service, actor.id(), actor.orgId());

        CachedToken cached = cache.get(key);
        if (cached != null && cached.isFresh()) {
            return cached.token();
        }

        String token = client.post()
                .uri("/internal/tokens")
                // Establishes that the caller is a platform service on the one endpoint that
                // cannot ask for a token, because it is the endpoint that issues them.
                .header("X-Internal-Secret", internalSecret)
                .bodyValue(Map.of(
                        "audience",
                        service,
                        "actorId",
                        actor.id(),
                        "actorKind",
                        actor.kind().name(),
                        "orgId",
                        actor.orgId() == null ? "" : actor.orgId(),
                        "onBehalfOf",
                        actor.humanId() == null ? "" : actor.humanId()))
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .timeout(Duration.ofSeconds(5))
                .map(TokenResponse::token)
                .block();

        if (token != null) {
            cache.put(key, new CachedToken(token, Instant.now().plus(ttl)));
        }
        return token;
    }

    /** The identity service's reply: the signed token. */
    private record TokenResponse(String token) {}
}
