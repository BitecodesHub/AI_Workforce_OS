package os.aiworkforce.platform.web.audit;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuditOutbox} over the service's own {@code audit_outbox} table.
 *
 * <p>Plain JDBC rather than a JPA entity, on purpose. Every business service shares this class, and
 * a shared entity would have to be found by each service's entity scan and checked against each
 * schema; a statement against one small table needs neither. {@link JdbcTemplate} takes part in the
 * transaction the caller already has - Spring's JPA transaction manager shares its connection with
 * JDBC code on the same data source - which is what makes the row commit with the change it
 * describes. The table is created by each service's own migration, in its own schema.
 */
public class JdbcAuditOutbox implements AuditOutbox {

    private static final String CLAIM = """
            UPDATE audit_outbox SET next_attempt_at = now() + (? * interval '1 second')
            WHERE id IN (
                SELECT id FROM audit_outbox
                WHERE next_attempt_at <= now()
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id, org_id, actor_id, actor_kind, on_behalf_of, action, resource_type, resource_id,
                      outcome, detail::text AS detail, request_id, occurred_at, attempts
            """;

    private final JdbcTemplate jdbc;

    public JdbcAuditOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Event event) {
        jdbc.update(
                """
                INSERT INTO audit_outbox (id, org_id, actor_id, actor_kind, on_behalf_of, action, resource_type,
                                          resource_id, outcome, detail, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                ps -> {
                    ps.setObject(1, event.id());
                    setUuid(ps, 2, event.orgId());
                    ps.setString(3, event.actorId());
                    ps.setString(4, event.actorKind());
                    setText(ps, 5, event.onBehalfOf());
                    ps.setString(6, event.action());
                    ps.setString(7, event.resourceType());
                    setText(ps, 8, event.resourceId());
                    ps.setString(9, event.outcome());
                    ps.setString(10, event.detailJson());
                    setText(ps, 11, event.requestId());
                    ps.setObject(12, OffsetDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC));
                });
    }

    @Override
    public List<Event> claimDue(int limit, Duration lease) {
        // One statement claims and returns, so two instances cannot both take a row: SKIP LOCKED
        // passes over rows another instance is claiming, and the pushed-out next_attempt_at hides
        // the row from everyone else for the length of the lease. A claim is committed on its own,
        // so an instance that dies mid-send leaves the row to be found again when the lease ends.
        List<Event> claimed = jdbc.query(
                CLAIM,
                ps -> {
                    ps.setLong(1, Math.max(1, lease.toSeconds()));
                    ps.setInt(2, limit);
                },
                (rs, row) -> new Event(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("actor_id"),
                        rs.getString("actor_kind"),
                        rs.getString("on_behalf_of"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("outcome"),
                        rs.getString("detail"),
                        rs.getString("request_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")));
        // RETURNING has no order; delivery should follow the order things happened in.
        return claimed.stream()
                .sorted(java.util.Comparator.comparing(Event::occurredAt).thenComparing(Event::id))
                .toList();
    }

    @Override
    public void delivered(UUID id) {
        jdbc.update("DELETE FROM audit_outbox WHERE id = ?", id);
    }

    @Override
    public void failed(UUID id, Duration retryAfter, String error) {
        jdbc.update(
                """
                UPDATE audit_outbox
                SET attempts = attempts + 1,
                    next_attempt_at = now() + (? * interval '1 second'),
                    last_error = ?
                WHERE id = ?
                """,
                ps -> {
                    ps.setLong(1, Math.max(1, retryAfter.toSeconds()));
                    ps.setString(2, error == null ? null : error.substring(0, Math.min(error.length(), 500)));
                    ps.setObject(3, id);
                });
    }

    @Override
    public void release(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE audit_outbox SET next_attempt_at = now() WHERE id = ANY(?)", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        });
    }

    @Override
    public long pending() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_outbox", Long.class);
        return count == null ? 0 : count;
    }

    private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    private static void setText(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
