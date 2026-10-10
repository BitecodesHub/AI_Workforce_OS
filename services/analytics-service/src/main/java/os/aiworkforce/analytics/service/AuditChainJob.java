// @find: audit chain job, nightly verification, scheduled audit check, broken chain alert, aiwos.audit.chain.broken gauge, verify cron
// @what: Scheduled job that re-checks every audit chain each night and publishes the result as metrics.
// @flow: Calls AuditVerification.verifyEverything; alert in infra/prometheus/rules
package os.aiworkforce.analytics.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-walks every audit chain each night, logs the result, and publishes it as gauges.
 *
 * <p>A chain nobody re-checks is only a claim. A break is logged at ERROR, which is what an alert
 * is set on, and {@code aiwos.audit.chain.broken} stays above zero until the next clean run, so a
 * dashboard shows it without anyone reading logs. The workspace-facing check is the same walk,
 * offered as {@code GET /api/audit/verify}.
 *
 * <p>Every instance of the service runs it. That is wasted work, not a hazard: it only reads.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "aiwos.audit.verify-enabled", havingValue = "true", matchIfMissing = true)
public class AuditChainJob {

    private static final Logger log = LoggerFactory.getLogger(AuditChainJob.class);

    private final AuditVerification verification;
    private final AtomicLong broken = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();
    private final AtomicLong chains = new AtomicLong();
    private final AtomicReference<Summary> last = new AtomicReference<>();

    /**
     * @param chains how many chains were walked
     * @param entries how many entries were examined in all
     * @param brokenChains the keys of the chains with a break, with the first broken sequence of each
     */
    public record Summary(Instant finishedAt, Duration took, long chains, long entries, List<String> brokenChains) {}

    public AuditChainJob(AuditVerification verification, ObjectProvider<MeterRegistry> registry) {
        this.verification = verification;
        registry.ifAvailable(meters -> {
            Gauge.builder("aiwos.audit.chain.broken", broken, AtomicLong::get)
                    .description("Audit chains with a break found by the last nightly verification")
                    .register(meters);
            Gauge.builder("aiwos.audit.chain.checked", checked, AtomicLong::get)
                    .description("Audit entries examined by the last nightly verification")
                    .register(meters);
            Gauge.builder("aiwos.audit.chain.chains", chains, AtomicLong::get)
                    .description("Audit chains walked by the last nightly verification")
                    .register(meters);
        });
    }

    // @find: scheduled job or startup listener nightly, audit chain job
    @Scheduled(cron = "${aiwos.audit.verify-cron:0 15 2 * * *}")
    public void nightly() {
        try {
            run();
        } catch (RuntimeException e) {
            // A check that could not run says nothing about the chains, and must not be mistaken for
            // a clean result: the gauges keep their last values and the failure is loud.
            log.error("The nightly audit chain verification could not run", e);
        }
    }

    /** One full verification, also callable on demand. */
    // @find: run audit chain verification now
    public Summary run() {
        Instant started = Instant.now();
        List<AuditChain.Verification> results = verification.verifyEverything();

        long entries = results.stream().mapToLong(AuditChain.Verification::checked).sum();
        List<String> brokenChains = results.stream()
                .filter(result -> !result.verified())
                .map(result -> result.chainKey() + " at entry " + result.firstBrokenSequence())
                .toList();

        broken.set(brokenChains.size());
        checked.set(entries);
        chains.set(results.size());
        Summary summary = new Summary(Instant.now(), Duration.between(started, Instant.now()), results.size(), entries, brokenChains);
        last.set(summary);

        if (brokenChains.isEmpty()) {
            log.info(
                    "Audit verification passed: {} chain(s), {} entr{} in {} ms",
                    results.size(),
                    entries,
                    entries == 1 ? "y" : "ies",
                    summary.took().toMillis());
        } else {
            log.error(
                    "Audit verification FAILED for {} of {} chain(s): {}",
                    brokenChains.size(),
                    results.size(),
                    String.join("; ", brokenChains));
        }
        return summary;
    }

    /** The last completed run, or null before the first. */
    public Summary lastSummary() {
        return last.get();
    }
}
