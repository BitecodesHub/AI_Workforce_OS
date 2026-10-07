package os.aiworkforce.platform.web.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A queue held in memory, with a clock the test moves, standing in for the table. */
class InMemoryAuditOutbox implements AuditOutbox {

    private static final class Row {
        Event event;
        Instant nextAttemptAt;
        String lastError;

        Row(Event event, Instant nextAttemptAt) {
            this.event = event;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    Instant now = Instant.parse("2026-10-06T00:00:00Z");

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public synchronized void add(Event event) {
        rows.put(event.id(), new Row(event, now));
    }

    @Override
    public synchronized List<Event> claimDue(int limit, Duration lease) {
        List<Event> due = new ArrayList<>();
        rows.values().stream()
                .filter(row -> !row.nextAttemptAt.isAfter(now))
                .sorted(Comparator.comparing((Row row) -> row.event.occurredAt()))
                .limit(limit)
                .forEach(row -> {
                    row.nextAttemptAt = now.plus(lease);
                    due.add(row.event);
                });
        return due;
    }

    @Override
    public synchronized void delivered(UUID id) {
        rows.remove(id);
    }

    @Override
    public synchronized void failed(UUID id, Duration retryAfter, String error) {
        Row row = rows.get(id);
        Event e = row.event;
        row.event = new Event(
                e.id(),
                e.orgId(),
                e.actorId(),
                e.actorKind(),
                e.onBehalfOf(),
                e.action(),
                e.resourceType(),
                e.resourceId(),
                e.outcome(),
                e.detailJson(),
                e.requestId(),
                e.occurredAt(),
                e.attempts() + 1);
        row.nextAttemptAt = now.plus(retryAfter);
        row.lastError = error;
    }

    @Override
    public synchronized void release(Collection<UUID> ids) {
        ids.forEach(id -> rows.get(id).nextAttemptAt = now);
    }

    @Override
    public synchronized long pending() {
        return rows.size();
    }

    synchronized List<Event> all() {
        return rows.values().stream().map(row -> row.event).toList();
    }

    synchronized Instant nextAttemptAt(UUID id) {
        return rows.get(id).nextAttemptAt;
    }

    synchronized String lastError(UUID id) {
        return rows.get(id).lastError;
    }
}
