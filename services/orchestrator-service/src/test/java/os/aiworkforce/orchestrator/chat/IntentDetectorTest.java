package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.schedule.ParsedSchedule;

class IntentDetectorTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");
    private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

    /** Recognises anything with a timing marker, standing in for the real parser under test. */
    private static final SchedulePreviewer ALWAYS_PARSES =
            (text, zone, now) -> Optional.of(new ParsedSchedule("recurring", "0 0 9 * * *", null, text));

    private static final SchedulePreviewer NEVER_PARSES = (text, zone, now) -> Optional.empty();

    @Test
    @DisplayName("a timing phrase the parser accepts is a schedule")
    void scheduleWhenParserAgrees() {
        assertThat(IntentDetector.detect("every weekday at 9am summarise support tickets", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.SCHEDULE);
        assertThat(IntentDetector.detect("tomorrow at 5pm send the report", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.SCHEDULE);
    }

    @Test
    @DisplayName("a timing marker the parser refuses is not a schedule")
    void notAScheduleWhenParserRefuses() {
        assertThat(IntentDetector.detect("every day I think about this", ZONE, NOW, NEVER_PARSES))
                .isNotEqualTo(IntentDetector.Intent.SCHEDULE);
    }

    @Test
    @DisplayName("text with no timing marker is never read as a schedule, even if the parser would take it")
    void noMarkerNeverSchedule() {
        assertThat(IntentDetector.detect("draft a welcome email for Priya", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    @Test
    @DisplayName("a question with no action verb is a documents search")
    void questionIsDocuments() {
        assertThat(IntentDetector.detect("What is our refund policy?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
        assertThat(IntentDetector.detect("Does the handbook cover parental leave", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
    }

    @Test
    @DisplayName("search and find-in phrasing is a documents search even without a question mark")
    void searchPhrasingIsDocuments() {
        assertThat(IntentDetector.detect("search the handbook for parental leave", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
        assertThat(IntentDetector.detect("find in the handbook the leave policy", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
    }

    @Test
    @DisplayName("a question that also asks for an action is work, not a documents search")
    void questionWithActionVerbIsWork() {
        assertThat(IntentDetector.detect("Can you draft a reply to this ticket?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    @Test
    @DisplayName("an ordinary instruction is work")
    void ordinaryInstructionIsWork() {
        assertThat(IntentDetector.detect("draft a welcome email for Priya", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    @Test
    @DisplayName("the timing phrase is lifted out of the instruction, leaving the action")
    void withoutTimingPhraseStripsTheMarker() {
        assertThat(IntentDetector.withoutTimingPhrase("every weekday at 9am summarise support tickets"))
                .isEqualTo("summarise support tickets");
        assertThat(IntentDetector.withoutTimingPhrase("tomorrow at 5pm send the report"))
                .isEqualTo("send the report");
    }

    @Test
    @DisplayName("text with no recognisable timing phrase is returned whole, just stripped")
    void withoutTimingPhraseLeavesOrdinaryTextAlone() {
        assertThat(IntentDetector.withoutTimingPhrase("  draft a welcome email  ")).isEqualTo("draft a welcome email");
    }
}
