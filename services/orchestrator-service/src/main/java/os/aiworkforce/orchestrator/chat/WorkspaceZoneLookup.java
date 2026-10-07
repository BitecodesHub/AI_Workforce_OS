package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The timezone a workspace schedules and reads "today" in.
 *
 * <p>The one lookup for it: the chat coordinator, the board, the schedules and the date an agent is
 * told all read it from here. {@code ScheduleZoneLookup} is this class under the name the schedule
 * package has always used, and is the one registered as a bean, so there is one cache and one
 * answer. This class carries no stereotype of its own for that reason; a second bean of the same
 * type would be a second cache, and two answers for one workspace.
 *
 * <p>Read from the organisation service's internal workspace endpoint with a service token,
 * following the same pattern as {@code OrgCredentialResolver}: this is platform-to-platform
 * traffic, not something the caller's own permissions decide. Cached for ten minutes, because
 * every schedule suggestion and every board refresh would otherwise cost a call to another
 * service for a value that changes rarely.
 *
 * <p>A workspace that cannot be reached, or has no timezone on record, falls back to UTC. It was
 * Melbourne here and UTC for schedules, so one workspace could be told two different "today"s while
 * the organisation service was down; UTC is the one that is the same for everybody, and it is what
 * a schedule has to fire in when nothing better is known.
 */
public class WorkspaceZoneLookup {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceZoneLookup.class);
    /** Used only when the organisation service cannot be reached, or has no zone on record. */
    private static final ZoneId FALLBACK = ZoneId.of("UTC");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** How long the fallback is kept when the lookup itself failed, so the next call asks again soon. */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    private final WebClient client;
    private final InternalTokenProvider tokens;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public WorkspaceZoneLookup(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().organisation()).build();
        this.tokens = tokens;
    }

    private record Cached(ZoneId zone, String name, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkspaceResponse(UUID id, String name, String slug, String timezone, String status) {}

    public ZoneId zoneFor(UUID orgId) {
        return lookup(orgId).zone();
    }

    /** The workspace's display name, or null when the organisation service cannot say. */
    public String nameFor(UUID orgId) {
        return lookup(orgId).name();
    }

    private Cached lookup(UUID orgId) {
        Cached cached = cache.get(orgId);
        if (cached != null && cached.isFresh()) {
            return cached;
        }
        Duration keep = CACHE_TTL;
        Cached resolved;
        try {
            WorkspaceResponse response = fetch(orgId);
            ZoneId zone = response == null || response.timezone() == null || response.timezone().isBlank()
                    ? FALLBACK
                    : parseOrFallback(response.timezone());
            String name = response == null || response.name() == null || response.name().isBlank()
                    ? null
                    : response.name().strip();
            resolved = new Cached(zone, name, null);
        } catch (RuntimeException e) {
            // A schedule still needs a zone to fire in even when the organisation service is
            // briefly unreachable; UTC is a predictable, honest default rather than a failure. It
            // is only held for a moment, so a blip does not leave a workspace on UTC for ten minutes.
            log.warn("Could not read workspace {}'s timezone; using {} for now: {}", orgId, FALLBACK, e.getMessage());
            resolved = new Cached(FALLBACK, null, null);
            keep = FAILURE_TTL;
        }
        resolved = new Cached(resolved.zone(), resolved.name(), Instant.now().plus(keep));
        cache.put(orgId, resolved);
        return resolved;
    }

    private WorkspaceResponse fetch(UUID orgId) {
        return client.get()
                .uri("/internal/workspaces/{id}", orgId)
                .header("Authorization", "Bearer " + tokens.forService("organisation"))
                .retrieve()
                .bodyToMono(WorkspaceResponse.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    private static ZoneId parseOrFallback(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException notAZone) {
            return FALLBACK;
        }
    }
}
