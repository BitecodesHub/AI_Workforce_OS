// @find: schedule previewer, schedule parse seam, test double for schedule parser, SchedulePreviewer, IntentDetector schedule
// @what: A small interface around the schedule parser so intent detection can be tested with its own reading.
// @flow: Implemented by DefaultSchedulePreviewer; used by IntentDetector.
package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.schedule.ScheduleParser;

/**
 * A seam around {@link ScheduleParser#tryParse}, so {@link IntentDetector}'s tests can supply a
 * schedule reading of their own rather than depending on the real parser's behaviour - which, at
 * the time this seam was written, is still a stub that never recognises anything.
 */
@FunctionalInterface
public interface SchedulePreviewer {

    Optional<ParsedSchedule> tryParse(String text, ZoneId zone, Instant now);

    /** Delegates to the real parser. What every caller outside a test uses. */
    SchedulePreviewer DEFAULT = ScheduleParser::tryParse;
}
