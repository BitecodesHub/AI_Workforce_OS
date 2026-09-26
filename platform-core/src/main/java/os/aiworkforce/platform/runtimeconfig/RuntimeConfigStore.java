package os.aiworkforce.platform.runtimeconfig;

import java.util.List;
import java.util.Optional;

/**
 * Where runtime settings are stored.
 *
 * <p>Kept as an interface so {@link RuntimeConfigService} can be used by a service that owns the
 * settings table and by one that reads them over HTTP, without either knowing which it is.
 */
public interface RuntimeConfigStore {

    /** The stored value for a key, or empty when nothing has been set. */
    Optional<String> read(String key, String orgId);

    /** Stores a value, returning the previous one so the change can be audited. */
    Optional<String> write(String key, String orgId, String value, String actorId);

    /** Removes an override, so the key falls back to the platform value or its default. */
    Optional<String> clear(String key, String orgId, String actorId);

    /** Every stored override for a workspace, for the settings screen. */
    List<StoredValue> readAll(String orgId);

    record StoredValue(String key, String orgId, String value, java.time.Instant updatedAt, String updatedBy) {}
}
