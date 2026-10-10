// @find: processed event, idempotent event handling, dedupe event, event id, topic, processed_events, ProcessedEvent entity
// @what: Entity recording an event id already handled, so redelivered events are ignored.
// @flow: Stored by ProcessedEvents; checked by event listeners.
// @find: processed event, idempotent event handling, dedupe event, event id, topic, processed_events, ProcessedEvent entity
// @what: Entity recording an event id already handled, so redelivered events are ignored.
// @flow: Stored by ProcessedEvents; checked by event listeners.
package os.aiworkforce.orchestrator.repository;

import java.time.Instant;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/** Marker for an event this service has already handled, so a redelivery is a no-op. */
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "processed_events")
public class ProcessedEvent {

    @jakarta.persistence.Id
    @jakarta.persistence.Column(name = "event_id", nullable = false)
    private String eventId;

    @jakarta.persistence.Column(nullable = false)
    private String topic;

    @jakarta.persistence.Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId, String topic) {
        this.eventId = eventId;
        this.topic = topic;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
