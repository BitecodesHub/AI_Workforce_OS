// @find: test helper in-memory push subscriptions, fake push subscription store, InMemoryPushSubscriptions
// @what: Test stand-in for PushSubscriptionStore that keeps rows in memory with the same rules as its SQL (unique endpoint, owner-scoped delete).
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

    final Map<String, PushSubscription> byEndpoint = new LinkedHashMap<>();
    private Instant now = Instant.parse("2026-10-12T09:00:00Z");

    InMemoryPushSubscriptions() {
        super(new JdbcTemplate());
    }

    /** Adds a row directly, as an earlier subscription would have left it. */
    PushSubscription add(UUID orgId, UUID userId, String endpoint, String p256dh, String auth) {
        return upsert(orgId, userId, endpoint, p256dh, auth, "Chrome on Android");
    }

    @Override
    public synchronized PushSubscription upsert(
            UUID orgId, UUID userId, String endpoint, String p256dh, String auth, String label) {
        PushSubscription existing = byEndpoint.get(endpoint);
        now = now.plusSeconds(1);
        PushSubscription row = new PushSubscription(
                existing == null ? UuidV7.generate() : existing.id(),
                orgId,
                userId,
                endpoint,
                p256dh,
                auth,
                label == null ? "" : label,
                existing == null ? now : existing.createdAt(),
                existing == null ? null : existing.lastSuccessAt(),
                0);
        byEndpoint.put(endpoint, row);
        return row;
    }

    @Override
    public synchronized List<PushSubscription> listFor(UUID orgId, UUID userId) {
        return byEndpoint.values().stream()
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
            byEndpoint.remove(mine.get(i).endpoint());
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized Optional<UUID> deleteOwned(UUID orgId, UUID userId, String endpoint) {
        PushSubscription row = byEndpoint.get(endpoint);
        if (row == null || !row.orgId().equals(orgId) || !row.userId().equals(userId)) {
            return Optional.empty();
        }
        byEndpoint.remove(endpoint);
        return Optional.of(row.id());
    }

    @Override
    public synchronized Optional<PushSubscription> findByEndpoint(String endpoint) {
        return Optional.ofNullable(byEndpoint.get(endpoint));
    }

    @Override
    public synchronized List<UUID> subscribedUsers(UUID orgId, int limit) {
        return byEndpoint.values().stream()
                .filter(row -> row.orgId().equals(orgId))
                .map(PushSubscription::userId)
                .distinct()
                .sorted()
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<PushSubscription> listForUsers(UUID orgId, Collection<UUID> userIds) {
        return byEndpoint.values().stream()
                .filter(row -> row.orgId().equals(orgId) && userIds.contains(row.userId()))
                .toList();
    }

    @Override
    public synchronized void recordSuccess(UUID id, Instant at) {
        replace(id, row -> new PushSubscription(row.id(), row.orgId(), row.userId(), row.endpoint(), row.p256dh(),
                row.auth(), row.label(), row.createdAt(), at, 0));
    }

    @Override
    public synchronized int recordFailure(UUID id) {
        replace(id, row -> new PushSubscription(row.id(), row.orgId(), row.userId(), row.endpoint(), row.p256dh(),
                row.auth(), row.label(), row.createdAt(), row.lastSuccessAt(), row.failureCount() + 1));
        return byId(id).map(PushSubscription::failureCount).orElse(0);
    }

    @Override
    public synchronized void delete(UUID id) {
        byId(id).ifPresent(row -> byEndpoint.remove(row.endpoint()));
    }

    synchronized Optional<PushSubscription> byId(UUID id) {
        return byEndpoint.values().stream().filter(row -> row.id().equals(id)).findFirst();
    }

    synchronized List<PushSubscription> all() {
        return new ArrayList<>(byEndpoint.values());
    }

    private void replace(UUID id, java.util.function.UnaryOperator<PushSubscription> change) {
        byId(id).ifPresent(row -> byEndpoint.put(row.endpoint(), change.apply(row)));
    }
}
