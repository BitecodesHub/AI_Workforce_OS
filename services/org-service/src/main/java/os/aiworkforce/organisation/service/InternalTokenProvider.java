// @find: internal service token, service-to-service auth, token for identity service, internal tokens cache, mint internal token, POST /internal/tokens, on behalf of
// @what: Fetches and caches short-lived internal service tokens from identity-service for calls to sibling services.
// @flow: Calls identity-service POST /internal/tokens; used by InvitationService, WorkspaceController and AuditWiring.
package os.aiworkforce.organisation.service;

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
 * <p>The token carries the original human actor, so a workspace created by a person is still
 * attributable to them when identity grants the owner role on their behalf.
 *
 * <p>Cached by callee and actor together. A cache keyed by callee alone would hand one person's
 * authority to another person's request.
 */
@Component
public class InternalTokenProvider {

    /**
     * Cache key: the target service together with the acting identity and workspace, so tokens are
     * never shared between people.
     */
    private record CacheKey(String service, String actorId, String orgId) {}

    /**
     * A token with its expiry. Considered fresh until ten seconds before it expires, so a token is not
     * used just as it lapses.
     */
    private record CachedToken(String token, Instant expiresAt) {
        /** Whether the token can still be used. */
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt.minus(Duration.ofSeconds(10)));
        }
    }

    /** Tokens already obtained. Entries are replaced when stale and are not otherwise evicted. */
    private final Map<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();
    /** Client whose base URL is identity-service. */
    private final WebClient client;
    /** How long a fetched token is treated as usable, from the platform security settings. */
    private final Duration ttl;
    /** Shared secret that identity requires on its token endpoint; never logged. */
    private final String internalSecret;

    /**
     * Creates the provider, reading the identity address, token lifetime and shared secret from the
     * platform properties.
     */
    public InternalTokenProvider(WebClient.Builder builder, PlatformProperties properties) {
        this.client = builder.baseUrl(properties.services().identity()).build();
        this.ttl = properties.security().internalTokenTtl();
        this.internalSecret = properties.security().internalServiceSecret();
    }

    // @find: get internal token for a service, service token, cached token
    /**
     * Returns a bearer token for calling the named sibling service.
     *
     * <p>Flow: reads the current actor from {@code RequestContext} (the system actor when there is none);
     * returns a cached token for the same service, actor and workspace if one is still fresh; otherwise
     * calls {@code POST /internal/tokens} on identity-service with the internal secret, waiting up to five
     * seconds, and caches the result. The token carries the actor, its workspace and the human it acts
     * for. The call blocks the calling thread.
     *
     * <p>Failure: an HTTP error or timeout is thrown to the caller. An empty response yields {@code null},
     * which is not cached.
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

    /** The body identity returns: just the signed token. */
    private record TokenResponse(String token) {}
}
