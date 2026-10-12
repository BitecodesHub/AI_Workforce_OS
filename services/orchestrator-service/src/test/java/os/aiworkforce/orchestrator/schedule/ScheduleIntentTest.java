// @find: tests for schedule intent, clearly asks for a schedule, date describes the subject, starts next Monday is not a schedule, remind me at, schedule a meeting is work, ScheduleIntentTest
// @what: Tests that only a message that asks for timed or repeated work reads as a schedule, and that a date describing the request never does.
// @flow: Exercises ScheduleIntent with the real ScheduleExtractor and ScheduleParser
package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleIntentTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");
    /** A Saturday, 10:00 in Melbourne. */
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZONE).toInstant();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "every Monday at 9am summarise competitor news",
                "Every weekday at 8:30am send me the pipeline summary",
                "Send me the pipeline summary every weekday at 8:30am.",
                "Daily at 7am summarise overnight alerts",
                "Tomorrow at 9am send the board pack to the directors",
                "In 2 hours check whether the website is back up",
                "remind HR tomorrow at 3pm to send onboarding emails",
                "Remind me at 4pm to call the supplier",
                "Please remind me tomorrow at 9am to chase the invoice",
                "Schedule a summary of new support tickets every Friday at 5pm",
                "Set a reminder for Friday at 2pm to renew the domain",
                "Please post the team update on Friday at 4pm",
                "Can you send the sales report to Sam every Monday at 9am",
                "Send the board pack to the directors tomorrow at 9am",
                "Send the report tomorrow at 9am",
                "@Research every Monday at 9am summarise competitor news",
                "every weekday at 9am",
                "Draft a weekly status update every Friday at 4pm"
            })
    @DisplayName("a request for timed or repeated work is a schedule")
    void asksForSchedule(String text) {
        assertThat(ScheduleExtractor.extract(text, ZONE, NOW).isPresent()
                        || ScheduleParser.tryParse(text, ZONE, NOW).isPresent())
                .as("a schedule can be read from: %s", text)
                .isTrue();
        assertThat(ScheduleIntent.asksForSchedule(text, ZONE, NOW)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Draft a welcome email for Jane, who starts next Monday",
                "Draft a welcome email for Jane who starts on Monday at 9am",
                "Write an email telling the team our stand-up is every Monday at 9am in Room 2",
                "Prepare the agenda for the board meeting tomorrow at 10am",
                "Summarise the notes from today at 3pm",
                "Book a table for four tomorrow at 7pm",
                "Schedule a meeting with Jane next Monday at 10am",
                "Next Monday is Jane's first day, draft a welcome email for her",
                "Draft a reply saying the invoice is due on Friday at 5pm",
                "Tell the client the delivery will arrive tomorrow at 9am",
                "Draft an announcement that the office closes every Friday at 4pm",
                "Remind the team that the office closes at 4pm today",
                "Move the interview to Monday at 10am",
                "Write a summary of what happened every day last week",
                "What is on tomorrow at 9am?",
                "Draft the minutes of yesterday's meeting",
                "Create a weekly report of support tickets",
                "Draft a welcome email for Priya, who joins on Monday"
            })
    @DisplayName("a date that only describes the request is not a schedule")
    void describesTheRequest(String text) {
        assertThat(ScheduleIntent.asksForSchedule(text, ZONE, NOW)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "?"})
    @DisplayName("nothing, or only a question mark, is never a schedule")
    void blank(String text) {
        assertThat(ScheduleIntent.asksForSchedule(text, ZONE, NOW)).isFalse();
        assertThat(ScheduleIntent.asksForSchedule(null, ZONE, NOW)).isFalse();
    }
}
