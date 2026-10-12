// @find: identity permissions client, POST /internal/memberships/permissions, POST /internal/memberships/permissions/batch, member permissions cache, IdentityMemberPermissions
// @what: Asks the identity service what people hold in a workspace, many at once, with a short cache and a pause after a failure.
// @flow: Implements MemberPermissions for PushRecipients; calls identity /internal/memberships/permissions/batch with a service token from InternalTokenProvider
package os.aiworkforce.orchestrator.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The identity service's answer to "what do these people hold here".
 *
 * <p>Asked once for up to {@value #BATCH} people, so choosing who to tell about an approval costs
 * identity one call however many people have push turned on. Each answer is held for {@value
 * #TTL_SECONDS} seconds per person and workspace, so a burst of approvals mostly reads the cache,
 * and a removed permission stops push within that time (well inside the five minutes an access
 * token already carries one). A failed call leaves those people out and keeps identity from being
 * asked again about that workspace for {@value #FAILURE_PAUSE_SECONDS} seconds, so an outage costs
 * one timeout per pause instead of one per approval. The cache is bounded.
 */
@Component
public class IdentityMemberPermissions implements MemberPermissions {

    private static final Logger log = LoggerFactory.getLogger(IdentityMemberPermissions.class);

    /** How long an answer is reused, in seconds. */
    static final long TTL_SECONDS = 30;
    /** How long a failed call keeps identity from being asked about that workspace again, in seconds. */
    static final long FAILURE_PAUSE_SECONDS = 10;
    /** The most people asked about in one call; identity accepts this many. */
    static final int BATCH = 200;

    private static final int MAX_CACHED = 5_000;

    private record Key(UUID orgId, UUID userId) {}

    private record Cached(Set<String> permissions, Instant expiresAt) {}

    /** The identity service's reply to the batch: the codes of each active member asked about; others are absent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchAnswer(Map<UUID, List<String>> permissions) {}

    /** Asks identity about one batch of people in one workspace; replaceable in tests. */
    @FunctionalInterface
    interface Lookup {
        BatchAnswer ask(UUID orgId, List<UUID> userIds);
    }

    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> pausedUntil = new ConcurrentHashMap<>();
    private final Lookup lookup;
    private final Clock clock;

    /** Builds a client for the identity service from the shared builder (cloned, so its base address is not shared). */
    @Autowired
    public IdentityMemberPermissions(
            WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this(httpLookup(builder.clone().baseUrl(properties.services().identity()).build(), tokens), Clock.systemUTC());
    }

    /** For tests: the lookup and the clock. */
    IdentityMemberPermissions(Lookup lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    // The real call: POST /internal/memberships/permissions/batch with a service token, five seconds at most.
    private static Lookup httpLookup(WebClient client, InternalTokenProvider tokens) {
        return (orgId, userIds) -> client.post()
                .uri("/internal/memberships/permissions/batch")
                .header("Authorization", "Bearer " + tokens.forService("identity", orgId))
                .bodyValue(Map.of("orgId", orgId.toString(), "userIds", userIds))
                .retrieve()
                .bodyToMono(BatchAnswer.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    // @find: look up member permissions for push
    /** One person's codes, through the batch. Never throws. */
    @Override
    public Optional<Set<String>> permissionsOf(UUID orgId, UUID userId) {
        return Optional.ofNullable(permissionsOf(orgId, List.of(userId)).get(userId));
    }

    // @find: look up many members' permissions for push
    /**
     * The codes of each person asked about: from the cache while fresh, the rest from identity in
     * batches. A non-member gets an empty set. People identity could not be asked about are left
     * out of the map. Never throws: a failure is logged.
     */
    @Override
    public Map<UUID, Set<String>> permissionsOf(UUID orgId, Collection<UUID> userIds) {
        Instant now = clock.instant();
        Map<UUID, Set<String>> held = new LinkedHashMap<>();
        Set<UUID> unknown = new LinkedHashSet<>();
        for (UUID userId : userIds) {
            Cached cached = cache.get(new Key(orgId, userId));
            if (cached != null && now.isBefore(cached.expiresAt())) {
                held.put(userId, cached.permissions());
            } else {
                unknown.add(userId);
            }
        }
        if (unknown.isEmpty() || now.isBefore(pausedUntil.getOrDefault(orgId, Instant.MIN))) {
            return held;
        }
        List<UUID> ask = new ArrayList<>(unknown);
        for (int from = 0; from < ask.size(); from += BATCH) {
            List<UUID> batch = ask.subList(from, Math.min(ask.size(), from + BATCH));
            BatchAnswer answer;
            try {
                answer = lookup.ask(orgId, batch);
            } catch (RuntimeException e) {
                pausedUntil.put(orgId, now.plusSeconds(FAILURE_PAUSE_SECONDS));
                log.warn("Could not read the permissions of {} person(s) in workspace {}: {}", batch.size(), orgId, e.getMessage());
                return held;
            }
            Map<UUID, List<String>> codes = answer == null || answer.permissions() == null ? Map.of() : answer.permissions();
            makeRoom(batch.size(), now);
            Instant expires = now.plusSeconds(TTL_SECONDS);
            for (UUID userId : batch) {
                List<String> theirs = codes.get(userId);
                Set<String> permissions = theirs == null ? Set.of() : Set.copyOf(theirs);
                cache.put(new Key(orgId, userId), new Cached(permissions, expires));
                held.put(userId, permissions);
            }
        }
        pausedUntil.remove(orgId);
        return held;
    }

    // Empties stale entries first, and everything when that is not enough, before adding more.
    private void makeRoom(int adding, Instant now) {
        if (cache.size() + adding <= MAX_CACHED) {
            return;
        }
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        if (cache.size() + adding > MAX_CACHED) {
            cache.clear();
        }
        if (pausedUntil.size() > MAX_CACHED) {
            pausedUntil.clear();
        }
    }
}
