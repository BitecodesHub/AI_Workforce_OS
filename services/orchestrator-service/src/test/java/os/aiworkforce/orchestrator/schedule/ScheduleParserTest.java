package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The phrase matrix {@link ScheduleParser} promises: every accepted shape, its cron or run time,
 * and its plain-words description, plus the phrases it refuses.
 *
 * <p>{@code NOW} is fixed at the Monday this plan was written against, in the workspace's default
 * timezone, so "tomorrow at 3pm" resolves to the exact Tuesday the plan's own example describes.
 */
class ScheduleParserTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");
    private static final Instant NOW =
            ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZONE).toInstant();

    @Nested
    @DisplayName("recurring phrases")
    class Recurring {

        @Test
        @DisplayName("every day at 9am")
        void everyDay() {
            ParsedSchedule parsed = ScheduleParser.parse("every day at 9am", ZONE, NOW);
            assertThat(parsed.kind()).isEqualTo("recurring");
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * *");
            assertThat(parsed.description()).isEqualTo("Every day at 9:00 am");
        }

        @Test
        @DisplayName("daily at 17:30, 24-hour time")
        void dailyTwentyFourHour() {
            ParsedSchedule parsed = ScheduleParser.parse("daily at 17:30", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 30 17 * * *");
            assertThat(parsed.description()).isEqualTo("Every day at 5:30 pm");
        }

        @Test
        @DisplayName("every day with no time at all defaults to 9:00 am")
        void everyDayNoTime() {
            ParsedSchedule parsed = ScheduleParser.parse("every day", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * *");
            assertThat(parsed.description()).isEqualTo("Every day at 9:00 am");
        }

        @Test
        @DisplayName("every weekday at 9")
        void everyWeekday() {
            ParsedSchedule parsed = ScheduleParser.parse("every weekday at 9", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * MON-FRI");
            assertThat(parsed.description()).isEqualTo("Every weekday at 9:00 am");
        }

        @Test
        @DisplayName("weekdays at 8:45am, without the word every")
        void weekdaysBareForm() {
            ParsedSchedule parsed = ScheduleParser.parse("weekdays at 8:45am", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 45 8 * * MON-FRI");
            assertThat(parsed.description()).isEqualTo("Every weekday at 8:45 am");
        }

        @Test
        @DisplayName("every weekend at 10am")
        void everyWeekend() {
            ParsedSchedule parsed = ScheduleParser.parse("every weekend at 10am", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 10 * * SAT,SUN");
            assertThat(parsed.description()).isEqualTo("Every weekend at 10:00 am");
        }

        @Test
        @DisplayName("every monday at 9")
        void everyMonday() {
            ParsedSchedule parsed = ScheduleParser.parse("every monday at 9", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * MON");
            assertThat(parsed.description()).isEqualTo("Every Monday at 9:00 am");
        }

        @Test
        @DisplayName("every monday and thursday at 2pm")
        void mondayAndThursday() {
            ParsedSchedule parsed = ScheduleParser.parse("every monday and thursday at 2pm", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 14 * * MON,THU");
            assertThat(parsed.description()).isEqualTo("Every Monday and Thursday at 2:00 pm");
        }

        @Test
        @DisplayName("every mon, wed, fri at 9:30, comma-separated abbreviations")
        void commaSeparatedAbbreviations() {
            ParsedSchedule parsed = ScheduleParser.parse("every mon, wed, fri at 9:30", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 30 9 * * MON,WED,FRI");
            assertThat(parsed.description()).isEqualTo("Every Monday, Wednesday, and Friday at 9:30 am");
        }

        @Test
        @DisplayName("every week on friday at 4pm")
        void everyWeekOnFriday() {
            ParsedSchedule parsed = ScheduleParser.parse("every week on friday at 4pm", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 16 * * FRI");
            assertThat(parsed.description()).isEqualTo("Every Friday at 4:00 pm");
        }

        @Test
        @DisplayName("every hour")
        void everyHour() {
            assertThat(ScheduleParser.parse("every hour", ZONE, NOW).cron()).isEqualTo("0 0 * * * *");
        }

        @Test
        @DisplayName("hourly")
        void hourly() {
            ParsedSchedule parsed = ScheduleParser.parse("hourly", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 * * * *");
            assertThat(parsed.description()).isEqualTo("Every hour");
        }

        @Test
        @DisplayName("every 15 minutes")
        void every15Minutes() {
            ParsedSchedule parsed = ScheduleParser.parse("every 15 minutes", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 */15 * * * *");
            assertThat(parsed.description()).isEqualTo("Every 15 minutes");
        }

        @Test
        @DisplayName("every 2 hours")
        void every2Hours() {
            ParsedSchedule parsed = ScheduleParser.parse("every 2 hours", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 */2 * * *");
            assertThat(parsed.description()).isEqualTo("Every 2 hours");
        }

        @Test
        @DisplayName("every month on the 1st at 9am")
        void monthOnFirst() {
            ParsedSchedule parsed = ScheduleParser.parse("every month on the 1st at 9am", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 1 * *");
            assertThat(parsed.description()).isEqualTo("On the 1st of every month at 9:00 am");
        }

        @Test
        @DisplayName("on the 15th of every month at noon")
        void nthOfMonth() {
            ParsedSchedule parsed = ScheduleParser.parse("on the 15th of every month at noon", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 12 15 * *");
            assertThat(parsed.description()).isEqualTo("On the 15th of every month at 12:00 pm");
        }

        @Test
        @DisplayName("on the last day of every month at 5pm, using cron's L")
        void lastDayOfMonth() {
            ParsedSchedule parsed = ScheduleParser.parse("on the last day of every month at 5pm", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 17 L * *");
            assertThat(parsed.description()).isEqualTo("On the last day of every month at 5:00 pm");
        }

        @Test
        @DisplayName("case is ignored")
        void caseInsensitive() {
            ParsedSchedule parsed = ScheduleParser.parse("EVERY DAY AT 9AM", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * *");
        }

        @Test
        @DisplayName("midnight and explicit minutes")
        void midnightAndMinutes() {
            assertThat(ScheduleParser.parse("every day at midnight", ZONE, NOW).cron())
                    .isEqualTo("0 0 0 * * *");
            assertThat(ScheduleParser.parse("every day at 09:05", ZONE, NOW).cron())
                    .isEqualTo("0 5 9 * * *");
        }
    }

    @Nested
    @DisplayName("one-off phrases")
    class Once {

        @Test
        @DisplayName("tomorrow at 3pm, matching the plan's own worked example")
        void tomorrow() {
            ParsedSchedule parsed = ScheduleParser.parse("tomorrow at 3pm", ZONE, NOW);
            assertThat(parsed.kind()).isEqualTo("once");
            assertThat(parsed.description()).isEqualTo("Once, on Tue 29 Sep 2026 at 3:00 pm");
            assertThat(parsed.runAt()).isEqualTo(
                    ZonedDateTime.of(2026, 9, 29, 15, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("today at a time still ahead of now")
        void todayStillAhead() {
            ParsedSchedule parsed = ScheduleParser.parse("today at 5pm", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(
                    ZonedDateTime.of(2026, 9, 28, 17, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("today at a time already passed is refused")
        void todayAlreadyPassed() {
            assertThatThrownBy(() -> ScheduleParser.parse("today at 9am", ZONE, NOW))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("in 20 minutes")
        void inMinutes() {
            ParsedSchedule parsed = ScheduleParser.parse("in 20 minutes", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(NOW.plusSeconds(20 * 60));
        }

        @Test
        @DisplayName("in 2 hours")
        void inHours() {
            ParsedSchedule parsed = ScheduleParser.parse("in 2 hours", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(NOW.plusSeconds(2 * 3600));
        }

        @Test
        @DisplayName("in 3 days")
        void inDays() {
            ParsedSchedule parsed = ScheduleParser.parse("in 3 days", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(NOW.plusSeconds(3 * 86400));
        }

        @Test
        @DisplayName("on 1 October at 9am, no year, rolls to the next occurrence ahead of now")
        void onDayMonthNoYear() {
            ParsedSchedule parsed = ScheduleParser.parse("on 1 October at 9am", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(
                    ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("on 2026-10-01 09:00, an explicit date and time")
        void onIsoDateAndTime() {
            ParsedSchedule parsed = ScheduleParser.parse("on 2026-10-01 09:00", ZONE, NOW);
            assertThat(parsed.runAt()).isEqualTo(
                    ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("an ISO instant, parsed directly")
        void isoInstant() {
            ParsedSchedule parsed = ScheduleParser.parse("2026-10-01T09:00:00Z", ZONE, NOW);
            assertThat(parsed.kind()).isEqualTo("once");
            assertThat(parsed.runAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        }

        @Test
        @DisplayName("a past ISO instant is refused")
        void pastIsoInstant() {
            assertThatThrownBy(() -> ScheduleParser.parse("2020-01-01T09:00:00Z", ZONE, NOW))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("raw cron, passed through")
    class RawCron {

        @Test
        @DisplayName("a six-field Spring cron expression")
        void sixField() {
            ParsedSchedule parsed = ScheduleParser.parse("0 0 9 * * MON-FRI", ZONE, NOW);
            assertThat(parsed.kind()).isEqualTo("recurring");
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * MON-FRI");
        }

        @Test
        @DisplayName("a five-field cron is prefixed with a seconds field of 0")
        void fiveFieldPrefixed() {
            ParsedSchedule parsed = ScheduleParser.parse("0 9 * * *", ZONE, NOW);
            assertThat(parsed.cron()).isEqualTo("0 0 9 * * *");
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("blank text is refused")
        void blank() {
            assertThatThrownBy(() -> ScheduleParser.parse("   ", ZONE, NOW)).isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("nonsense is refused with examples that work")
        void nonsense() {
            assertThatThrownBy(() -> ScheduleParser.parse("whenever it feels right", ZONE, NOW))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.details()).containsKey("examples");
                        @SuppressWarnings("unchecked")
                        List<String> examples = (List<String>) e.details().get("examples");
                        assertThat(examples).isNotEmpty();
                    });
        }

        @Test
        @DisplayName("every 3 minutes is refused: five-minute floor")
        void tooFrequent() {
            assertThatThrownBy(() -> ScheduleParser.parse("every 3 minutes", ZONE, NOW))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("every month on the 30th is refused: not every month has one")
        void monthlyDayTooHigh() {
            assertThatThrownBy(() -> ScheduleParser.parse("every month on the 30th at 9am", ZONE, NOW))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("an unknown day name is refused")
        void unknownDay() {
            assertThatThrownBy(() -> ScheduleParser.parse("every someday at 9am", ZONE, NOW))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("tryParse answers empty rather than throwing")
        void tryParseIsEmptyOnFailure() {
            assertThat(ScheduleParser.tryParse("whenever it feels right", ZONE, NOW)).isEmpty();
            assertThat(ScheduleParser.tryParse("every day at 9am", ZONE, NOW)).isPresent();
        }
    }

    @Nested
    @DisplayName("nextRuns")
    class NextRuns {

        @Test
        @DisplayName("a weekday schedule skips the weekend")
        void weekdaySkipsWeekend() {
            // NOW is a Monday; five weekday firings from here land Tue..Fri then the next Monday.
            ParsedSchedule parsed = ScheduleParser.parse("every weekday at 9am", ZONE, NOW);
            List<Instant> runs = ScheduleParser.nextRuns(parsed, ZONE, NOW, 5);
            assertThat(runs).hasSize(5);
            List<String> days = runs.stream()
                    .map(i -> ZonedDateTime.ofInstant(i, ZONE).getDayOfWeek().toString())
                    .toList();
            assertThat(days).containsExactly("TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "MONDAY");
        }

        @Test
        @DisplayName("a monthly schedule advances a full month at a time")
        void monthly() {
            ParsedSchedule parsed = ScheduleParser.parse("every month on the 1st at 9am", ZONE, NOW);
            List<Instant> runs = ScheduleParser.nextRuns(parsed, ZONE, NOW, 3);
            assertThat(runs).containsExactly(
                    ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE).toInstant(),
                    ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZONE).toInstant(),
                    ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("the last day of the month lands on the 30th in a 30-day month")
        void lastDayOfShortMonth() {
            ParsedSchedule parsed = ScheduleParser.parse("on the last day of every month at 5pm", ZONE, NOW);
            List<Instant> runs = ScheduleParser.nextRuns(parsed, ZONE, NOW, 1);
            assertThat(runs).containsExactly(
                    ZonedDateTime.of(2026, 9, 30, 17, 0, 0, 0, ZONE).toInstant());
        }

        @Test
        @DisplayName("a once schedule in the future answers itself, once")
        void onceInFuture() {
            ParsedSchedule parsed = ScheduleParser.parse("tomorrow at 3pm", ZONE, NOW);
            assertThat(ScheduleParser.nextRuns(parsed, ZONE, NOW, 5)).containsExactly(parsed.runAt());
        }

        @Test
        @DisplayName("a once schedule already in the past answers nothing")
        void oncePast() {
            ParsedSchedule already = new ParsedSchedule("once", null, NOW.minusSeconds(60), "irrelevant");
            assertThat(ScheduleParser.nextRuns(already, ZONE, NOW, 5)).isEmpty();
        }

        @Test
        @DisplayName("across the daylight-saving change, a daily 9am schedule keeps firing at 9am local time")
        void daylightSavingBoundary() {
            // Australia/Melbourne moves to daylight saving on the first Sunday of October 2026.
            Instant lateSeptember = ZonedDateTime.of(2026, 9, 29, 9, 0, 0, 0, ZONE).toInstant();
            ParsedSchedule parsed = ScheduleParser.parse("every day at 9am", ZONE, lateSeptember);
            List<Instant> runs = ScheduleParser.nextRuns(parsed, ZONE, lateSeptember, 7);
            for (Instant run : runs) {
                assertThat(ZonedDateTime.ofInstant(run, ZONE).toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
            }
        }
    }
}
