// @find: tests for team room presence rules, thinking, browsing, using connector, in-flight tool call, site of address, headline run, waiting for you first, PresenceRulesTest, PresenceRules
// @what: Unit tests for how a run's latest steps become an agent's live state and which run an agent shows.
// @flow: Exercises PresenceRules
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.teamroom.PresenceRules.Activity;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.CallRecord;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.RunState;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.StepFact;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.ToolName;

/**
 * A running run's state comes from its latest model answer and the tool calls that have returned
 * since: none outstanding is thinking, an outstanding browser call is browsing (with its site), any
 * other outstanding call is using that connector.
 */
class PresenceRulesTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final Instant START = Instant.parse("2026-10-12T09:00:00Z");

    private static StepFact model(int position, CallRecord... calls) {
        return new StepFact(RUN, "model_call", position, START.plusSeconds(position), List.of(calls), null, null, null);
    }

    private static StepFact returned(int position, String callId, String tool, String url) {
        return new StepFact(RUN, "tool_call", position, START.plusSeconds(position), List.of(), callId, tool, url);
    }

    private static StepFact note(int position) {
        return new StepFact(RUN, "note", position, START.plusSeconds(position), List.of(), null, null, null);
    }

    // @find: test thinking before the first answer, presence rules
    @Test
    @DisplayName("a run with no model answer yet is thinking, since the run started or its last step")
    void thinkingBeforeTheFirstAnswer() {
        assertThat(PresenceRules.working(START, List.of(), null))
                .isEqualTo(new Activity(PresenceRules.THINKING, START, null, null, null));

        Activity afterNote = PresenceRules.working(START, List.of(note(1)), null);
        assertThat(afterNote.state()).isEqualTo(PresenceRules.THINKING);
        assertThat(afterNote.since()).isEqualTo(START.plusSeconds(1));
    }

    // @find: test thinking when every call returned, presence rules
    @Test
    @DisplayName("once every call of the latest answer has returned, the next model call is under way: thinking")
    void thinkingWhenEveryCallReturned() {
        List<StepFact> steps = List.of(
                model(2, new CallRecord("c1", "slack.post_message", "{}")),
                returned(3, "c1", "slack.post_message", null));

        Activity activity = PresenceRules.working(START, steps, null);

        assertThat(activity.state()).isEqualTo(PresenceRules.THINKING);
        assertThat(activity.since()).isEqualTo(START.plusSeconds(3));
    }

    // @find: test browsing names the site, presence rules
    @Test
    @DisplayName("an outstanding web browser call is browsing, with the site from its own address, without www")
    void browsingNamesTheSite() {
        List<StepFact> steps = List.of(model(
                4, new CallRecord("c1", "browser.open_page", "{\"url\":\"https://www.YouTube.com/watch?v=1\"}")));

        Activity activity = PresenceRules.working(START, steps, null);

        assertThat(activity)
                .isEqualTo(new Activity(PresenceRules.BROWSING, START.plusSeconds(4), "browser", "open_page", "youtube.com"));
        assertThat(PresenceRules.needsLastPage(steps)).isFalse();
    }

    // @find: test browsing falls back to the last page, presence rules
    @Test
    @DisplayName("a browser call with no address of its own (a click) is on the page the run last had open")
    void browsingFallsBackToTheLastPage() {
        List<StepFact> steps = List.of(model(5, new CallRecord("c9", "browser__click_button", "{\"text\":\"Next\"}")));

        assertThat(PresenceRules.needsLastPage(steps)).isTrue();
        assertThat(PresenceRules.working(START, steps, "https://news.example.org/today").site())
                .isEqualTo("news.example.org");
        assertThat(PresenceRules.working(START, steps, null).site()).isNull();
    }

    // @find: test using a connector, presence rules
    @Test
    @DisplayName("any other outstanding call is using that connector, named by server and tool")
    void usingAConnector() {
        List<StepFact> steps = List.of(model(2, new CallRecord("c1", "gmail__send_message", "{}")));

        Activity activity = PresenceRules.working(START, steps, null);

        assertThat(activity.state()).isEqualTo(PresenceRules.USING_TOOL);
        assertThat(activity.server()).isEqualTo("gmail");
        assertThat(activity.tool()).isEqualTo("send_message");
        assertThat(PresenceRules.needsLastPage(steps)).isFalse();
    }

    // @find: test the first outstanding call of the latest answer, presence rules
    @Test
    @DisplayName("only the latest answer counts, and its first call that has not returned is the one under way")
    void firstOutstandingCallOfTheLatestAnswer() {
        List<StepFact> steps = List.of(
                // An older answer whose call never shows a result here must not count.
                model(1, new CallRecord("old", "github.list_issues", "{}")),
                model(
                        3,
                        new CallRecord("c1", "knowledge.search", "{}"),
                        new CallRecord("c2", "browser.search_site", "{\"url\":\"docs.example.com\",\"query\":\"x\"}")),
                returned(4, "c1", "knowledge.search", null));

        Activity activity = PresenceRules.working(START, steps, null);

        assertThat(activity.state()).isEqualTo(PresenceRules.BROWSING);
        assertThat(activity.site()).isEqualTo("docs.example.com");
        assertThat(activity.since()).isEqualTo(START.plusSeconds(4));
    }

    // @find: test site of address, presence rules
    @Test
    @DisplayName("a site is the host in lower case, without www, and nothing for text that is not an address")
    void siteOfAddress() {
        assertThat(PresenceRules.siteOf("example.com/path?q=1")).isEqualTo("example.com");
        assertThat(PresenceRules.siteOf("http://WWW.Example.COM:8080/")).isEqualTo("example.com");
        assertThat(PresenceRules.siteOf("  ")).isNull();
        assertThat(PresenceRules.siteOf(null)).isNull();
        assertThat(PresenceRules.siteOf("not an address at all")).isNull();
    }

    // @find: test tool name forms, presence rules
    @Test
    @DisplayName("a tool name splits in its dotted form first, then the provider's double-underscore form")
    void toolNameForms() {
        assertThat(ToolName.of("browser.open_page")).isEqualTo(new ToolName("browser", "open_page"));
        assertThat(ToolName.of("browser__open_page")).isEqualTo(new ToolName("browser", "open_page"));
        assertThat(ToolName.of("zendesk.add__note")).isEqualTo(new ToolName("zendesk", "add__note"));
        assertThat(ToolName.of("webhook")).isEqualTo(new ToolName("webhook", ""));
        assertThat(ToolName.of(null)).isEqualTo(new ToolName("", ""));
    }

    private static RunState state(String state, boolean forYou, Instant since) {
        return new RunState(
                UUID.randomUUID(), state, since, forYou, false, null, null, null, null, null, null, null, null, null);
    }

    // @find: test headline prefers what waits for you, presence rules
    @Test
    @DisplayName("an agent shows the run that most needs a person, then the most recent")
    void headlinePrefersWhatWaitsForYou() {
        RunState browsing = state(PresenceRules.BROWSING, false, START.plusSeconds(50));
        RunState waitingOnSomeoneElse = state(PresenceRules.WAITING_APPROVAL, false, START.plusSeconds(10));
        RunState waitingOnYou = state(PresenceRules.WAITING_INPUT, true, START);
        RunState olderThinking = state(PresenceRules.THINKING, false, START.plusSeconds(1));
        RunState newerThinking = state(PresenceRules.THINKING, false, START.plusSeconds(2));

        assertThat(PresenceRules.headline(List.of(browsing, waitingOnSomeoneElse, waitingOnYou)))
                .contains(waitingOnYou);
        assertThat(PresenceRules.headline(List.of(browsing, waitingOnSomeoneElse))).contains(waitingOnSomeoneElse);
        assertThat(PresenceRules.headline(List.of(olderThinking, newerThinking))).contains(newerThinking);
        assertThat(PresenceRules.headline(List.of(state(PresenceRules.PRIVATE, false, START), newerThinking)))
                .contains(newerThinking);
        assertThat(PresenceRules.headline(List.of())).isEmpty();
    }

    // @find: test resting state, presence rules
    @Test
    @DisplayName("with nothing in progress a paused agent is paused and any other is idle")
    void restingState() {
        assertThat(PresenceRules.restingState("paused")).isEqualTo(PresenceRules.PAUSED);
        assertThat(PresenceRules.restingState("active")).isEqualTo(PresenceRules.IDLE);
    }
}
