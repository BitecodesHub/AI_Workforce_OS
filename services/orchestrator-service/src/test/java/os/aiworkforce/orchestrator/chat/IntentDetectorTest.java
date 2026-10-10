// @find: tests for intent detector, chat, schedule when parser agrees, not aschedule when parser refuses, no marker never schedule, question is documents, search phrasing is documents, question with action verb is work, help request is work, policy question is documents, IntentDetectorTest, IntentDetector
// @what: Tests for IntentDetector in the orchestrator chat package (15 test methods).
// @flow: Exercises IntentDetector
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

    // @find: test schedule when parser agrees, intent detector
    @Test
    @DisplayName("a timing phrase the parser accepts is a schedule")
    void scheduleWhenParserAgrees() {
        assertThat(IntentDetector.detect("every weekday at 9am summarise support tickets", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.SCHEDULE);
        assertThat(IntentDetector.detect("tomorrow at 5pm send the report", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.SCHEDULE);
    }

    // @find: test not aschedule when parser refuses, intent detector
    @Test
    @DisplayName("a timing marker the parser refuses is not a schedule")
    void notAScheduleWhenParserRefuses() {
        assertThat(IntentDetector.detect("every day I think about this", ZONE, NOW, NEVER_PARSES))
                .isNotEqualTo(IntentDetector.Intent.SCHEDULE);
    }

    // @find: test no marker never schedule, intent detector
    @Test
    @DisplayName("text with no timing marker is never read as a schedule, even if the parser would take it")
    void noMarkerNeverSchedule() {
        assertThat(IntentDetector.detect("draft a welcome email for Priya", ZONE, NOW, ALWAYS_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    // @find: test question is documents, intent detector
    @Test
    @DisplayName("a question with no action verb is a documents search")
    void questionIsDocuments() {
        assertThat(IntentDetector.detect("What is our refund policy?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
        assertThat(IntentDetector.detect("Does the handbook cover parental leave", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
    }

    // @find: test search phrasing is documents, intent detector
    @Test
    @DisplayName("search and find-in phrasing is a documents search even without a question mark")
    void searchPhrasingIsDocuments() {
        assertThat(IntentDetector.detect("search the handbook for parental leave", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
        assertThat(IntentDetector.detect("find in the handbook the leave policy", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
    }

    // @find: test question with action verb is work, intent detector
    @Test
    @DisplayName("a question that also asks for an action is work, not a documents search")
    void questionWithActionVerbIsWork() {
        assertThat(IntentDetector.detect("Can you draft a reply to this ticket?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    // @find: test help request is work, intent detector
    @Test
    @DisplayName("a help request with no document in it is work, not a documents search")
    void helpRequestIsWork() {
        assertThat(IntentDetector.detect("Can you help me plan my week?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    // @find: test policy question is documents, intent detector
    @Test
    @DisplayName("a plain policy question is a documents search")
    void policyQuestionIsDocuments() {
        assertThat(IntentDetector.detect("What is our leave policy?", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.DOCUMENTS);
    }

    // @find: test ordinary instruction is work, intent detector
    @Test
    @DisplayName("an ordinary instruction is work")
    void ordinaryInstructionIsWork() {
        assertThat(IntentDetector.detect("draft a welcome email for Priya", ZONE, NOW, NEVER_PARSES))
                .isEqualTo(IntentDetector.Intent.WORK);
    }

    // @find: test without timing phrase strips the marker, intent detector
    @Test
    @DisplayName("the timing phrase is lifted out of the instruction, leaving the action")
    void withoutTimingPhraseStripsTheMarker() {
        assertThat(IntentDetector.withoutTimingPhrase("every weekday at 9am summarise support tickets"))
                .isEqualTo("summarise support tickets");
        assertThat(IntentDetector.withoutTimingPhrase("tomorrow at 5pm send the report"))
                .isEqualTo("send the report");
    }

    // @find: test without timing phrase leaves ordinary text alone, intent detector
    @Test
    @DisplayName("text with no recognisable timing phrase is returned whole, just stripped")
    void withoutTimingPhraseLeavesOrdinaryTextAlone() {
        assertThat(IntentDetector.withoutTimingPhrase("  draft a welcome email  "))
                .isEqualTo("draft a welcome email");
    }

    // @find: test without timing phrase keeps the rest verbatim, intent detector
    @Test
    @DisplayName("everything but the timing phrase stays exactly as written: line breaks, indents and lists")
    void withoutTimingPhraseKeepsTheRestVerbatim() {
        String text = "Every Monday at 8am summarise the weekly report:\n\n  1. revenue\n  2. churn\n";

        assertThat(IntentDetector.withoutTimingPhrase(text))
                .isEqualTo("summarise the weekly report:\n\n  1. revenue\n  2. churn");
    }

    // @find: test without timing phrase mid sentence, intent detector
    @Test
    @DisplayName("a phrase in the middle is lifted out without joining the two halves into one run-on")
    void withoutTimingPhraseMidSentence() {
        assertThat(IntentDetector.withoutTimingPhrase("Send the roster every Friday at 4pm to the team"))
                .isEqualTo("Send the roster to the team");
    }

    // @find: test without timing phrase never returns nothing, intent detector
    @Test
    @DisplayName("a message that is only a timing phrase is returned whole rather than as nothing")
    void withoutTimingPhraseNeverReturnsNothing() {
        assertThat(IntentDetector.withoutTimingPhrase("every weekday at 9am")).isEqualTo("every weekday at 9am");
    }

    // @find: test refers to documents, intent detector
    @Test
    @DisplayName("words that name the workspace's own documents are recognised, whatever else the request asks")
    void refersToDocuments() {
        assertThat(IntentDetector.refersToDocuments("Draft a reply using our refund policy"))
                .isTrue();
        assertThat(IntentDetector.refersToDocuments("Reply as the handbook says")).isTrue();
        assertThat(IntentDetector.refersToDocuments("Write the summary from the uploaded docs"))
                .isTrue();
        assertThat(IntentDetector.refersToDocuments("check the Knowledge Base first")).isTrue();
        assertThat(IntentDetector.refersToDocuments("Follow our escalation procedures")).isTrue();
        assertThat(IntentDetector.refersToDocuments("Draft a welcome email for Priya"))
                .isFalse();
        assertThat(IntentDetector.refersToDocuments("")).isFalse();
        assertThat(IntentDetector.refersToDocuments(null)).isFalse();
    }
}
