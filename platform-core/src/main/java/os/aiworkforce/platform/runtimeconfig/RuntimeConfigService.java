// @find: runtime config, runtime settings, operator settings, change setting without deploy, workspace override, platform value, default, cache, set setting, clear setting, effective settings
// @what: Reads and changes runtime settings, resolving workspace override, then platform value, then default.
// @flow: Backed by a RuntimeConfigStore; each service has a runtime_settings table
package os.aiworkforce.platform.runtimeconfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Reads the settings an operator can change without a deployment.
 *
 * <p>Resolution runs narrowest first: a workspace override, then the platform value, then the
 * key's declared default. A key with no stored value anywhere still answers, which is what lets
 * the platform start with an empty settings table.
 *
 * <p>Three caches sit in front of the database, and each exists for a different failure:
 *
 * <ul>
 *   <li>A per-process map, so a value read inside a loop costs nothing.
 *   <li>Redis, so eight services share one read rather than each hitting the database.
 *   <li>A published invalidation message, so a change made in one service is seen by the other
 *       seven within milliseconds rather than after a cache expiry.
 * </ul>
 *
 * <p>When Redis is unreachable the service reads through to the database rather than serving a
 * default. Serving a default would be the worst outcome available: an operator who has disabled
 * a provider during an incident would find it quietly re-enabled by a cache outage.
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);
    private static final String CACHE_PREFIX = "aiwos:config:";

    private final Map<String, ConfigKey> registry = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> local = new ConcurrentHashMap<>();
    private final ObjectProvider<RuntimeConfigStore> store;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final PlatformProperties.RuntimeConfig config;
    private final ObjectMapper json;

    private record CachedValue(String value, long expiresAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    public RuntimeConfigService(
            ObjectProvider<RuntimeConfigStore> store,
            ObjectProvider<StringRedisTemplate> redis,
            PlatformProperties properties,
            ObjectMapper json) {
        this.store = store;
        this.redis = redis;
        this.config = properties.runtimeConfig();
        this.json = json;
    }

    /**
     * Declares the keys a module owns.
     *
     * <p>Registration is what makes the settings screen possible: it is the only list of what can
     * be changed. A key read without being registered is a programming error and is refused, so a
     * typo produces a startup failure rather than a silent default.
     */
    public void register(List<ConfigKey> keys) {
        for (ConfigKey key : keys) {
            ConfigKey existing = registry.putIfAbsent(key.name(), key);
            if (existing != null && !existing.equals(key)) {
                throw new IllegalStateException("Duplicate runtime configuration key: " + key.name());
            }
        }
    }

    public Map<String, ConfigKey> registry() {
        return Map.copyOf(registry);
    }

    public ConfigKey key(String name) {
        ConfigKey key = registry.get(name);
        if (key == null) {
            throw new IllegalStateException("Runtime configuration key is not registered: " + name);
        }
        return key;
    }

    // ---- Typed reads ---------------------------------------------------------------------

    public boolean getBoolean(ConfigKey key, String orgId) {
        return Boolean.parseBoolean(resolve(key, orgId));
    }

    public int getInt(ConfigKey key, String orgId) {
        return Integer.parseInt(resolve(key, orgId).trim());
    }

    public double getDouble(ConfigKey key, String orgId) {
        return Double.parseDouble(resolve(key, orgId).trim());
    }

    public Duration getDuration(ConfigKey key, String orgId) {
        return Duration.parse(resolve(key, orgId).trim());
    }

    public String getString(ConfigKey key, String orgId) {
        return resolve(key, orgId);
    }

    public List<String> getList(ConfigKey key, String orgId) {
        String raw = resolve(key, orgId);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split("\\s*,\\s*"));
    }

    public JsonNode getJson(ConfigKey key, String orgId) {
        try {
            return json.readTree(resolve(key, orgId));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Setting " + key.name() + " does not hold valid JSON.", e);
        }
    }

    // ---- Writes --------------------------------------------------------------------------

    /**
     * Stores a value after checking it against the key's declared bounds.
     *
     * <p>Validation happens here rather than in the console, because the console is not the only
     * writer: a migration, a support script or another service can call this, and each of them
     * would otherwise need its own copy of the rules.
     */
    // @find: change runtime setting, set operator setting
    public void set(String keyName, String orgId, String value, String actorId) {
        ConfigKey key = key(keyName);
        if (orgId != null && !key.workspaceOverridable()) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "That setting is set for the whole platform.");
        }
        validate(key, value);
        store.getObject().write(keyName, orgId, value, actorId);
        invalidate(keyName, orgId);
        log.info("Runtime setting {} changed for {} by {}", keyName, orgId == null ? "platform" : orgId, actorId);
    }

    // @find: clear runtime setting override
    public void clear(String keyName, String orgId, String actorId) {
        ConfigKey key = key(keyName);
        store.getObject().clear(key.name(), orgId, actorId);
        invalidate(keyName, orgId);
    }

    private void validate(ConfigKey key, String value) {
        if (value == null) {
            throw ApiException.validation(key.name(), "must not be empty");
        }
        try {
            switch (key.type()) {
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw ApiException.validation(key.name(), "must be true or false");
                    }
                }
                case INTEGER -> checkRange(key, Long.parseLong(value.trim()));
                case DECIMAL -> checkRange(key, Double.parseDouble(value.trim()));
                case DURATION -> checkRange(key, Duration.parse(value.trim()).toMillis());
                case ENUM -> {
                    if (!key.allowedValues().contains(value)) {
                        throw ApiException.validation(
                                key.name(), "must be one of " + String.join(", ", key.allowedValues()));
                    }
                }
                case JSON -> json.readTree(value);
                case STRING, STRING_LIST -> {
                    /* Any text is acceptable; bounds do not apply. */
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.validation(
                    key.name(), "is not a valid " + key.type().name().toLowerCase());
        }
    }

    private void checkRange(ConfigKey key, double parsed) {
        if (key.minimum() != null && parsed < key.minimum().doubleValue()) {
            throw ApiException.validation(key.name(), "must be at least " + key.minimum());
        }
        if (key.maximum() != null && parsed > key.maximum().doubleValue()) {
            throw ApiException.validation(key.name(), "must be at most " + key.maximum());
        }
    }

    // ---- Resolution ----------------------------------------------------------------------

    private String resolve(ConfigKey key, String orgId) {
        if (!config.enabled()) {
            return String.valueOf(key.defaultValue());
        }
        if (orgId != null && key.workspaceOverridable()) {
            String scoped = cached(key.name(), orgId);
            if (scoped != null) {
                return scoped;
            }
        }
        String platform = cached(key.name(), null);
        return platform != null ? platform : String.valueOf(key.defaultValue());
    }

    private String cached(String keyName, String orgId) {
        String cacheKey = CACHE_PREFIX + (orgId == null ? "platform" : orgId) + ":" + keyName;

        CachedValue inProcess = local.get(cacheKey);
        if (inProcess != null && inProcess.isFresh()) {
            return inProcess.value();
        }

        String value = fromRedis(cacheKey);
        if (value == null) {
            value = fromStore(keyName, orgId);
            if (value != null) {
                toRedis(cacheKey, value);
            }
        }

        local.put(
                cacheKey,
                new CachedValue(
                        value, System.currentTimeMillis() + config.cacheTtl().toMillis()));
        return value;
    }

    private String fromRedis(String cacheKey) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return null;
        }
        try {
            return template.opsForValue().get(cacheKey);
        } catch (RuntimeException e) {
            // A cache outage must not become a configuration outage; fall through to the store.
            log.warn("Runtime configuration cache unavailable, reading through: {}", e.getMessage());
            return null;
        }
    }

    private void toRedis(String cacheKey, String value) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().set(cacheKey, value, config.cacheTtl());
        } catch (RuntimeException e) {
            log.debug("Could not populate runtime configuration cache: {}", e.getMessage());
        }
    }

    private String fromStore(String keyName, String orgId) {
        RuntimeConfigStore backing = store.getIfAvailable();
        if (backing == null) {
            if (!config.readThroughOnCacheFailure()) {
                throw new ApiException(ErrorCode.DEPENDENCY_DEGRADED, "Settings are temporarily unreadable.");
            }
            return null;
        }
        return backing.read(keyName, orgId).orElse(null);
    }

    /** Drops the value here and tells the other services to drop theirs. */
    private void invalidate(String keyName, String orgId) {
        String cacheKey = CACHE_PREFIX + (orgId == null ? "platform" : orgId) + ":" + keyName;
        local.remove(cacheKey);
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.delete(cacheKey);
            template.convertAndSend(config.invalidationChannel(), cacheKey);
        } catch (RuntimeException e) {
            // The cache entry still expires on its own, so the change lands either way; it simply
            // takes the time to live rather than being immediate.
            log.warn("Could not broadcast settings invalidation for {}: {}", keyName, e.getMessage());
        }
    }

    /** Called by the subscriber when another service changes a value. */
    public void onInvalidation(String cacheKey) {
        local.remove(cacheKey);
    }

    /** Every setting visible to a workspace, with its effective value and where it came from. */
    // @find: list effective settings for workspace
    public List<EffectiveSetting> effectiveSettings(String orgId) {
        return registry.values().stream()
                .filter(key -> orgId == null || key.workspaceOverridable())
                .sorted(java.util.Comparator.comparing(ConfigKey::name))
                .map(key -> {
                    String workspace = orgId == null ? null : fromStore(key.name(), orgId);
                    String platform = fromStore(key.name(), null);
                    String source = workspace != null ? "workspace" : platform != null ? "platform" : "default";
                    String value = workspace != null
                            ? workspace
                            : platform != null ? platform : String.valueOf(key.defaultValue());
                    return new EffectiveSetting(
                            key.name(),
                            key.sensitive() ? "********" : value,
                            source,
                            key.description(),
                            key.jsonSchema());
                })
                .toList();
    }

    public record EffectiveSetting(
            String key, String value, String source, String description, Map<String, Object> schema) {}

    public Optional<ConfigKey> find(String name) {
        return Optional.ofNullable(registry.get(name));
    }
}
