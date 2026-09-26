package os.aiworkforce.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sweeps that keep the platform's state honest between requests.
 *
 * <p>Each of these exists because something otherwise stays wrong silently: a run whose worker
 * died shows as running forever, and an approval nobody answered sits pending forever, quietly
 * holding a task open. Neither produces an error anybody would see.
 *
 * <p>Batch sizes are small on purpose. A sweep that tries to fix everything at once holds a
 * transaction long enough to matter, and if it fails it fixes nothing; a small batch that runs
 * often converges just as fast and fails cheaply.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "aiwos.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);
    private static final int BATCH = 50;

    private final AgentRunner runner;
    private final ApprovalService approvals;

    public MaintenanceScheduler(AgentRunner runner, ApprovalService approvals) {
        this.runner = runner;
        this.approvals = approvals;
    }

    /** Picks up runs whose worker stopped renewing its lease. */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.reap-interval:PT60S}")
    public void reapAbandonedRuns() {
        try {
            int reaped = runner.reapAbandoned(BATCH);
            if (reaped > 0) {
                log.warn("Marked {} run(s) abandoned after their lease expired", reaped);
            }
        } catch (RuntimeException e) {
            // A failing sweep must not stop the scheduler from running the next one.
            log.error("The abandoned-run sweep failed", e);
        }
    }

    /** Closes approvals that passed their deadline, rejecting by default. */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.expiry-interval:PT120S}")
    public void expireApprovals() {
        try {
            approvals.expireOverdue(BATCH);
        } catch (RuntimeException e) {
            log.error("The approval-expiry sweep failed", e);
        }
    }
}
