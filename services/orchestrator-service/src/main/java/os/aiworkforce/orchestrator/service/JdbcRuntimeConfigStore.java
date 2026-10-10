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

    public JdbcRuntimeConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // @find: read setting
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

    private static UUID uuid(String orgId) {
        return UUID.fromString(orgId);
    }
}
