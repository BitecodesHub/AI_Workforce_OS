// @find: working memory, scratchpad, redis, short term memory, ttl, put get all clear scope, task state
// @what: Redis-backed short-lived scratchpad an agent uses while working; degrades if Redis is down.
// @flow: Used for in-progress task state
package os.aiworkforce.memory.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The scratchpad an agent uses while it is working.
 *
 * <p>In Redis rather than Postgres, with a time to live, because this tier is genuinely
 * expendable: it holds the half-finished state of a task in progress, and losing it costs one
 * re-run rather than a record of what happened. Writing it to durable storage would mean a write
 * amplification of hundreds per run for data that is irrelevant an hour later.
 *
 * <p>Redis being unavailable degrades the platform rather than stopping it. An agent without a
 * scratchpad works statelessly within a single step, which is worse but is not an outage - and
 * the caller is told, so the interface can say so rather than quietly producing poorer answers.
 */
@Service
public class WorkingMemory {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemory.class);
    private static final String PREFIX = "aiwos:working:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(4);
    private static final int MAX_VALUE_BYTES = 256 * 1024;

    private final StringRedisTemplate redis;

    public WorkingMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @param degraded true when the store was unreachable and the value was not written */
    public record WriteResult(boolean degraded) {}

    // @find: write working memory, scratchpad set
    public WriteResult put(String orgId, String scope, String key, String value) {
        if (value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            // A scratchpad entry this large is a transcript that belongs in the run trace, where
            // it is queryable, rather than in a cache that will silently drop it.
            throw ApiException.validation("value", "working memory entries are limited to 256 KiB");
        }
        try {
            redis.opsForValue().set(redisKey(orgId, scope, key), value, DEFAULT_TTL);
            return new WriteResult(false);
        } catch (RuntimeException e) {
            log.warn("Working memory unavailable, continuing without it: {}", e.getMessage());
            return new WriteResult(true);
        }
    }

    // @find: read working memory value
    public Optional<String> get(String orgId, String scope, String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(redisKey(orgId, scope, key)));
        } catch (RuntimeException e) {
            log.warn("Working memory unavailable on read: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // @find: read all working memory
    public Map<String, String> all(String orgId, String scope) {
        try {
            String pattern = PREFIX + orgId + ":" + scope + ":*";
            // SCAN rather than KEYS: KEYS blocks the whole server for the length of the scan,
            // which on a shared instance is an outage for everything else using it.
            List<String> keys = new java.util.ArrayList<>();
            try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(pattern)
                    .count(200)
                    .build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) {
                return Map.of();
            }
            List<String> values = redis.opsForValue().multiGet(keys);
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                String shortKey = keys.get(i).substring(pattern.length() - 1);
                String value = values == null ? null : values.get(i);
                if (value != null) {
                    result.put(shortKey, value);
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Working memory unavailable on scan: {}", e.getMessage());
            return Map.of();
        }
    }

    // @find: clear working memory scope
    public void clearScope(String orgId, String scope) {
        try {
            String pattern = PREFIX + orgId + ":" + scope + ":*";
            List<String> keys = new java.util.ArrayList<>();
            try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(pattern)
                    .count(200)
                    .build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            // The entries expire on their own, so a failure here delays cleanup rather than
            // leaking state permanently.
            log.debug("Could not clear working memory scope {}: {}", scope, e.getMessage());
        }
    }

    /** Raised when a caller has asked for working memory that must be durable. */
    static ApiException degraded() {
        return new ApiException(ErrorCode.DEPENDENCY_DEGRADED, "Short-term agent memory is temporarily unavailable.");
    }

    private static String redisKey(String orgId, String scope, String key) {
        return PREFIX + orgId + ":" + scope + ":" + key;
    }
}
