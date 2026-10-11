// @find: default schedule previewer, parse schedule from chat text, every day at, schedule phrase, DefaultSchedulePreviewer, ScheduleParser seam
// @what: The real SchedulePreviewer bean that reads a schedule out of a chat message.
// @flow: Called by IntentDetector; calls ScheduleParser.
package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.schedule.ParsedSchedule;

/** The real {@link SchedulePreviewer}, wired into Spring so it can be swapped for a test double. */
@Component
public class DefaultSchedulePreviewer implements SchedulePreviewer {

    /**
     * Delegates to {@link SchedulePreviewer#DEFAULT}: the whole-message parser first, then the phrase extractor. Pure; no side effects.
     */
    @Override
    public Optional<ParsedSchedule> tryParse(String text, ZoneId zone, Instant now) {
        return SchedulePreviewer.DEFAULT.tryParse(text, zone, now);
    }
}
