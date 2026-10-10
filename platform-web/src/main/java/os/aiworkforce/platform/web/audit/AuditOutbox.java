// @find: audit outbox, pending audit events, outbox interface, queue of audit events, deliver later
// @what: Interface for the local queue of audit events waiting to be delivered.
// @flow: Implemented by JdbcAuditOutbox
package os.aiworkforce.platform.web.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * The service's own queue of audit events waiting to be delivered.
 *
 * <p>A row is written in the same transaction as the change it describes, so the two commit or
 * roll back together: an action that was undone leaves no entry, and an action that happened cannot
 * lose its entry to an outage somewhere else. Rows are removed once analytics-service has accepted
 * them and not before.
 */
public interface AuditOutbox {

    /**
     * One event waiting to be sent.
     *
     * @param id the event's own id, fixed when it was recorded and sent unchanged on every attempt,
     *     which is how analytics-service recognises a repeat
     * @param detailJson the detail as JSON text
     * @param occurredAt when the action happened, which a delayed delivery must not replace
     * @param attempts how many deliveries have already failed
     */
    record Event(
            UUID id,
            UUID orgId,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String detailJson,
            String requestId,
            Instant occurredAt,
            int attempts) {}

    /** Writes the event, joining the caller's transaction when there is one. */
    void add(Event event);

    /**
     * Takes up to {@code limit} events that are due, hiding them from other instances for
     * {@code lease} so two replicas do not send the same row. Oldest first.
     */
    java.util.List<Event> claimDue(int limit, Duration lease);

    /** Removes an event analytics-service accepted. */
    void delivered(UUID id);

    /** Records a failed attempt and says when to try again. */
    void failed(UUID id, Duration retryAfter, String error);

    /** Hands claimed events back at once, untried, when the pass stops early. */
    void release(Collection<UUID> ids);

    /** How many events are waiting, due or not. */
    long pending();
}
