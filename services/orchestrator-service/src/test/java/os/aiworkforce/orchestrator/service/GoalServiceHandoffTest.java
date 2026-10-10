// @find: tests for goal service handoff, goals, hand-off between agents, chain of tasks, predecessor results, request verbatim
// @what: Unit and integration tests (12 cases) for goal service handoff, for example: cut with marker says what it left out; cut with marker breaks at whitespace; cut with marker keeps surrogate pairs whole; a single predecessor gets five thousand.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Task;

/**
 * What a later step of a chain is given: the person's own request, the earlier work in full up to a
 * shared budget, which passages the first step read, and its own part - with every cut said aloud.
 */
class GoalServiceHandoffTest {

    private static final Function<UUID, String> NAMES = id -> "Research";

    private static Task done(String result) {
        Task task = new Task();
        task.setAgentId(UUID.randomUUID());
        task.setStatus("completed");
        task.setResult(result);
        return task;
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    // ---- Cutting ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a text that fits is returned exactly, and one that does not is cut with a marker naming how much")
    void cutWithMarkerSaysWhatItLeftOut() {
        assertThat(GoalService.cutWithMarker("short\n\ntext", 100)).isEqualTo("short\n\ntext");
        assertThat(GoalService.cutWithMarker(null, 100)).isEmpty();

        String text = "alpha beta gamma\n\ndelta epsilon zeta\n- eta theta";
        String cut = GoalService.cutWithMarker(text, 30);

        assertThat(cut).startsWith("alpha beta gamma\n\ndelta");
        assertThat(cut).matches("(?s).*\\n\\[\\.\\.\\. \\d+ more characters not shown]");
        String head = cut.substring(0, cut.lastIndexOf("\n[... "));
        int omitted = Integer.parseInt(cut.replaceAll("(?s).*\\[\\.\\.\\. (\\d+) more.*", "$1"));
        assertThat(head.length() + omitted).isEqualTo(text.length());
        assertThat(text).startsWith(head);
    }

    @Test
    @DisplayName("a cut falls on whitespace in the last stretch rather than in the middle of a word")
    void cutWithMarkerBreaksAtWhitespace() {
        String text = "word ".repeat(100);

        String cut = GoalService.cutWithMarker(text, 52);

        String head = cut.substring(0, cut.lastIndexOf("\n[... "));
        assertThat(head).endsWith("word");
        assertThat(head.length()).isLessThanOrEqualTo(52);
    }

    @Test
    @DisplayName("a cut never leaves half of an emoji at the end")
    void cutWithMarkerKeepsSurrogatePairsWhole() {
        String text = "x".repeat(49) + "😀" + "y".repeat(100); // the emoji spans index 49 and 50

        String cut = GoalService.cutWithMarker(text, 50);

        String head = cut.substring(0, cut.lastIndexOf("\n[... "));
        assertThat(Character.isHighSurrogate(head.charAt(head.length() - 1))).isFalse();
    }

    // ---- How much of earlier work is handed on ------------------------------------------------------

    @Test
    @DisplayName("one earlier step is handed on up to 5,000 characters, and says when it was cut")
    void aSinglePredecessorGetsFiveThousand() {
        String result = "line of the research findings\n".repeat(500); // 15,000 characters

        String handed = GoalService.handoffResults(List.of(done(result))).getFirst();

        String head = handed.substring(0, handed.lastIndexOf("\n[... "));
        assertThat(head.length()).isLessThanOrEqualTo(5_000).isGreaterThan(4_700);
        assertThat(handed).contains("\n[... ").endsWith("more characters not shown]");
        assertThat(head).contains("line of the research findings\nline of the research findings");
    }

    @Test
    @DisplayName("a result that fits is handed on whole, with no marker")
    void aShortResultIsNotCut() {
        String result = "# Findings\n\n- one\n- two\n";

        assertThat(GoalService.handoffResults(List.of(done(result))).getFirst()).isEqualTo(result);
    }

    @Test
    @DisplayName("several earlier steps share one budget, and a short one leaves its unused share to the others")
    void predecessorsShareABudget() {
        String big = "findings ".repeat(1_400); // 12,600 characters
        List<String> evenly = GoalService.handoffResults(List.of(done(big), done(big), done(big)));
        for (String handed : evenly) {
            assertThat(handed).endsWith("more characters not shown]");
        }
        int headTotal = evenly.stream()
                .mapToInt(handed -> handed.lastIndexOf("\n[... "))
                .sum();
        assertThat(headTotal).isLessThanOrEqualTo(10_000).isGreaterThan(9_500);

        List<String> uneven = GoalService.handoffResults(List.of(done("a short note"), done(big), done(big)));
        assertThat(uneven.getFirst()).isEqualTo("a short note");
        // 10,000 less the note, split between the two long ones, each under its own 5,000 ceiling.
        for (String handed : uneven.subList(1, 3)) {
            int head = handed.lastIndexOf("\n[... ");
            assertThat(head).isLessThanOrEqualTo(4_995).isGreaterThan(4_700);
        }
    }

    @Test
    @DisplayName("a step with no result is handed on as nothing")
    void aMissingResultIsEmpty() {
        assertThat(GoalService.handoffResults(List.of(done(null)))).containsExactly("");
    }

    // ---- The instruction a later step reads --------------------------------------------------------------

    @Test
    @DisplayName("a chain step keeps the person's request, puts the earlier work between it and the step's part, and has one 'Your part'")
    void chainStepHasOneRequestAndOnePart() {
        String request = "Research the three suppliers, then draft the email.\n  Tone: warm.";
        String instruction = GoalService.chainStepInstruction(request, "Draft the email");

        String preamble = GoalService.withHandoffPreamble(
                instruction, request, List.of(done("Supplier A is cheapest.")), List.of(), NAMES);

        assertThat(preamble).startsWith(GoalService.REQUEST_HEADING + request + "\n\nWork already done for this request:\n");
        assertThat(preamble).contains("- Research: Supplier A is cheapest.\n");
        assertThat(preamble).endsWith("\nYour part: Draft the email");
        assertThat(countOf(preamble, "Your part:")).isEqualTo(1);
        assertThat(countOf(preamble, "The person's request (verbatim):")).isEqualTo(1);
        assertThat(preamble).doesNotContain("Original request:");
    }

    @Test
    @DisplayName("a step the coordinator did not word gets the goal's description as the original request")
    void plainStepGetsTheOriginalRequest() {
        String preamble = GoalService.withHandoffPreamble(
                "Write the summary", "Research suppliers and write a summary for the board",
                List.of(done("Findings.")), List.of(), NAMES);

        assertThat(preamble)
                .startsWith("Original request: Research suppliers and write a summary for the board\n\n"
                        + "Work already done for this request:\n");
        assertThat(preamble).endsWith("\nYour part:\nWrite the summary");
        assertThat(countOf(preamble, "Your part:")).isEqualTo(1);
    }

    @Test
    @DisplayName("a step whose instruction already is the goal's description does not repeat it")
    void sameDescriptionIsNotRepeated() {
        String preamble = GoalService.withHandoffPreamble(
                "Do the thing", "Do the thing", List.of(done("Done.")), null, NAMES);

        assertThat(preamble).doesNotContain("Original request:");
        assertThat(preamble).startsWith("Work already done for this request:\n");
        assertThat(preamble).endsWith("\nYour part:\nDo the thing");
    }

    @Test
    @DisplayName("the titles of the passages the first step read are listed, so a [1] in its work can be followed")
    void passageTitlesAreListed() {
        String preamble = GoalService.withHandoffPreamble(
                "Write the reply",
                "Check the policy then reply",
                List.of(done("Policy says 5 days [1].")),
                List.of("[1] Refund policy, page 2", "[2] Support handbook"),
                NAMES);

        assertThat(preamble)
                .contains("The first step was given these passages from the workspace's documents")
                .contains("[1] Refund policy, page 2; [2] Support handbook.");
        assertThat(preamble.indexOf("Work already done")).isLessThan(preamble.indexOf("[1] Refund policy"));
        assertThat(preamble.indexOf("[1] Refund policy")).isLessThan(preamble.indexOf("Your part:"));
    }

    @Test
    @DisplayName("the passage titles are read back from the numbered block the coordinator puts at the start")
    void passageTitlesAreParsed() {
        String instruction = GoalService.PASSAGES_HEADING + " Base your answer on them:\n"
                + "[1] Refund policy, page 2\nRefunds are issued within 5 business days.\n\n"
                + "[2] Support handbook\nEscalate refunds over $500.\n\n"
                + "Request:\nDraft a reply";

        assertThat(GoalService.passageTitles(instruction))
                .containsExactly("[1] Refund policy, page 2", "[2] Support handbook");
        assertThat(GoalService.passageTitles("Draft a reply")).isEmpty();
        assertThat(GoalService.passageTitles(null)).isEmpty();
    }
}
