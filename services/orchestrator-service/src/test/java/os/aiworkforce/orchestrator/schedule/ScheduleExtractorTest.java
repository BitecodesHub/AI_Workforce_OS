// @find: tests for schedule extractor, timing phrase inside a sentence, every monday at 9am summarise, remind tomorrow at 3pm, everyday phrasing, ScheduleExtractorTest
// @what: Tests that a schedule written inside a sentence is found and split from the work, and that ordinary requests are not mistaken for schedules.
// @flow: Exercises ScheduleExtractor and ScheduleParser's everyday phrasing
package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleExtractorTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");
    /** A Saturday, 10:00 in Melbourne. */
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZONE).toInstant();

    @Test
    @DisplayName("a recurring phrase at the start is lifted out, the rest is the instruction")
    void recurringAtStart() {
        ScheduleExtractor.Extraction found = ScheduleExtractor.extract(
                        "every Monday at 9am summarise competitor news", ZONE, NOW)
                .orElseThrow();
        assertThat(found.phrase()).isEqualTo("every Monday at 9am");
        assertThat(found.parsed().cron()).isEqualTo("0 0 9 * * MON");
        assertThat(found.instruction()).isEqualTo("summarise competitor news");
    }

    @Test
    @DisplayName("a one-off phrase in the middle of a reminder keeps the sentence around it")
    void oneOffInTheMiddle() {
        ScheduleExtractor.Extraction found = ScheduleExtractor.extract(
                        "remind HR tomorrow at 3pm to send onboarding emails", ZONE, NOW)
                .orElseThrow();
        assertThat(found.phrase()).isEqualTo("tomorrow at 3pm");
        assertThat(found.parsed().kind()).isEqualTo("once");
        assertThat(found.parsed().runAt())
                .isEqualTo(ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, ZONE).toInstant());
        assertThat(found.instruction()).isEqualTo("remind HR to send onboarding emails");
    }

    @Test
    @DisplayName("a phrase at the end, with trailing punctuation, is found")
    void phraseAtTheEnd() {
        ScheduleExtractor.Extraction found = ScheduleExtractor.extract(
                        "Send me the pipeline summary every weekday at 8:30am.", ZONE, NOW)
                .orElseThrow();
        assertThat(found.parsed().cron()).isEqualTo("0 30 8 * * MON-FRI");
        assertThat(found.instruction()).isEqualTo("Send me the pipeline summary");
    }

    @Test
    @DisplayName("an @mention before the phrase still counts as the phrase opening the request")
    void afterMention() {
        ScheduleExtractor.Extraction found = ScheduleExtractor.extract(
                        "@Research every day check the news", ZONE, NOW)
                .orElseThrow();
        assertThat(found.parsed().cron()).isEqualTo("0 0 9 * * *");
        assertThat(found.instruction()).isEqualTo("@Research check the news");
    }

    @Test
    @DisplayName("the instruction keeps its line breaks")
    void keepsFormatting() {
        ScheduleExtractor.Extraction found = ScheduleExtractor.extract(
                        "Every weekday at 9am summarise tickets:\n- group them\n- flag urgent ones", ZONE, NOW)
                .orElseThrow();
        assertThat(found.instruction()).isEqualTo("summarise tickets:\n- group them\n- flag urgent ones");
    }

    @Test
    @DisplayName("in 2 minutes, for a schedule that must fire soon")
    void inMinutes() {
        ScheduleExtractor.Extraction found =
                ScheduleExtractor.extract("in 2 minutes say hello", ZONE, NOW).orElseThrow();
        assertThat(found.parsed().runAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(found.instruction()).isEqualTo("say hello");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "summarise what happened every day last week",
                "what is on today",
                "meet at 5 people",
                "write a summary of the market",
                ""
            })
    @DisplayName("ordinary requests are not schedules")
    void notSchedules(String text) {
        assertThat(ScheduleExtractor.extract(text, ZONE, NOW)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "at 3pm tomorrow|2026-10-11T04:00:00Z",
                "tomorrow 3pm|2026-10-11T04:00:00Z",
                "tomorrow at 3 p.m.|2026-10-11T04:00:00Z",
                "tomorrow at 9.30am|2026-10-10T22:30:00Z",
                "tomorrow morning|2026-10-10T22:00:00Z",
                "tonight at 8pm|2026-10-10T09:00:00Z",
                "at 3pm|2026-10-10T04:00:00Z",
                "at 9am|2026-10-10T22:00:00Z",
                "next monday at 9am|2026-10-11T22:00:00Z",
                "on friday at 2pm|2026-10-16T03:00:00Z",
                "in 30 mins|2026-10-09T23:30:00Z",
                "in an hour|2026-10-10T00:00:00Z",
                "in half an hour|2026-10-09T23:30:00Z"
            })
    @DisplayName("everyday one-off phrasing is read in the workspace's zone")
    void everydayOneOffs(String phrase, String expected) {
        ParsedSchedule parsed = ScheduleParser.parse(phrase, ZONE, NOW);
        assertThat(parsed.kind()).isEqualTo("once");
        assertThat(parsed.runAt()).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "every monday 9am|0 0 9 * * MON",
                "at 9am every monday|0 0 9 * * MON",
                "each day at 7pm|0 0 19 * * *",
                "everyday at 6:15am|0 15 6 * * *",
                "every 30 mins|0 */30 * * * *"
            })
    @DisplayName("everyday recurring phrasing")
    void everydayRecurring(String phrase, String cron) {
        assertThat(ScheduleParser.parse(phrase, ZONE, NOW).cron()).isEqualTo(cron);
    }
}
