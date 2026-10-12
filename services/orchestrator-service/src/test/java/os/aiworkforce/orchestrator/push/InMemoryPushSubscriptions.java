// @find: test helper in-memory push subscriptions, fake push subscription store, InMemoryPushSubscriptions
// @what: Test stand-in for PushSubscriptionStore that keeps rows in memory with the same rules as its SQL (unique per workspace and endpoint, takeover only with the same keys, owner-scoped delete).
package os.aiworkforce.orchestrator.push;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import os.aiworkforce.platform.web.persistence.UuidV7;

/** The subscription table in memory, for service and controller tests that run without a database. */
class InMemoryPushSubscriptions extends PushSubscriptionStore {

    /** The session every row added with {@link #add} belongs to, unless a test names another. */
    static final UUID SESSION = UUID.fromString("00000000-0000-7000-8000-00000000c0de");

    private record Key(UUID orgId, String endpoint) {}

    private final Map<Key, PushSubscription> rows = new LinkedHashMap<>();
    private Instant now = Instant.parse("2026-10-12T09:00:00Z");

    InMemoryPushSubscriptions() {
        super(new JdbcTemplate());
    }

    /** Adds a row directly, as an earlier subscription would have left it, in the shared live session. */
    PushSubscription add(UUID orgId, UUID userId, String endpoint, String p256dh, String auth) {
        return add(orgId, userId, SESSION, endpoint, p256dh, auth);
    }

    /** Adds a row directly, in the given session. */
    PushSubscription add(UUID orgId, UUID userId, UUID sessionId, String endpoint, String p256dh, String auth) {
        return upsert(orgId, userId, sessionId, endpoint, p256dh, auth, "Chrome on Android").orElseThrow();
    }

    /** The row for this browser in this workspace, if any. */
    synchronized PushSubscription at(UUID orgId, String endpoint) {
        return rows.get(new Key(orgId, endpoint));
    }

    @Override
    public synchronized Optional<PushSubscription> upsert(
            UUID orgId, UUID userId, UUID sessionId, String endpoint, String p256dh, String auth, String label) {
        Key key = new Key(orgId, endpoint);
        PushSubscription existing = rows.get(key);
        if (existing != null
                && !existing.userId().equals(userId)
                && !(existing.p256dh().equals(p256dh) && existing.auth().equals(auth))) {
            return Optional.empty();
        }
        now = now.plusSeconds(1);
        PushSubscription row = new PushSubscription(
                existing == null ? UuidV7.generate() : existing.id(),
                orgId,
                userId,
                endpoint,
                p256dh,
                auth,
                label == null ? "" : label,
                sessionId,
                existing == null ? now : existing.createdAt(),
                existing == null ? null : existing.lastSuccessAt(),
                0);
        rows.put(key, row);
        return Optional.of(row);
    }

    @Override
    public synchronized List<PushSubscription> listFor(UUID orgId, UUID userId) {
        return rows.values().stream()
                .filter(row -> row.orgId().equals(orgId) && row.userId().equals(userId))
                .sorted(Comparator.comparing(PushSubscription::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized int countFor(UUID orgId, UUID userId) {
        return listFor(orgId, userId).size();
    }

    @Override
    public synchronized int trimOldest(UUID orgId, UUID userId, int keep) {
        List<PushSubscription> mine = listFor(orgId, userId);
        int removed = 0;
        for (int i = keep; i < mine.size(); i++) {
            rows.remove(new Key(orgId, mine.get(i).endpoint()));
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized Optional<UUID> deleteOwned(UUID orgId, UUID userId, String endpoint) {
        PushSubscription row = rows.get(new Key(orgId, endpoint));
        if (row == null || !row.userId().equals(userId)) {
            return Optional.empty();
        }
        rows.remove(new Key(orgId, endpoint));
        return Optional.of(row.id());
    }

    @Override
    public synchronized boolean deleteOwnedById(UUID orgId, UUID userId, UUID id) {
        Optional<PushSubscription> row = byId(id).filter(found -> found.orgId().equals(orgId) && found.userId().equals(userId));
        row.ifPresent(found -> rows.remove(new Key(found.orgId(), found.endpoint())));
        return row.isPresent();
    }

    @Override
    public synchronized Optional<PushSubscription> findByOrgAndEndpoint(UUID orgId, String endpoint) {
        return Optional.ofNullable(rows.get(new Key(orgId, endpoint)));
    }

    @Override
    public synchronized List<UUID> subscribedUsersAfter(UUID orgId, UUID after, int limit) {
        return rows.values().stream()
                .filter(row -> row.orgId().equals(orgId))
                .map(PushSubscription::userId)
                .distinct()
                .sorted()
                .filter(userId -> after == null || userId.compareTo(after) > 0)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<PushSubscription> listForUsers(UUID orgId, Collection<UUID> userIds) {
        return rows.values().stream()
                .filter(row -> row.orgId().equals(orgId) && userIds.contains(row.userId()))
                .toList();
    }

    @Override
    public synchronized void recordSuccess(UUID id, Instant at) {
        replace(id, row -> new PushSubscription(row.id(), row.orgId(), row.userId(), row.endpoint(), row.p256dh(),
                row.auth(), row.label(), row.sessionId(), row.createdAt(), at, 0));
    }

    @Override
    public synchronized int recordFailure(UUID id) {
        replace(id, row -> new PushSubscription(row.id(), row.orgId(), row.userId(), row.endpoint(), row.p256dh(),
                row.auth(), row.label(), row.sessionId(), row.createdAt(), row.lastSuccessAt(), row.failureCount() + 1));
        return byId(id).map(PushSubscription::failureCount).orElse(0);
    }

    @Override
    public synchronized void delete(UUID id) {
        byId(id).ifPresent(row -> rows.remove(new Key(row.orgId(), row.endpoint())));
    }

    @Override
    public synchronized int deleteAll(Collection<UUID> ids) {
        int removed = 0;
        for (UUID id : ids) {
            Optional<PushSubscription> row = byId(id);
            if (row.isPresent()) {
                rows.remove(new Key(row.get().orgId(), row.get().endpoint()));
                removed++;
            }
        }
        return removed;
    }

    synchronized Optional<PushSubscription> byId(UUID id) {
        return rows.values().stream().filter(row -> row.id().equals(id)).findFirst();
    }

    synchronized List<PushSubscription> all() {
        return new ArrayList<>(rows.values());
    }

    private void replace(UUID id, java.util.function.UnaryOperator<PushSubscription> change) {
        byId(id).ifPresent(row -> rows.put(new Key(row.orgId(), row.endpoint()), change.apply(row)));
    }
}
