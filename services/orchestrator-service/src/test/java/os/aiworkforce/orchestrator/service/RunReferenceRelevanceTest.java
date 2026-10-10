// @find: tests for run reference relevance, run reference, reference passages relevance, knowledge at run start, grounding
// @what: Unit and integration tests (2 cases) for run reference relevance, for example: unrelated passage is dropped; relevant passages are renumbered.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The passages a new run is given before its first model call are only the ones that bear on its
 * instruction - the same rule chat applies - so a run is never "grounded" in a slide that merely
 * shares a word with the question.
 */
class RunReferenceRelevanceTest {

    private static KnowledgeSearchTool.Cited cited(int number, String title, String content) {
        return new KnowledgeSearchTool.Cited(
                number, title, null, null, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1.0, content,
                false);
    }

    @Test
    @DisplayName("a passage that shares only filler with the instruction is dropped, and the search is no longer grounded")
    void unrelatedPassageIsDropped() {
        String query = "In one sentence, what is the capital of Australia? Do not use any tools.";
        KnowledgeSearchTool.Searched found = new KnowledgeSearchTool.Searched(
                query,
                List.of(cited(1, "SIH Quantminds.pptx", "Buildable prototype: three live connectors on one laptop.")),
                true,
                true,
                null);

        KnowledgeSearchTool.Searched kept = AgentRunner.relevantOnly(found);

        assertThat(kept.passages()).isEmpty();
        assertThat(kept.grounded()).isFalse();
    }

    @Test
    @DisplayName("relevant passages are kept, in order, and renumbered from 1")
    void relevantPassagesAreRenumbered() {
        String query = "What is our refund policy for annual plans?";
        KnowledgeSearchTool.Searched found = new KnowledgeSearchTool.Searched(
                query,
                List.of(
                        cited(1, "Deck.pptx", "Our team won a hackathon."),
                        cited(2, "Policies.pdf", "Refunds on annual plans are pro-rated within 30 days."),
                        cited(3, "Handbook.pdf", "The refund policy applies to every plan.")),
                true,
                false,
                null);

        KnowledgeSearchTool.Searched kept = AgentRunner.relevantOnly(found);

        assertThat(kept.grounded()).isTrue();
        assertThat(kept.passages())
                .extracting(KnowledgeSearchTool.Cited::number, KnowledgeSearchTool.Cited::documentTitle)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Policies.pdf"),
                        org.assertj.core.groups.Tuple.tuple(2, "Handbook.pdf"));
    }
}
