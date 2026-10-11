// @find: episode purge job, delete expired episodes, retention, scheduled cleanup, nightly purge
// @what: Scheduled job that removes episodes past their expiry each night.
// @flow: Calls EpisodicMemory.purgeExpired
package os.aiworkforce.memory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes episodes past their retention date, a small batch at a time, every night. */
@Component
public class EpisodePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EpisodePurgeJob.class);
    /**
     * Number of expired episodes deleted per database round trip, which keeps each transaction short.
     */
    static final int BATCH = 500;
    /**
     * Upper bound on batches per run, so one night never deletes more than BATCH times this value and a very large backlog is finished over several nights.
     */
    static final int MAX_BATCHES = 200;

    private final EpisodicMemory memory;

    /**
     * Creates the job. {@code memory} performs the actual deletion in {@link EpisodicMemory#purgeExpired}.
     */
    public EpisodePurgeJob(EpisodicMemory memory) {
        this.memory = memory;
    }

    // @find: scheduled job or startup listener purge, episode purge job
    /**
     * Nightly at 03:41 server time, repeatedly asks {@link EpisodicMemory#purgeExpired} to delete up to
     * {@link #BATCH} expired episodes until a batch returns fewer rows than requested or {@link #MAX_BATCHES}
     * is reached. Side effect: rows are deleted from the episodes table; the total is logged when greater
     * than zero. A failure inside a batch propagates to the scheduler, which logs it and runs again the
     * next night.
     */
    @Scheduled(cron = "0 41 3 * * *")
    public void purge() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int removed = memory.purgeExpired(BATCH);
            total += removed;
            if (removed < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Purged {} expired episodes", total);
        }
    }
}
