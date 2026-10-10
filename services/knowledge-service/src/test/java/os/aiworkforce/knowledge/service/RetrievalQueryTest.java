// @find: tests for search query wording, conversational words stripped, how a person asks, question phrasing, retrieval, knowledge base
// @what: Checks the way a question is phrased does not decide whether the document is found.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a person asks should not decide whether their document is found. */
class RetrievalQueryTest {

    @Test
    @DisplayName("drops the words a person uses to ask, keeping what they want found")
    void dropsConversationalWords() {
        assertThat(RetrievalService.withoutConversationalWords("Tell me about our SIH PROJECT"))
                .isEqualTo("me about our SIH PROJECT");
        assertThat(RetrievalService.withoutConversationalWords("Please explain the leave policy"))
                .isEqualTo("the leave policy");
    }

    @Test
    @DisplayName("a query made only of such words is kept as it was, so it still searches for something")
    void keepsAnAllConversationalQuery() {
        assertThat(RetrievalService.withoutConversationalWords("tell me")).isEqualTo("me");
        assertThat(RetrievalService.withoutConversationalWords("please")).isEqualTo("please");
        assertThat(RetrievalService.withoutConversationalWords(null)).isEmpty();
    }
}
