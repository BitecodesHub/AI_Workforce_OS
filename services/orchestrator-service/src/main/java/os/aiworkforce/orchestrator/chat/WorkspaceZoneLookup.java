package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The timezone a workspace schedules and reads "today" in.
 *
 * <p>Read from the organisation service with a service token, following the same pattern as
 * {@code OrgCredentialResolver}: this is platform-to-platform traffic, not something the caller's
 * own permissions decide. Cached for ten minutes, because every schedule suggestion and every
 * board refresh would otherwise cost a call to another service for a value that changes rarely.
 * A workspace that cannot be reached, or has no timezone on record, falls back to Melbourne -
 * this platform's own home, and a reasonable default for a workspace new enough to have never set
 * one.
 */
@Service
public class WorkspaceZoneLookup {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceZoneLookup.class);
    private static final ZoneId FALLBACK = ZoneId.of("Australia/Melbourne");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final WebClient client;
    private final InternalTokenProvider tokens;
    private final Map<UUID, CachedZone> cache = new ConcurrentHashMap<>();

    public WorkspaceZoneLookup(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().organisation()).build();
        this.tokens = tokens;
    }

    private record CachedZone(ZoneId zone, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private record WorkspaceResponse(UUID id, String name, String slug, String timezone, String status) {}

    public ZoneId zoneFor(UUID orgId) {
        CachedZone cached = cache.get(orgId);
        if (cached != null && cached.isFresh()) {
            return cached.zone();
        }
        ZoneId resolved = fetch(orgId);
        cache.put(orgId, new CachedZone(resolved, Instant.now().plus(CACHE_TTL)));
        return resolved;
    }

    private ZoneId fetch(UUID orgId) {
        try {
            WorkspaceResponse response = client.get()
                    .uri("/api/workspaces/{id}", orgId)
                    .header("Authorization", "Bearer " + tokens.forService("organisation"))
                    .retrieve()
                    .bodyToMono(WorkspaceResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return response == null
                            || response.timezone() == null
                            || response.timezone().isBlank()
                    ? FALLBACK
                    : parseOrFallback(response.timezone());
        } catch (RuntimeException e) {
            log.debug("Workspace {} timezone could not be read; using {}: {}", orgId, FALLBACK, e.getMessage());
            return FALLBACK;
        }
    }

    private static ZoneId parseOrFallback(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException notAZone) {
            return FALLBACK;
        }
    }
}
