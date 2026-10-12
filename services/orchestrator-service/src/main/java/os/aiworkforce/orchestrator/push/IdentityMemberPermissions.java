// @find: identity permissions client, POST /internal/memberships/permissions, member permissions cache, IdentityMemberPermissions
// @what: Asks the identity service what a person holds in a workspace, with a short cache.
// @flow: Implements MemberPermissions for PushRecipients; calls identity /internal/memberships/permissions with a service token from InternalTokenProvider
package os.aiworkforce.orchestrator.push;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The identity service's answer to "what does this person hold here".
 *
 * <p>Held for {@value #TTL_SECONDS} seconds per person and workspace, so a burst of approvals costs
 * identity one call per subscribed person, and a removed permission stops push within that time
 * (well inside the five minutes an access token already carries one). The cache is bounded. A
 * failure is not cached and is reported as no answer, so that person is left out rather than told
 * about something they may no longer decide.
 */
@Component
public class IdentityMemberPermissions implements MemberPermissions {

    private static final Logger log = LoggerFactory.getLogger(IdentityMemberPermissions.class);

    /** How long an answer is reused, in seconds. */
    static final long TTL_SECONDS = 30;

    private static final int MAX_CACHED = 1_000;

    private record Key(UUID orgId, UUID userId) {}

    private record Cached(Set<String> permissions, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** The identity service's reply: whether the person is a member, and the codes held. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Answer(boolean member, List<String> permissions) {}

    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
    private final WebClient client;
    private final InternalTokenProvider tokens;

    /** Builds a client for the identity service from the shared builder (cloned, so its base address is not shared). */
    public IdentityMemberPermissions(
            WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.clone().baseUrl(properties.services().identity()).build();
        this.tokens = tokens;
    }

    // @find: look up member permissions for push
    /**
     * The person's permission codes, from the cache while fresh, else from identity (five second
     * limit). A non-member gets an empty set. Never throws: a failure is logged and answered empty.
     */
    @Override
    public Optional<Set<String>> permissionsOf(UUID orgId, UUID userId) {
        Key key = new Key(orgId, userId);
        Cached cached = cache.get(key);
        if (cached != null && cached.isFresh()) {
            return Optional.of(cached.permissions());
        }
        Set<String> permissions;
        try {
            Answer answer = client.post()
                    .uri("/internal/memberships/permissions")
                    .header("Authorization", "Bearer " + tokens.forService("identity", orgId))
                    .bodyValue(Map.of("orgId", orgId.toString(), "userId", userId.toString()))
                    .retrieve()
                    .bodyToMono(Answer.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            permissions = answer == null || !answer.member() || answer.permissions() == null
                    ? Set.of()
                    : Set.copyOf(answer.permissions());
        } catch (RuntimeException e) {
            log.warn("Could not read the permissions of person {} in workspace {}: {}", userId, orgId, e.getMessage());
            return Optional.empty();
        }
        if (cache.size() >= MAX_CACHED) {
            cache.values().removeIf(entry -> !entry.isFresh());
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(permissions, Instant.now().plusSeconds(TTL_SECONDS)));
        return Optional.of(permissions);
    }
}
