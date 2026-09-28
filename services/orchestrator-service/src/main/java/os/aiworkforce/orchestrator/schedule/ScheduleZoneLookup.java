package os.aiworkforce.orchestrator.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
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
 * The timezone a workspace schedules in.
 *
 * <p>Named for this package rather than shared, the way {@code OrgCredentialResolver} is not:
 * the chat coordinator needs the same fact and is expected to own an equivalent copy of its own,
 * so that two packages built in parallel never contend for one file. A workspace's timezone
 * changes rarely if ever, so it is cached rather than fetched on every schedule created or swept.
 */
@Component
public class ScheduleZoneLookup {

    private static final Logger log = LoggerFactory.getLogger(ScheduleZoneLookup.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** Used only when the organisation service cannot be reached at all. */
    private static final ZoneId FALLBACK = ZoneId.of("UTC");

    private record Cached(ZoneId zone, Instant fetchedAt) {
        boolean isFresh() {
            return Instant.now().isBefore(fetchedAt.plus(CACHE_TTL));
        }
    }

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final WebClient client;
    private final InternalTokenProvider tokens;

    public ScheduleZoneLookup(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().organisation()).build();
        this.tokens = tokens;
    }

    public ZoneId zoneFor(UUID orgId) {
        Cached cached = cache.get(orgId);
        if (cached != null && cached.isFresh()) {
            return cached.zone();
        }
        ZoneId zone = fetch(orgId);
        cache.put(orgId, new Cached(zone, Instant.now()));
        return zone;
    }

    private ZoneId fetch(UUID orgId) {
        try {
            WorkspaceView view = client.get()
                    .uri("/api/workspaces/{id}", orgId)
                    .header("Authorization", "Bearer " + tokens.forService("organisation"))
                    .retrieve()
                    .bodyToMono(WorkspaceView.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            if (view == null || view.timezone() == null || view.timezone().isBlank()) {
                return FALLBACK;
            }
            return ZoneId.of(view.timezone());
        } catch (RuntimeException e) {
            // A schedule still needs a zone to fire in even when the organisation service is
            // briefly unreachable; UTC is a predictable, honest default rather than a failure.
            log.warn("Could not read workspace {}'s timezone; scheduling in UTC for now", orgId, e);
            return FALLBACK;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkspaceView(UUID id, String name, String slug, String timezone, String status) {}
}
