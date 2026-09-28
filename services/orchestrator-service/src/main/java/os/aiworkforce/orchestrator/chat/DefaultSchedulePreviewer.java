package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.schedule.ScheduleParser;

/** The real {@link SchedulePreviewer}, wired into Spring so it can be swapped for a test double. */
@Component
public class DefaultSchedulePreviewer implements SchedulePreviewer {

    @Override
    public Optional<ParsedSchedule> tryParse(String text, ZoneId zone, Instant now) {
        return ScheduleParser.tryParse(text, zone, now);
    }
}
