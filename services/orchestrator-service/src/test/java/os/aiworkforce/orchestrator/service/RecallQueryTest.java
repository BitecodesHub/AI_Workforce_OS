// @find: tests for recall query, recall query, memory recall, search query built from request
// @what: Unit and integration tests (4 cases) for recall query, for example: the roster is not part of the request; the request after earlier context loses the roster too; a short query is sent as it is; a long query is cut at aword within the service limit.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The query a run recalls its agent's notes with. The memory service refuses one over 500
 * characters, and every General Employee instruction used to carry the workspace's roster of
 * colleagues after the request, so its recall failed (422) on every run.
 */
class RecallQueryTest {

    private static final String ROSTER = "\n\nAbout this workspace: besides you, it has 4 AI colleagues: Customer Support"
            + " (You triage support tickets.); HR (You handle people operations.). Point the person to the right one"
            + " when a request is really theirs.";

    @Test
    void theRosterIsNotPartOfTheRequest() {
        assertThat(AgentRunner.requestOf("List three types of business insurance." + ROSTER))
                .isEqualTo("List three types of business insurance.");
    }

    @Test
    void theRequestAfterEarlierContextLosesTheRosterToo() {
        String instruction = "Earlier in this conversation, for context:\nThe requester: hi\n\nRequest:\n"
                + "Give me two tips for a clear email subject line." + ROSTER;
        assertThat(AgentRunner.requestOf(instruction)).isEqualTo("Give me two tips for a clear email subject line.");
    }

    @Test
    void aShortQueryIsSentAsItIs() {
        assertThat(MemoryClient.recallQuery("  what does Priya prefer?  ")).isEqualTo("what does Priya prefer?");
        assertThat(MemoryClient.recallQuery(null)).isEmpty();
    }

    @Test
    void aLongQueryIsCutAtAWordWithinTheServiceLimit() {
        String longQuery = "word ".repeat(300);
        String sent = MemoryClient.recallQuery(longQuery);
        assertThat(sent.length()).isLessThanOrEqualTo(MemoryClient.MAX_RECALL_QUERY);
        assertThat(sent).endsWith("word");
    }
}
