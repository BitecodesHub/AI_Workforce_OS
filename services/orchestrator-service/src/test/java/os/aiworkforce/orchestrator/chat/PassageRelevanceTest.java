// @find: tests for passage relevance, chat, what is your name is conversational and attaches nothing, greetings thanks and meta questions are conversational, genuine questions are not conversational, unrelated passages are dropped, genuine document question still grounds, passages below the score floor are dropped, an action in aconnected service attaches no unrelated document, an action still grounds when it shares real subject words, PassageRelevanceTest, PassageRelevance
// @what: Tests for PassageRelevance in the orchestrator chat package (11 test methods).
// @flow: Exercises PassageRelevance
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PassageRelevanceTest {

    private static KnowledgeClient.Passage passage(String title, String content, double score) {
        return new KnowledgeClient.Passage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), title, null, null, null, content, score);
    }

    private static final List<KnowledgeClient.Passage> HACKATHON = List.of(
            passage("SIH Quantminds.pptx", "Problem statement, team members and the proposed solution.", 0.9),
            passage("SIH Quantminds.pptx", "What is the impact? Your solution is feasible.", 0.8),
            passage("SIH Quantminds.pptx", "Name of the team and the mentor.", 0.7));

    // @find: test what is your name is conversational and attaches nothing, passage relevance
    @Test
    void whatIsYourNameIsConversationalAndAttachesNothing() {
        assertThat(PassageRelevance.isConversational("@Customer Support What is your name")).isTrue();
        assertThat(PassageRelevance.relevant("What is your name", HACKATHON)).isEmpty();
    }

    // @find: test greetings thanks and meta questions are conversational, passage relevance
    @Test
    void greetingsThanksAndMetaQuestionsAreConversational() {
        for (String text : List.of("hi", "Hello there!", "thanks a lot", "who are you?", "what can you do",
                "How are you today", "introduce yourself")) {
            assertThat(PassageRelevance.isConversational(text)).as(text).isTrue();
        }
    }

    // @find: test genuine questions are not conversational, passage relevance
    @Test
    void genuineQuestionsAreNotConversational() {
        assertThat(PassageRelevance.isConversational("What is our refund policy for annual plans?")).isFalse();
        assertThat(PassageRelevance.isConversational("What documents do you have?")).isFalse();
    }

    // @find: test unrelated passages are dropped, passage relevance
    @Test
    void unrelatedPassagesAreDropped() {
        assertThat(PassageRelevance.relevant("What are the refund rules for annual plans", HACKATHON)).isEmpty();
    }

    // @find: test genuine document question still grounds, passage relevance
    @Test
    void genuineDocumentQuestionStillGrounds() {
        KnowledgeClient.Passage refund = passage(
                "Refund policy", "Annual plans can be refunded within 30 days; refund rules are set by finance.", 0.9);
        List<KnowledgeClient.Passage> kept = PassageRelevance.relevant(
                "What are the refund rules for annual plans", List.of(refund, HACKATHON.get(0)));
        assertThat(kept).containsExactly(refund);
    }

    // @find: test passages below the score floor are dropped, passage relevance
    @Test
    void passagesBelowTheScoreFloorAreDropped() {
        KnowledgeClient.Passage weak = passage("Refund policy", "Refund rules for annual plans.", 0.01);
        assertThat(PassageRelevance.relevant("refund rules for annual plans", List.of(weak))).isEmpty();
    }

    /* The deck that was attached to "create a new repo named test repo by aiworkforce" in live use. */
    private static final KnowledgeClient.Passage DECK = passage(
            "SIH Quantminds.pptx",
            "Create a new test platform for the team. Named mentors review each new repo of the prototype.",
            0.6);

    // @find: test an action in aconnected service attaches no unrelated document, passage relevance
    @Test
    void anActionInAConnectedServiceAttachesNoUnrelatedDocument() {
        String request = "create a new repo named test repo by aiworkforce";
        assertThat(PassageRelevance.isConnectorAction(request)).isTrue();
        assertThat(PassageRelevance.relevant(request, List.of(DECK))).isEmpty();
        assertThat(PassageRelevance.relevant("@Engineering create a new private repo named aiworkforce-test-repo",
                        List.of(DECK)))
                .isEmpty();
        assertThat(PassageRelevance.relevant("Open an issue in acme/website about the checkout button", HACKATHON))
                .isEmpty();
    }

    // @find: test an action still grounds when it shares real subject words, passage relevance
    @Test
    void anActionStillGroundsWhenItSharesRealSubjectWords() {
        KnowledgeClient.Passage policy = passage(
                "Refund policy", "Annual plans can be refunded within 30 days; refund rules are set by finance.", 0.9);
        List<KnowledgeClient.Passage> kept = PassageRelevance.relevant(
                "Create an issue to update the refund rules for annual plans", List.of(policy, DECK));
        assertThat(kept).containsExactly(policy);
    }

    // @find: test questions about abilities are conversational, passage relevance
    @Test
    void questionsAboutAbilitiesAreConversational() {
        for (String text : List.of("which github abilities do you have?", "What tools do you have",
                "what are your GitHub capabilities", "what can you do in Slack")) {
            assertThat(PassageRelevance.isConversational(text)).as(text).isTrue();
        }
        assertThat(PassageRelevance.relevant("which github abilities do you have?", HACKATHON)).isEmpty();
    }

    // @find: test document questions are not actions, passage relevance
    @Test
    void documentQuestionsAreNotActions() {
        assertThat(PassageRelevance.isConnectorAction("What does the release plan say about the new repo?")).isFalse();
        assertThat(PassageRelevance.isConnectorAction("Create a summary of the onboarding document")).isFalse();
    }

    // @find: test a passage found by meaning counts without shared words, passage relevance
    @Test
    void aPassageFoundByMeaningCountsWithoutSharedWords() {
        KnowledgeClient.Passage close = new KnowledgeClient.Passage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Travel rules.txt", null, null, null,
                "Business class is allowed only when the flight lasts longer than eight hours.", 1.0, false, 0.267);
        KnowledgeClient.Passage keywordOnly = new KnowledgeClient.Passage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Travel rules.txt", null, null, null,
                "Business class is allowed only when the flight lasts longer than eight hours.", 1.0, false, null);

        assertThat(PassageRelevance.relevant("Can I sit in the premium cabin on a long trip?", List.of(close)))
                .containsExactly(close);
        assertThat(PassageRelevance.relevant("Can I sit in the premium cabin on a long trip?", List.of(keywordOnly)))
                .isEmpty();
        // An action in a connected service is not grounded by meaning alone.
        assertThat(PassageRelevance.relevant("create a new repo named premium cabin", List.of(close))).isEmpty();
    }
}
