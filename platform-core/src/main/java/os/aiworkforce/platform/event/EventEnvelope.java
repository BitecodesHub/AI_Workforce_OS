package os.aiworkforce.platform.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import os.aiworkforce.platform.context.RequestContext;

/**
 * The wrapper every event travels in.
 *
 * <p>A bare payload on a topic loses the three things that make an event usable a week later:
 * who caused it, which request it belongs to, and whether this copy has been seen before. All
 * three live here rather than being repeated in every payload type.
 *
 * <p>{@code eventId} is the idempotency key. Kafka guarantees at-least-once delivery, so a
 * consumer <em>will</em> see duplicates - on a rebalance, on a retry, on a redeployment mid-poll.
 * A consumer that is not idempotent will double-charge, double-send or double-approve, and the
 * only reliable defence is a key it can record as processed.
 *
 * @param eventId unique per emission, and the key a consumer deduplicates on
 * @param type dotted event name, for example {@code task.completed}
 * @param version payload schema version, so a consumer can refuse what it cannot read
 * @param occurredAt when the fact happened, which is not when it was delivered
 * @param orgId the workspace the fact belongs to
 * @param subjectId the primary entity the event concerns
 * @param correlationId ties every event produced while serving one request
 * @param causationId the event that caused this one, so a chain can be walked backwards
 * @param actor the snapshot of who was acting, rebuilt by the consumer
 * @param payload the event body
 * @param attempt delivery attempt, incremented by the retry topic
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope<T>(
        String eventId,
        String type,
        int version,
        Instant occurredAt,
        String orgId,
        String subjectId,
        String correlationId,
        String causationId,
        RequestContext.Snapshot actor,
        T payload,
        int attempt,
        Map<String, String> headers) {

    public static <T> EventEnvelope<T> of(String type, int version, String orgId, String subjectId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                version,
                Instant.now(),
                orgId,
                subjectId,
                RequestContext.requestId(),
                null,
                RequestContext.snapshot(),
                payload,
                1,
                Map.of());
    }

    /** Derives a new event caused by this one, carrying the correlation forward. */
    public <R> EventEnvelope<R> causing(String type, int version, R payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                version,
                Instant.now(),
                orgId,
                subjectId,
                correlationId,
                eventId,
                actor,
                payload,
                1,
                Map.of());
    }

    /** The same event, marked as one delivery attempt later. */
    public EventEnvelope<T> nextAttempt() {
        return new EventEnvelope<>(
                eventId,
                type,
                version,
                occurredAt,
                orgId,
                subjectId,
                correlationId,
                causationId,
                actor,
                payload,
                attempt + 1,
                headers);
    }

    /**
     * The partition key.
     *
     * <p>Keying on the subject is what preserves ordering where ordering matters: every event
     * about one run lands on one partition, so {@code run.started} cannot overtake
     * {@code run.completed}. Keying on the workspace instead would serialise unrelated work.
     */
    public String partitionKey() {
        return subjectId != null ? subjectId : orgId != null ? orgId : eventId;
    }
}
