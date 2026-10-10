// @find: metrics, operations metrics, pending approvals gauge, sweep duration, micrometer, dashboard figures, monitoring
// @what: Publishes service-wide metrics: approvals waiting and how long each background sweep takes.
// @flow: Used by MaintenanceScheduler
package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The figures an operator reads off a dashboard that no single request produces: how many
 * approvals are waiting for a person, and how long each background sweep takes.
 *
 * <p>Only low-cardinality tags are used. The pending count is one number for the whole service,
 * not one per workspace or approver, because a tag per workspace would add a series for every
 * customer. The sweep timer is tagged with the sweep's own name, of which there are a handful.
 *
 * <p>The count is read by {@link MaintenanceScheduler} every minute and held in between, so a
 * scrape never touches the database. Every instance reports the same figure, since the count is
 * of the shared table; read it with {@code max} across instances, not {@code sum}.
 */
@Component
public class OperationsMetrics {

    public static final String APPROVALS_PENDING = "aiwos.approvals.pending";
    public static final String SWEEP_DURATION = "aiwos.sweep.duration";

    /** The sweeps that are timed, registered at start so each has a series before its first run. */
    static final List<String> SWEEPS = List.of(
            "reap-abandoned",
            "expire-approvals",
            "expire-questions",
            "resume-decided",
            "advance-goals",
            "purge-events",
            "retention",
            "gauges");

    private static final Logger log = LoggerFactory.getLogger(OperationsMetrics.class);

    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;
    private final AtomicLong pendingApprovals = new AtomicLong();

    public OperationsMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
        this.meters = meters;
        this.jdbc = jdbc;
        Gauge.builder(APPROVALS_PENDING, pendingApprovals, AtomicLong::get)
                .description("Approvals waiting for a person to decide, across every workspace")
                .register(meters);
        SWEEPS.forEach(this::timer);
    }

    // @find: refresh pending approvals gauge
    /**
     * Counts the approvals still pending and holds the figure for the next scrape. A count that
     * cannot be made leaves the last figure standing: a gauge that dropped to zero during a
     * database blip would read as an empty queue exactly when nobody could look.
     */
    public void refreshPendingApprovals() {
        try {
            Long count = jdbc.queryForObject("select count(*) from approvals where status = 'pending'", Long.class);
            pendingApprovals.set(count == null ? 0 : count);
        } catch (RuntimeException e) {
            log.warn("Could not count pending approvals for the gauge: {}", e.getMessage());
        }
    }

    // @find: time a sweep
    /** Runs a sweep and records how long it took, whether it returned or threw. */
    public void timed(String sweep, Runnable body) {
        Timer.Sample sample = Timer.start(meters);
        try {
            body.run();
        } finally {
            sample.stop(timer(sweep));
        }
    }

    private Timer timer(String sweep) {
        return Timer.builder(SWEEP_DURATION)
                .description("How long one pass of a background sweep takes")
                .tag("sweep", sweep)
                .register(meters);
    }
}
