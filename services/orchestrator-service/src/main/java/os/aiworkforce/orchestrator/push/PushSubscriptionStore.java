// @find: push subscriptions store, save push subscription, delete push subscription, list my devices, push_subscriptions table, subscribed users, record push failure, record push success, PushSubscriptionStore
// @what: Reads and writes push_subscriptions: one row per browser and workspace that agreed to receive push notifications.
// @flow: Used by PushService (subscribe, unsubscribe, remove a device, list, recipients, ended sessions) and PushDelivery (success, failure, removal)
package os.aiworkforce.orchestrator.push;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Plain SQL over {@code push_subscriptions}.
 *
 * <p>Plain SQL rather than an entity because the writes that matter are single statements: an
 * upsert on the workspace and endpoint, a counter bumped in place, a delete scoped to its owner.
 * Each statement commits on its own; nothing here is part of a larger transaction, and delivery
 * runs on threads that have none.
 *
 * <p>Every read and delete that a person can cause names their workspace and their own id, so one
 * person can never list, or remove, another's device. A row never moves to another workspace, and
 * moves to another person only when that person proves they hold the same browser subscription.
 */
@Component
public class PushSubscriptionStore {

    /**
     * One browser that agreed to receive push notifications for one person in one workspace.
     *
     * @param sessionId the identity session that last saved it; null when none was known
     */
    public record PushSubscription(
            UUID id,
            UUID orgId,
            UUID userId,
            String endpoint,
            String p256dh,
            String auth,
            String label,
            UUID sessionId,
            Instant createdAt,
            Instant lastSuccessAt,
            int failureCount) {

        @Override
        public String toString() {
            // The endpoint is a capability: whoever holds it (and the keys) can reach the device.
            return "PushSubscription[id=" + id + ", userId=" + userId + "]";
        }
    }

    private static final String COLUMNS =
            "id, org_id, user_id, endpoint, p256dh, auth, user_agent_label, session_id, created_at, last_success_at, failure_count";

    /*
     * A browser keeps one endpoint per site, so the workspace and the endpoint decide the row:
     * subscribing again from the same browser refreshes its keys and its session. The row passes
     * to a different person only when they present the very keys it already holds - the same
     * browser subscription, as on a shared computer where the next person turns push on. Anybody
     * else who somehow learnt the endpoint changes nothing, and no row is returned.
     */
    private static final String UPSERT =
            """
            insert into push_subscriptions (id, org_id, user_id, endpoint, p256dh, auth, user_agent_label, session_id)
            values (:id, :orgId, :userId, :endpoint, :p256dh, :auth, :label, :sessionId)
            on conflict (org_id, endpoint) do update
               set user_id = excluded.user_id,
                   p256dh = excluded.p256dh,
                   auth = excluded.auth,
                   user_agent_label = excluded.user_agent_label,
                   session_id = excluded.session_id,
                   failure_count = 0
             where push_subscriptions.user_id = excluded.user_id
                or (push_subscriptions.p256dh = excluded.p256dh and push_subscriptions.auth = excluded.auth)
            returning\s"""
                    + COLUMNS;

    private static final RowMapper<PushSubscription> ROW = PushSubscriptionStore::map;

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    /** Wires the shared JDBC template. */
    public PushSubscriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    // @find: save push subscription, upsert by workspace and endpoint
    /**
     * Saves a subscription for one person in one workspace, or refreshes the existing row for the
     * same browser there (its keys, label and session, and a fresh failure count). A row held by a
     * different person is taken over only when the keys match; otherwise nothing changes.
     *
     * @return the row as stored, or empty when the endpoint belongs to someone else here
     */
    public Optional<PushSubscription> upsert(
            UUID orgId, UUID userId, UUID sessionId, String endpoint, String p256dh, String auth, String label) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UuidV7.generate())
                .addValue("orgId", orgId)
                .addValue("userId", userId)
                .addValue("sessionId", sessionId)
                .addValue("endpoint", endpoint)
                .addValue("p256dh", p256dh)
                .addValue("auth", auth)
                .addValue("label", label == null ? "" : label);
        return named.query(UPSERT, params, ROW).stream().findFirst();
    }

    // @find: list my push devices
    /** One person's subscriptions in one workspace, newest first. */
    public List<PushSubscription> listFor(UUID orgId, UUID userId) {
        return jdbc.query(
                "select " + COLUMNS + " from push_subscriptions where org_id = ? and user_id = ? order by created_at desc",
                ROW,
                orgId,
                userId);
    }

    /** How many subscriptions one person has in one workspace. */
    public int countFor(UUID orgId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from push_subscriptions where org_id = ? and user_id = ?", Integer.class, orgId, userId);
        return count == null ? 0 : count;
    }

    /**
     * Removes one person's oldest subscriptions beyond {@code keep}, so a person who reinstalls
     * browsers for years does not collect devices without end.
     *
     * @return how many were removed
     */
    public int trimOldest(UUID orgId, UUID userId, int keep) {
        return jdbc.update(
                """
                delete from push_subscriptions
                 where org_id = ? and user_id = ?
                   and id not in (select id from push_subscriptions
                                   where org_id = ? and user_id = ?
                                   order by created_at desc, id desc
                                   limit ?)
                """,
                orgId,
                userId,
                orgId,
                userId,
                Math.max(0, keep));
    }

    // @find: delete push subscription by endpoint
    /**
     * Removes the subscription with this endpoint, only when it belongs to this person in this
     * workspace.
     *
     * @return the removed row's id, or empty when there was none of theirs
     */
    public Optional<UUID> deleteOwned(UUID orgId, UUID userId, String endpoint) {
        return jdbc.queryForList(
                        "delete from push_subscriptions where org_id = ? and user_id = ? and endpoint = ? returning id",
                        UUID.class,
                        orgId,
                        userId,
                        endpoint)
                .stream()
                .findFirst();
    }

    // @find: delete push subscription by id, remove one of my devices
    /**
     * Removes one subscription by its id, only when it belongs to this person in this workspace:
     * how a person turns off a phone they no longer hold.
     *
     * @return whether a row of theirs was removed
     */
    public boolean deleteOwnedById(UUID orgId, UUID userId, UUID id) {
        return jdbc.update("delete from push_subscriptions where org_id = ? and user_id = ? and id = ?", orgId, userId, id) > 0;
    }

    /** The subscription for this browser in this workspace, whoever owns it; for checks before a change. */
    public Optional<PushSubscription> findByOrgAndEndpoint(UUID orgId, String endpoint) {
        return jdbc.query(
                        "select " + COLUMNS + " from push_subscriptions where org_id = ? and endpoint = ?", ROW, orgId, endpoint)
                .stream()
                .findFirst();
    }

    // @find: who has push turned on in a workspace, page through subscribers
    /**
     * The people in a workspace with at least one subscription, in id order, a page at a time:
     * those after {@code after} (null for the first page), at most {@code limit} of them. Paging
     * rather than a cap, so every subscribed person is looked at however large the workspace.
     */
    public List<UUID> subscribedUsersAfter(UUID orgId, UUID after, int limit) {
        if (after == null) {
            return jdbc.queryForList(
                    "select distinct user_id from push_subscriptions where org_id = ? order by user_id limit ?",
                    UUID.class,
                    orgId,
                    Math.max(1, limit));
        }
        return jdbc.queryForList(
                "select distinct user_id from push_subscriptions where org_id = ? and user_id > ? order by user_id limit ?",
                UUID.class,
                orgId,
                after,
                Math.max(1, limit));
    }

    /** Every subscription of the given people in a workspace. Empty for no people. */
    public List<PushSubscription> listForUsers(UUID orgId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("userIds", userIds);
        return named.query(
                "select " + COLUMNS + " from push_subscriptions where org_id = :orgId and user_id in (:userIds)",
                params,
                ROW);
    }

    /** A delivery arrived: the time is noted and the failure count starts again. */
    public void recordSuccess(UUID id, Instant at) {
        jdbc.update(
                "update push_subscriptions set last_success_at = ?, failure_count = 0 where id = ?",
                Timestamp.from(at),
                id);
    }

    /** A delivery failed: the count goes up by one. @return the new count, or 0 when the row is gone */
    public int recordFailure(UUID id) {
        List<Integer> counts = jdbc.queryForList(
                "update push_subscriptions set failure_count = failure_count + 1 where id = ? returning failure_count",
                Integer.class,
                id);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    /** Removes a subscription the push service says is gone, or one that kept failing. */
    public void delete(UUID id) {
        jdbc.update("delete from push_subscriptions where id = ?", id);
    }

    /** Removes several subscriptions at once: those whose sign-in has ended. Nothing for none. */
    public int deleteAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return named.update(
                "delete from push_subscriptions where id in (:ids)", new MapSqlParameterSource().addValue("ids", ids));
    }

    // One row as a record.
    private static PushSubscription map(ResultSet row, int n) throws SQLException {
        Timestamp lastSuccess = row.getTimestamp("last_success_at");
        return new PushSubscription(
                row.getObject("id", UUID.class),
                row.getObject("org_id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getString("endpoint"),
                row.getString("p256dh"),
                row.getString("auth"),
                row.getString("user_agent_label"),
                row.getObject("session_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                lastSuccess == null ? null : lastSuccess.toInstant(),
                row.getInt("failure_count"));
    }
}
