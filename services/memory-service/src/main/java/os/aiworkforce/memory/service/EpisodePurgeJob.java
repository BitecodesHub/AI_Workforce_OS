package os.aiworkforce.memory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes episodes past their retention date, a small batch at a time, every night. */
@Component
public class EpisodePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EpisodePurgeJob.class);
    static final int BATCH = 500;
    static final int MAX_BATCHES = 200;

    private final EpisodicMemory memory;

    public EpisodePurgeJob(EpisodicMemory memory) {
        this.memory = memory;
    }

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
