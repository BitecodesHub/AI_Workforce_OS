// @find: internal token, service token, service-to-service auth, actor propagation, mint token, token for sibling service, forRequester, JWT for internal call
// @what: Mints short-lived service tokens that carry the original human actor for calls to sibling services.
// @flow: Used by AuditClient, MemoryClient, KnowledgeClient, resolvers
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Obtains short-lived tokens for calling a sibling service.
 *
 * <p>The token carries the original human actor, so an action taken three services deep still
 * names the person who started it. An audit trail that stops at "the orchestrator did it" cannot
 * answer the only question ever asked of one.
 *
 * <p>Tokens are cached only until shortly before they expire, and keyed by callee, actor and
 * workspace. A shared cache keyed by callee alone would hand one person's authority to another's
 * request. The cache is bounded: expired tokens are dropped whenever one is added, and it never
 * holds more than {@value #MAX_CACHED} at once, so a long-running process that has acted for many
 * people does not keep every token it ever minted.
 */
@Component
public class InternalTokenProvider {

    /** Far more than the people and workspaces active within one token lifetime. */
    static final int MAX_CACHED = 1_000;

    private record CacheKey(String service, String actorId, String orgId) {}

    private record CachedToken(String token, Instant expiresAt) {
        boolean isFresh() {
            // Renewed early, so a token never expires between being fetched and being used.
            return Instant.now().isBefore(expiresAt.minus(Duration.ofSeconds(10)));
        }
    }

    private final Map<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();
    private final WebClient client;
    private final Duration ttl;
    private final String internalSecret;

    public InternalTokenProvider(WebClient.Builder builder, PlatformProperties properties) {
        this.client = builder.baseUrl(properties.services().identity()).build();
        this.ttl = properties.security().internalTokenTtl();
        this.internalSecret = properties.security().internalServiceSecret();
    }

    // @find: token for service, platform actor
    /** A token for {@code service}, for whoever and whichever workspace the current context names. */
    public String forService(String service) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        return mint(service, actor, actor.orgId());
    }

    // @find: token for service in workspace
    /**
     * A token for {@code service} that names {@code orgId} as its workspace.
     *
     * <p>For endpoints that check the token's workspace against the one the request names, such as
     * the organisation service's credential reveal. A scheduled or system run has no workspace in
     * its context, so the workspace being acted for is named explicitly; the person is still
     * carried from the context, so the audit trail keeps them.
     */
    public String forService(String service, UUID orgId) {
        Objects.requireNonNull(orgId, "orgId");
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        // A caller acting for one workspace never gets a token for another: without this, any path
        // that takes the workspace from its caller could spend another workspace's stored keys.
        if (actor.orgId() != null && !actor.orgId().equals(orgId.toString())) {
            throw new ApiException(
                    ErrorCode.ORGANISATION_MISMATCH, "A request for one workspace cannot act for another.");
        }
        return mint(service, actor, orgId.toString());
    }

    // @find: token for requester, act as person
    /**
     * A token for {@code service} that acts as one named person in {@code orgId}, whoever the
     * current context says is running.
     *
     * <p>For work that carries on behalf of a person without that person's request in flight: an
     * agent's run, started by a schedule or a goal, searching documents as the person it works
     * for. The receiving service reads the person from the token and decides what they may do; the
     * token itself carries no permissions. A request made as one workspace can never name another
     * workspace's person, for the same reason {@link #forService(String, UUID)} refuses it.
     */
    public String forRequester(String service, UUID orgId, UUID requestedBy) {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Actor current = RequestContext.actor().orElse(Actor.SYSTEM);
        if (current.orgId() != null && !current.orgId().equals(orgId.toString())) {
            throw new ApiException(
                    ErrorCode.ORGANISATION_MISMATCH, "A request for one workspace cannot act for another.");
        }
        Actor requester = Actor.user(requestedBy.toString(), orgId.toString(), null, Set.of(), 0L);
        return mint(service, requester, orgId.toString());
    }

    private String mint(String service, Actor actor, String orgId) {
        CacheKey key = new CacheKey(service, actor.id(), orgId);

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
                        orgId == null ? "" : orgId,
                        "onBehalfOf",
                        actor.humanId() == null ? "" : actor.humanId()))
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .timeout(Duration.ofSeconds(5))
                .map(TokenResponse::token)
                .block();

        if (token != null) {
            remember(key, new CachedToken(token, Instant.now().plus(ttl)));
        }
        return token;
    }

    private void remember(CacheKey key, CachedToken token) {
        cache.values().removeIf(entry -> !entry.isFresh());
        // Still full of live tokens: drop the ones closest to expiry, which are the cheapest to
        // lose because they would have been renewed soonest anyway.
        while (cache.size() >= MAX_CACHED) {
            var soonest = cache.entrySet().stream()
                    .min(Comparator.comparing((Map.Entry<CacheKey, CachedToken> entry) ->
                            entry.getValue().expiresAt()));
            if (soonest.isEmpty()) {
                break;
            }
            cache.remove(soonest.get().getKey(), soonest.get().getValue());
        }
        cache.put(key, token);
    }

    /** How many tokens are held, for tests of the bound. */
    int cachedTokens() {
        return cache.size();
    }

    private record TokenResponse(String token) {}
}
