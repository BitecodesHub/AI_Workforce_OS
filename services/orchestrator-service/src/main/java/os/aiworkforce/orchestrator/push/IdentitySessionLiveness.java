// @find: identity sessions client, POST /internal/sessions/live, live session cache, signed in device check, IdentitySessionLiveness
// @what: Asks the identity service which sign-in sessions are still live, in one call per batch, with a short cache.
// @flow: Implements SessionLiveness for PushService; calls identity /internal/sessions/live with a service token from InternalTokenProvider
package os.aiworkforce.orchestrator.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * The identity service's answer to "is this device still signed in".
 *
 * <p>An answer is reused for {@value #TTL_SECONDS} seconds, so a signed-out phone stops receiving
 * within half a minute, and a burst of approvals asks identity once. Only the sessions not already
 * known are asked about, in batches of {@value #BATCH}. A failure is reported as no answer and
 * remembered for {@value #FAILURE_PAUSE_SECONDS} seconds, so an identity outage costs one timeout
 * per pause rather than one per approval. The cache is bounded.
 */
@Component
public class IdentitySessionLiveness implements SessionLiveness {

    private static final Logger log = LoggerFactory.getLogger(IdentitySessionLiveness.class);

    /** How long an answer is reused, in seconds. */
    static final long TTL_SECONDS = 30;
    /** How long a failed call keeps identity from being asked again, in seconds. */
    static final long FAILURE_PAUSE_SECONDS = 10;
    /** The most sessions asked about in one call; identity accepts this many. */
    static final int BATCH = 500;

    private static final int MAX_CACHED = 5_000;

    private record Cached(boolean live, Instant expiresAt) {}

    /** The identity service's reply: which of the sessions asked about are live. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Answer(List<UUID> live) {}

    /** Asks identity about one batch; replaceable in tests. */
    @FunctionalInterface
    interface Lookup {
        Answer ask(List<UUID> sessionIds);
    }

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final Lookup lookup;
    private final Clock clock;
    private volatile Instant pausedUntil = Instant.MIN;

    /** Builds a client for the identity service from the shared builder (cloned, so its base address is not shared). */
    @Autowired
    public IdentitySessionLiveness(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this(httpLookup(builder.clone().baseUrl(properties.services().identity()).build(), tokens), Clock.systemUTC());
    }

    /** For tests: the lookup and the clock. */
    IdentitySessionLiveness(Lookup lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /*
     * The real call: POST /internal/sessions/live, five seconds at most. The token is the
     * platform's own whoever is calling - the test button runs on a person's request, and identity
     * answers this only to a service.
     */
    private static Lookup httpLookup(WebClient client, InternalTokenProvider tokens) {
        return sessionIds -> client.post()
                .uri("/internal/sessions/live")
                .header("Authorization", "Bearer " + RequestContext.as(Actor.SYSTEM, () -> tokens.forService("identity")))
                .bodyValue(Map.of("sessionIds", sessionIds))
                .retrieve()
                .bodyToMono(Answer.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    // @find: which sessions are still signed in
    /**
     * The live ones among {@code sessionIds}: from the cache while fresh, else from identity. Never
     * throws; a failure is logged and answered empty, and identity is left alone for a few seconds.
     */
    @Override
    public Optional<Set<UUID>> live(Collection<UUID> sessionIds) {
        Instant now = clock.instant();
        Set<UUID> live = new HashSet<>();
        Set<UUID> unknown = new LinkedHashSet<>();
        for (UUID sessionId : sessionIds) {
            if (sessionId == null) {
                continue;
            }
            Cached cached = cache.get(sessionId);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                if (cached.live()) {
                    live.add(sessionId);
                }
            } else {
                unknown.add(sessionId);
            }
        }
        if (unknown.isEmpty()) {
            return Optional.of(live);
        }
        if (now.isBefore(pausedUntil)) {
            return Optional.empty();
        }
        List<UUID> ask = new ArrayList<>(unknown);
        Set<UUID> answered = new HashSet<>();
        try {
            for (int from = 0; from < ask.size(); from += BATCH) {
                Answer answer = lookup.ask(ask.subList(from, Math.min(ask.size(), from + BATCH)));
                if (answer != null && answer.live() != null) {
                    answered.addAll(answer.live());
                }
            }
        } catch (RuntimeException e) {
            pausedUntil = now.plusSeconds(FAILURE_PAUSE_SECONDS);
            log.warn("Could not ask identity which of {} sign-in session(s) are live: {}", ask.size(), e.getMessage());
            return Optional.empty();
        }
        remember(ask, answered, now);
        for (UUID sessionId : ask) {
            if (answered.contains(sessionId)) {
                live.add(sessionId);
            }
        }
        return Optional.of(live);
    }

    // Keeps each answer for the TTL, emptying stale entries first when the cache is full.
    private void remember(List<UUID> asked, Set<UUID> live, Instant now) {
        if (cache.size() + asked.size() > MAX_CACHED) {
            cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            if (cache.size() + asked.size() > MAX_CACHED) {
                cache.clear();
            }
        }
        Instant expires = now.plusSeconds(TTL_SECONDS);
        for (UUID sessionId : asked) {
            cache.put(sessionId, new Cached(live.contains(sessionId), expires));
        }
    }
}
