// @find: parsed schedule, schedule text result, kind cron or once, cron expression, run at, description, ParsedSchedule
// @what: Value record holding the result of reading schedule text such as 'every Monday at 9am'.
// @flow: Produced by ScheduleParser; used by ScheduleService
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
