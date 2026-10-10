// @find: audit properties, aiwos.audit settings, relay interval, retry settings, enable audit
// @what: Settings for how a service delivers its audit events.
// @flow: Read by AuditOutboxRelay and AuditClient
package os.aiworkforce.platform.web.audit;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How a service delivers its audit events: whether it does, how often the relay runs, and how
 * patiently it retries.
 *
 * <p>The defaults suit a development stack and a small deployment. They are bound under
 * {@code aiwos.audit}, beside the platform's own tree, so a service turns the client off with
 * {@code aiwos.audit.enabled=false} without anything else knowing the client exists.
 *
 * <p>How often the relay looks for rows is {@code aiwos.audit.relay-interval}, ten seconds by
 * default; it is read where the relay is scheduled, because a schedule's period is not something a
 * running relay can change. Each committed event also nudges the relay at once, so the interval is
 * the worst case for a row that missed its nudge, not the usual delay.
 *
 * @param enabled whether events are recorded and delivered; off, {@code record} does nothing
 * @param batchSize rows claimed per pass
 * @param maxAttempts attempts before the row is reported at WARN and parked
 * @param initialBackoff the wait after the first failure; doubled after each further one
 * @param maxBackoff the longest wait between attempts before the row is parked
 * @param parkedRetry how often a row that used up its attempts is tried again. It is never
 *     dropped: an audit event that cannot be delivered is kept until somebody can read it
 * @param lease how long a claimed row is hidden from other instances while it is being sent
 * @param sendTimeout how long one delivery may take
 */
@Validated
@ConfigurationProperties(prefix = "aiwos.audit")
public record AuditProperties(
        @DefaultValue("true") boolean enabled,
        @Positive @DefaultValue("50") int batchSize,
        @Min(1) @DefaultValue("20") int maxAttempts,
        @NotNull @DefaultValue("PT10S") Duration initialBackoff,
        @NotNull @DefaultValue("PT30S") Duration maxBackoff,
        @NotNull @DefaultValue("PT1H") Duration parkedRetry,
        @NotNull @DefaultValue("PT60S") Duration lease,
        @NotNull @DefaultValue("PT5S") Duration sendTimeout) {

    /** How long to wait after a row's {@code failedAttempts}-th failure. */
    public Duration backoffAfter(int failedAttempts) {
        if (failedAttempts >= maxAttempts) {
            return parkedRetry;
        }
        // Doubling from the first wait, capped; the shift is bounded so a large count cannot overflow.
        long factor = 1L << Math.min(Math.max(failedAttempts - 1, 0), 20);
        Duration wait = initialBackoff.multipliedBy(factor);
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}
