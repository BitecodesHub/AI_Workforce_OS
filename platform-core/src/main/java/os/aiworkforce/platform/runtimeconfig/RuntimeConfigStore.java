// @find: runtime config store, settings storage, runtime_settings table, store interface
// @what: Interface for where runtime settings are stored.
// @flow: Implemented by the orchestrator's JdbcRuntimeConfigStore (a service without a store bean serves defaults); used by RuntimeConfigService
package os.aiworkforce.platform.runtimeconfig;

import java.util.List;
import java.util.Optional;

/**
 * Where runtime settings are stored.
 *
 * <p>Kept as an interface so {@link RuntimeConfigService} can be used by a service that owns the
 * settings table and by one that reads them over HTTP, without either knowing which it is.
 *
 * <p>The only implementation at present is {@code JdbcRuntimeConfigStore} in the orchestrator
 * service, which backs it with the {@code runtime_settings} table. {@link RuntimeConfigService} is
 * the sole caller. A {@code null} organisation identifier addresses the platform-wide value rather
 * than a workspace override.
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

    /**
     * One stored override as the settings screen lists it.
     *
     * @param key dotted setting name
     * @param orgId the workspace the override belongs to, or {@code null} for the platform value
     * @param value the stored text, parsed by the caller according to the key's type
     * @param updatedAt when the override was last written
     * @param updatedBy identifier of the actor who last wrote it
     */
    record StoredValue(String key, String orgId, String value, java.time.Instant updatedAt, String updatedBy) {}
}
