// @find: session sweep, delete expired sessions, cleanup sessions, scheduled job, nightly cleanup, SessionSweep
// @what: Scheduled nightly job that deletes long-expired sessions.
package os.aiworkforce.identity.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.identity.repository.Sessions;

/**
 * Removes sessions that expired a while ago, so the table holds what is in use rather than every
 * sign-in the workspace ever made. A week of grace keeps recently expired rows for support questions
 * such as "when did this person last sign in".
 */
@Component
public class SessionSweep {

    private static final Logger log = LoggerFactory.getLogger(SessionSweep.class);
    static final Duration GRACE = Duration.ofDays(7);

    private final Sessions sessions;

    public SessionSweep(Sessions sessions) {
        this.sessions = sessions;
    }

    // @find: sweep expired sessions, scheduled cleanup, cron 3:17
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void sweep() {
        int removed = sessions.deleteExpiredBefore(Instant.now().minus(GRACE));
        if (removed > 0) {
            log.info("Removed {} expired sessions", removed);
        }
    }
}
