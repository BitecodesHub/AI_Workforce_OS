// @find: schedule sweep, scheduler job, due schedules, every 30 seconds, start scheduled work, ScheduleSweep, background job, aiwos.scheduling.schedule-interval
// @what: Background job that regularly starts schedules whose time has come.
// @flow: Calls ScheduleService.sweepDue
package os.aiworkforce.orchestrator.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices a schedule is due and fires it.
 *
 * <p>A component of its own rather than another sweep added to {@link
 * os.aiworkforce.orchestrator.service.MaintenanceScheduler}: that class belongs to the engine
 * package, and a bean here needs nothing from it beyond {@code @EnableScheduling}, which it
 * already turns on for the whole application context.
 */
@Component
@ConditionalOnProperty(name = "aiwos.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleSweep {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSweep.class);
    /** Most due schedules handled in one tick. */
    private static final int BATCH = 50;

    private final ScheduleService service;

    /** Builds the sweep; used by Spring. */
    public ScheduleSweep(ScheduleService service) {
        this.service = service;
    }

    // @find: scheduled job, sweep due schedules every 30 seconds
    @Scheduled(fixedDelayString = "${aiwos.scheduling.schedule-interval:PT30S}")
    /**
     * Runs on a fixed delay (default 30 seconds, {@code aiwos.scheduling.schedule-interval}) and asks
     * {@link ScheduleService#sweepDue(int)} to start due schedules. Side effects are those of the service
     * (goals started, schedule rows advanced). An unexpected failure is logged and swallowed so the next tick still runs.
     */
    public void sweep() {
        try {
            int processed = service.sweepDue(BATCH);
            if (processed > 0) {
                log.info("Processed {} due schedule(s)", processed);
            }
        } catch (RuntimeException e) {
            // A failing sweep must not stop the scheduler from running the next one.
            log.error("The schedule sweep failed", e);
        }
    }
}
