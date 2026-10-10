// @find: tests for conversational queries, questions about the assistant, small talk search, retrieval, knowledge base
// @what: Checks conversational questions about the assistant itself are handled in search.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RetrievalConversationalTest {

    private static RetrievalService.Passage passage(String content) {
        return new RetrievalService.Passage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SIH Quantminds.pptx", null, null, null,
                content, 0.5, false);
    }

    @Test
    void questionAboutTheAssistantGroundsNothing() {
        List<RetrievalService.Passage> found = List.of(passage("Name of the team and the mentor."));
        assertThat(RetrievalService.groundedPassages("What is your name", found, Set.of(found.get(0).chunkId())))
                .isEmpty();
    }

    @Test
    void shortQuestionKeepsOnlyPassagesSharingItsSubject() {
        RetrievalService.Passage refund = passage("Refund requests are handled within 30 days.");
        RetrievalService.Passage other = passage("Team members and the mentor.");
        assertThat(RetrievalService.groundedPassages("What is the refund window", List.of(refund, other), Set.of()))
                .containsExactly(refund);
    }
}
