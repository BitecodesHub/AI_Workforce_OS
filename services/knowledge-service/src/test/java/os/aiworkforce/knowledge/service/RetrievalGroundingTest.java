// @find: tests for grounding, grounded answers, drop irrelevant passages, no evidence, citations, retrieval, knowledge base search
// @what: Checks only passages that really match the question are returned as grounded evidence.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.CitableChunk;
import os.aiworkforce.knowledge.repository.Sources;

/**
 * "Grounded" is a claim that a document supports an answer. A passage found by one incidental
 * word does not support one, so a keyword hit has to share more than a single distinctive term
 * with the question to count, and the passage says whether its source is restricted so a caller
 * can keep its text out of anywhere others read.
 */
class RetrievalGroundingTest {

    private final UUID org = UUID.randomUUID();
    private final Chunks chunks = mock(Chunks.class);
    private final Sources sources = mock(Sources.class);
    private final List<CitableChunk> stored = new ArrayList<>();
    private RetrievalService retrieval;

    private record Row(
            UUID getChunkId,
            UUID getDocumentId,
            UUID getSourceId,
            String getDocumentTitle,
            String getUri,
            Integer getPageNumber,
            String getHeading,
            String getContent)
            implements CitableChunk {}

    @BeforeEach
    void setUp() {
        retrieval = new RetrievalService(mock(QdrantClient.class), mock(EmbeddingService.class), chunks, sources);
        when(chunks.findCitable(eq(org), anyList(), anyString())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(1);
            return stored.stream().filter(row -> ids.contains(row.getChunkId())).toList();
        });
    }

    @AfterEach
    void tearDown() {
        retrieval.shutdown();
    }

    private Source sandboxSource(boolean restricted) {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setOrgId(org);
        source.setName("Docs");
        source.setKind("upload");
        source.setEmbeddingProvider("sandbox");
        source.setEmbeddingModel("sandbox-model");
        source.setChunkCount(3);
        source.setRestricted(restricted);
        when(sources.findVisible(org, true)).thenReturn(List.of(source));
        return source;
    }

    private void keywordFinds(Source source, String title, String heading, String content) {
        UUID chunkId = UUID.randomUUID();
        stored.add(new Row(chunkId, UUID.randomUUID(), source.getId(), title, null, 1, heading, content));
        when(chunks.searchLexical(eq(org), anyString(), anyString(), anyDouble(), anyDouble(), any()))
                .thenReturn(stored.stream().map(CitableChunk::getChunkId).toList());
    }

    @Test
    @DisplayName("an unrelated deck that shares one word with the question is not grounded")
    void oneIncidentalWordDoesNotGround() {
        Source docs = sandboxSource(false);
        keywordFinds(docs, "Q3 board deck.pptx", "Overview", "Programme risks are tracked in the register each month.");

        RetrievalService.Retrieval result =
                retrieval.retrieve(org, "How do we onboard a new supplier and manage their risks?", 8, null, true);

        assertThat(result.passages()).isEmpty();
    }

    @Test
    @DisplayName("a passage that covers more than one distinctive term of the question is grounded")
    void severalSharedTermsGround() {
        Source docs = sandboxSource(false);
        keywordFinds(
                docs,
                "Supplier onboarding.docx",
                "Risk checks",
                "Every new supplier completes onboarding, including a risks assessment.");

        RetrievalService.Retrieval result =
                retrieval.retrieve(org, "How do we onboard a new supplier and manage their risks?", 8, null, true);

        assertThat(result.passages()).hasSize(1);
    }

    @Test
    @DisplayName("the document's title and heading count towards the terms a passage covers")
    void titleAndHeadingCount() {
        Source docs = sandboxSource(false);
        keywordFinds(docs, "Refund policy.pdf", "Timeframes", "Money goes back to the original card.");

        RetrievalService.Retrieval result = retrieval.retrieve(org, "refund timeframes for customers", 8, null, true);

        assertThat(result.passages()).hasSize(1);
    }

    @Test
    @DisplayName("a short question needs only its one distinctive term, so an identifier still finds its document")
    void shortQuestionNeedsOneTerm() {
        Source docs = sandboxSource(false);
        keywordFinds(docs, "Invoices.xlsx", "Open items", "INV-4417 was paid on the 3rd.");

        RetrievalService.Retrieval result = retrieval.retrieve(org, "INV-4417", 8, null, true);

        assertThat(result.passages()).hasSize(1);
    }

    @Test
    @DisplayName("a passage from a restricted source says so, and one from an open source does not")
    void passagesCarryTheirSourcesRestriction() {
        Source restricted = sandboxSource(true);
        keywordFinds(restricted, "Salary bands.xlsx", "Bands", "Salary bands for engineers are set each year.");

        RetrievalService.Retrieval result = retrieval.retrieve(org, "engineer salary bands", 8, null, true);

        assertThat(result.passages()).singleElement().satisfies(passage -> assertThat(passage.restricted()).isTrue());

        stored.clear();
        Source open = sandboxSource(false);
        keywordFinds(open, "Leave policy.pdf", "Leave", "Annual leave is 25 days for every employee.");

        assertThat(retrieval.retrieve(org, "annual leave days", 8, null, true).passages())
                .singleElement()
                .satisfies(passage -> assertThat(passage.restricted()).isFalse());
    }

    @Test
    @DisplayName("terms are compared by stem, so refunds, refunded and refunding are one word")
    void stemmingJoinsInflections() {
        assertThat(RetrievalService.stem("refunds")).isEqualTo(RetrievalService.stem("refunded"));
        assertThat(RetrievalService.stem("refunding")).isEqualTo(RetrievalService.stem("refund"));
        assertThat(RetrievalService.stem("policies")).isEqualTo(RetrievalService.stem("policy"));
        assertThat(RetrievalService.stem("managed")).isEqualTo(RetrievalService.stem("manage"));
    }

    @Test
    @DisplayName("words a question is built from carry no subject, and a long question needs two terms to agree")
    void distinctiveTermsSkipQuestionWords() {
        Set<String> terms = RetrievalService.distinctiveTerms("How do we onboard a new supplier and manage their risks?");

        assertThat(terms).contains("onboard", "supplier", "risk").doesNotContain("how", "the", "and");
        assertThat(RetrievalService.requiredTerms(2)).isEqualTo(1);
        assertThat(RetrievalService.requiredTerms(3)).isEqualTo(2);
    }

    @Test
    @DisplayName("a passage found by meaning is kept even when it shares few words with the question")
    void passagesFoundByMeaningAreKept() {
        UUID chunk = UUID.randomUUID();
        RetrievalService.Passage byMeaning = new RetrievalService.Passage(
                chunk, UUID.randomUUID(), UUID.randomUUID(), "Time off.pdf", null, 1, "Leave", "Staff accrue days off.", 0.9);

        List<RetrievalService.Passage> kept =
                RetrievalService.groundedPassages("What is our parental leave entitlement?", List.of(byMeaning), Set.of(chunk));

        assertThat(kept).containsExactly(byMeaning);
    }
}
