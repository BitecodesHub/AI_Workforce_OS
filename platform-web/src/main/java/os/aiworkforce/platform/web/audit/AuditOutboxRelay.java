package os.aiworkforce.platform.web.audit;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.platform.context.RequestContext;

/**
 * Sends queued audit events to analytics-service, and keeps trying until it has them.
 *
 * <p>Runs on a timer ({@code aiwos.audit.relay-interval}, ten seconds) and once for each batch of
 * events a commit leaves behind, so an event normally leaves within moments of the change it
 * describes and an outage of analytics-service costs a delay rather than a gap. A failed delivery is
 * retried after a growing wait; once a row has used up its attempts it is reported at WARN and then
 * tried again at a slow, steady pace for as long as it exists. It is never dropped, because the
 * only thing worse than a late audit entry is a missing one.
 *
 * <p>Every instance of the service runs a relay over the same table. A row is claimed before it is
 * sent, so two instances do not send the same row at once, and delivery is idempotent at the far end
 * for the case where one does.
 */
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);

    private final AuditOutbox outbox;
    private final AuditSender sender;
    private final AuditProperties properties;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final ExecutorService nudges = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audit-outbox-relay");
        thread.setDaemon(true);
        return thread;
    });

    public AuditOutboxRelay(AuditOutbox outbox, AuditSender sender, AuditProperties properties) {
        this.outbox = outbox;
        this.sender = sender;
        this.properties = properties;
    }

    /** Asks for a pass soon, without waiting for it. Many calls in a burst collapse into one pass. */
    public void wake() {
        if (!wakePending.compareAndSet(false, true)) {
            return;
        }
        try {
            nudges.execute(() -> {
                wakePending.set(false);
                deliverDue();
            });
        } catch (RuntimeException shuttingDown) {
            wakePending.set(false);
        }
    }

    public void shutdown() {
        nudges.shutdown();
    }

    @Scheduled(fixedDelayString = "${aiwos.audit.relay-interval:PT10S}", initialDelayString = "PT5S")
    public void tick() {
        deliverDue();
    }

    /**
     * One pass: claims what is due and sends it in order. Passes do not overlap within an instance.
     *
     * @return how many events were delivered
     */
    public int deliverDue() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        // A nudge runs on a thread that may have copied a request's identity when it was created; the
        // relay acts for the platform, never for whichever person happened to trigger it.
        RequestContext.clear();
        int delivered = 0;
        try {
            List<AuditOutbox.Event> due = outbox.claimDue(properties.batchSize(), properties.lease());
            for (int i = 0; i < due.size(); i++) {
                Outcome outcome = deliver(due.get(i));
                if (outcome == Outcome.DELIVERED) {
                    delivered++;
                } else if (outcome == Outcome.DESTINATION_FAILING) {
                    // Analytics-service itself is unreachable or failing: the rest would fail the same
                    // way, so hand them back and try again on the next pass instead of burning one
                    // attempt on every queued row.
                    outbox.release(due.subList(i + 1, due.size()).stream()
                            .map(AuditOutbox.Event::id)
                            .toList());
                    break;
                }
            }
        } catch (RuntimeException e) {
            // The queue itself could not be read (the service's own database is unavailable). There
            // is nothing to retry from here: the next pass looks again.
            log.warn("The audit outbox could not be read: {}", e.toString());
        } finally {
            running.set(false);
        }
        return delivered;
    }

    private enum Outcome {
        DELIVERED,
        /** This event was refused or could not be sent; the ones behind it may still go. */
        EVENT_FAILED,
        /** The destination is down or refusing everything. */
        DESTINATION_FAILING
    }

    private Outcome deliver(AuditOutbox.Event event) {
        try {
            sender.send(event);
            outbox.delivered(event.id());
            return Outcome.DELIVERED;
        } catch (RuntimeException e) {
            recordFailure(event, e);
            return systemic(e) ? Outcome.DESTINATION_FAILING : Outcome.EVENT_FAILED;
        }
    }

    private void recordFailure(AuditOutbox.Event event, RuntimeException failure) {
        int failedAttempts = event.attempts() + 1;
        var retryAfter = properties.backoffAfter(failedAttempts);
        String reason = describe(failure);
        try {
            outbox.failed(event.id(), retryAfter, reason);
        } catch (RuntimeException e) {
            log.warn("Could not record the failed delivery of audit event {}: {}", event.id(), e.toString());
        }
        if (failedAttempts >= properties.maxAttempts()) {
            log.warn(
                    "Audit event {} ({} {}) has failed {} deliveries and is still waiting; last error: {}."
                            + " It is kept and will be tried again in {}.",
                    event.id(),
                    event.action(),
                    event.resourceType(),
                    failedAttempts,
                    reason,
                    retryAfter);
        } else if (failedAttempts == 1) {
            log.info(
                    "Audit event {} ({}) could not be delivered and is kept for retry: {}",
                    event.id(),
                    event.action(),
                    reason);
        } else {
            log.debug("Audit event {} delivery attempt {} failed: {}", event.id(), failedAttempts, reason);
        }
    }

    /** A failure of the destination rather than of this one event: everything behind it would fail too. */
    private static boolean systemic(Throwable failure) {
        if (failure instanceof WebClientResponseException response) {
            return response.getStatusCode().is5xxServerError()
                    || response.getStatusCode().value() == 401
                    || response.getStatusCode().value() == 403
                    || response.getStatusCode().value() == 429;
        }
        return true;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
