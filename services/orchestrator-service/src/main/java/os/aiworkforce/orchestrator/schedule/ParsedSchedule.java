package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;

/**
 * A schedule phrase, turned into what the sweep needs to act on it.
 *
 * @param kind {@code recurring} or {@code once}
 * @param cron a Spring six-field cron expression, set when {@code kind} is {@code recurring}
 * @param runAt the single instant to run at, set when {@code kind} is {@code once}
 * @param description the schedule, echoed back in plain words
 */
public record ParsedSchedule(String kind, String cron, Instant runAt, String description) {}
