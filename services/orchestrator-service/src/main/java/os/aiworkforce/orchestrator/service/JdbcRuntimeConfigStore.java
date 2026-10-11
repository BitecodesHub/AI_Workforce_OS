// @find: runtime settings, runtime_settings table, workspace settings store, read write clear setting, config store, feature flags per workspace
// @what: Stores this service's runtime settings in its own runtime_settings table.
// @flow: Used by platform RuntimeConfigService
package os.aiworkforce.orchestrator.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.runtimeconfig.RuntimeConfigStore;

/**
 * Where this service's runtime settings live: the {@code runtime_settings} table in its own
 * schema.
 *
 * <p>{@link os.aiworkforce.platform.runtimeconfig.RuntimeConfigService} reads and writes through
 * whatever store a service provides, and this service had none: a setting it declared could be
 * read, but only ever as its default, and no workspace could change one. Plain SQL rather than an
 * entity, because the table has no id and its two unique indexes - one for the platform's value,
 * one per workspace - are exactly what an upsert needs to name.
 */
@Component
public class JdbcRuntimeConfigStore implements RuntimeConfigStore {

    private final JdbcTemplate jdbc;

    /**
     * Uses the service's own JDBC template, so every statement runs against this service's schema.
     *
     * @param jdbc the Spring JDBC template
     */
    public JdbcRuntimeConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // @find: read setting
    /**
     * Reads one stored value.
     *
     * @param key the setting key
     * @param orgId the workspace id as text, or null for the platform-wide value
     * @return the stored text, or empty when nothing is saved at that level (the caller then uses its default)
     */
    @Override
    public Optional<String> read(String key, String orgId) {
        List<String> values = orgId == null
                ? jdbc.queryForList(
                        "select value from runtime_settings where key = ? and org_id is null", String.class, key)
                : jdbc.queryForList(
                        "select value from runtime_settings where key = ? and org_id = ?",
                        String.class,
                        key,
                        uuid(orgId));
        return values.stream().findFirst();
    }

    // @find: write setting, save setting
    /**
     * Saves a value, replacing any earlier one at the same level.
     *
     * <p>Reads the current value, then upserts: the platform row (null workspace) and each workspace row
     * have their own unique index, which the two statements name. Side effects: one insert or update,
     * stamping the time and {@code actorId}. The read and the write are separate statements, so the
     * returned previous value can be stale under a concurrent write; it is used for audit only.
     *
     * @param key the setting key
     * @param orgId the workspace id as text, or null for the platform-wide value
     * @param value the new value, as text
     * @param actorId who made the change, stored in {@code updated_by}
     * @return the value that was stored before, or empty
     */
    @Override
    public Optional<String> write(String key, String orgId, String value, String actorId) {
        Optional<String> previous = read(key, orgId);
        if (orgId == null) {
            jdbc.update(
                    """
                    insert into runtime_settings (key, org_id, value, updated_at, updated_by)
                    values (?, null, ?, now(), ?)
                    on conflict (key) where org_id is null
                    do update set value = excluded.value, updated_at = now(), updated_by = excluded.updated_by
                    """,
                    key,
                    value,
                    actorId);
        } else {
            jdbc.update(
                    """
                    insert into runtime_settings (key, org_id, value, updated_at, updated_by)
                    values (?, ?, ?, now(), ?)
                    on conflict (key, org_id) where org_id is not null
                    do update set value = excluded.value, updated_at = now(), updated_by = excluded.updated_by
                    """,
                    key,
                    uuid(orgId),
                    value,
                    actorId);
        }
        return previous;
    }

    // @find: clear setting, reset to default
    /**
     * Removes a saved value so the setting falls back to the level below it or to its default.
     *
     * <p>Side effect: deletes at most one row. Nothing is recorded about who cleared it; {@code actorId}
     * is accepted to satisfy the interface and is not stored.
     *
     * @param key the setting key
     * @param orgId the workspace id as text, or null for the platform-wide value
     * @param actorId who made the change
     * @return the value that was removed, or empty when there was none
     */
    @Override
    public Optional<String> clear(String key, String orgId, String actorId) {
        Optional<String> previous = read(key, orgId);
        if (orgId == null) {
            jdbc.update("delete from runtime_settings where key = ? and org_id is null", key);
        } else {
            jdbc.update("delete from runtime_settings where key = ? and org_id = ?", key, uuid(orgId));
        }
        return previous;
    }

    // @find: read all settings for workspace
    /**
     * Every value saved for one workspace, ordered by key, with when and by whom each was last changed.
     * Platform-wide rows are not included. Read only.
     *
     * @param orgId the workspace id as text; must be a valid UUID
     * @return the stored values; empty when the workspace has overridden nothing
     */
    @Override
    public List<StoredValue> readAll(String orgId) {
        return jdbc.query(
                "select key, org_id, value, updated_at, updated_by from runtime_settings where org_id = ? order by key",
                (rows, index) -> {
                    Timestamp updated = rows.getTimestamp("updated_at");
                    return new StoredValue(
                            rows.getString("key"),
                            rows.getString("org_id"),
                            rows.getString("value"),
                            updated == null ? null : updated.toInstant(),
                            rows.getString("updated_by"));
                },
                uuid(orgId));
    }

    // The org_id column is a UUID, so a malformed id fails here with IllegalArgumentException.
    private static UUID uuid(String orgId) {
        return UUID.fromString(orgId);
    }
}
