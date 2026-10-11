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
 * schedule reading of their own rather than depending on the real parser's behaviour.
 *
 * <p>Implemented by {@link DefaultSchedulePreviewer}, the Spring bean {@link IntentDetector} uses
 * to decide whether a message asks for work on a schedule. Previewing only reads text; it creates no
 * schedule.
 */
@FunctionalInterface
public interface SchedulePreviewer {

    /**
     * Reads a schedule out of the text, in the given zone and relative to {@code now}; empty when the text names none.
     */
    Optional<ParsedSchedule> tryParse(String text, ZoneId zone, Instant now);

    /**
     * Delegates to the real parser, and failing a whole-message schedule, to the phrase written
     * inside the sentence ("every Monday at 9am summarise competitor news"). What every caller
     * outside a test uses.
     */
    SchedulePreviewer DEFAULT = (text, zone, now) -> ScheduleParser.tryParse(text, zone, now)
            .or(() -> os.aiworkforce.orchestrator.schedule.ScheduleExtractor.extract(text, zone, now)
                    .map(os.aiworkforce.orchestrator.schedule.ScheduleExtractor.Extraction::parsed));
}
