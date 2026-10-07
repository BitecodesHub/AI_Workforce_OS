package os.aiworkforce.platform.web.audit;

/** Delivers one outbox event to analytics-service, or throws. */
@FunctionalInterface
public interface AuditSender {

    /**
     * Sends the event. Returns normally only when analytics-service has accepted it; sending the same
     * event again is safe, because the event's id makes the second delivery a no-op there.
     */
    void send(AuditOutbox.Event event);
}
