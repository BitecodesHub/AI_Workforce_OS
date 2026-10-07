package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.CitableChunk;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * What a search does when the meaning-based half is slow, down, or pointless, and which sources it
 * is allowed to read. The vector store, the embedding call and both repositories are stubs, so the
 * timings here are the service's own budgets and nothing else.
 */
class RetrievalServiceTest {

    private final UUID org = UUID.randomUUID();
    private final QdrantClient vectors = mock(QdrantClient.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
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
        retrieval = new RetrievalService(vectors, embeddings, chunks, sources);
        // The hydrate answers with every stored passage that was asked for, as Postgres would.
        when(chunks.findCitable(eq(org), anyList(), anyString())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(1);
            return stored.stream().filter(row -> ids.contains(row.getChunkId())).toList();
        });
    }

    @AfterEach
    void tearDown() {
        retrieval.shutdown();
    }

    private Source source(String name, String provider, boolean restricted) {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setOrgId(org);
        source.setName(name);
        source.setKind("upload");
        source.setEmbeddingProvider(provider);
        source.setEmbeddingModel(provider + "-model");
        source.setCollection(QdrantClient.collectionFor("aiwos", org, 768));
        source.setChunkCount(3);
        source.setRestricted(restricted);
        return source;
    }

    private void visible(boolean includeRestricted, Source... visible) {
        when(sources.findVisible(org, includeRestricted)).thenReturn(List.of(visible));
    }

    /** A passage in a source that the keyword search finds. */
    private UUID keywordFinds(Source source, String title) {
        UUID chunkId = UUID.randomUUID();
        stored.add(new Row(chunkId, UUID.randomUUID(), source.getId(), title, null, 1, "Leave", "Annual leave is 25 days."));
        List<UUID> found = stored.stream().map(CitableChunk::getChunkId).toList();
        when(chunks.searchLexical(eq(org), anyString(), anyString(), anyDouble(), anyDouble(), any()))
                .thenReturn(found);
        return chunkId;
    }

    private String allowedSentToKeywordSearch() {
        ArgumentCaptor<String> allowed = ArgumentCaptor.forClass(String.class);
        verify(chunks).searchLexical(eq(org), anyString(), allowed.capture(), anyDouble(), anyDouble(), any());
        return allowed.getValue();
    }

    private static String array(UUID... ids) {
        return Chunks.uuidArray(List.of(ids));
    }

    @Test
    @DisplayName("a sandbox source is searched by keyword only: neither the embedding call nor the vector store is asked")
    void sandboxNeverCallsTheVectorStore() {
        Source policies = source("Policies", "sandbox", false);
        visible(false, policies);
        UUID passage = keywordFinds(policies, "Leave policy.pdf");

        RetrievalService.Retrieval result = retrieval.retrieve(org, "annual leave", 8, null, false);

        assertThat(result.passages()).extracting(RetrievalService.Passage::chunkId).containsExactly(passage);
        assertThat(result.passages().get(0).sourceId()).isEqualTo(policies.getId());
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(embeddings, vectors);
    }

    @Test
    @DisplayName("with the embedding call hanging, keyword results come back in under 4 s, marked degraded")
    void slowEmbeddingDegrades() {
        Source policies = source("Policies", "gemini", false);
        visible(false, policies);
        UUID passage = keywordFinds(policies, "Leave policy.pdf");
        when(embeddings.embedQuery(any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Thread.sleep(Duration.ofSeconds(20));
            return new float[] {0.1f};
        });

        long started = System.nanoTime();
        RetrievalService.Retrieval result = retrieval.retrieve(org, "annual leave", 8, null, false);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(4));
        assertThat(result.degraded()).isTrue();
        assertThat(result.passages()).extracting(RetrievalService.Passage::chunkId).containsExactly(passage);
    }

    @Test
    @DisplayName("with the vector store hanging, keyword results come back in under 4 s, marked degraded")
    void slowVectorStoreDegrades() {
        Source policies = source("Policies", "gemini", false);
        visible(false, policies);
        UUID passage = keywordFinds(policies, "Leave policy.pdf");
        when(embeddings.embedQuery(any(), anyString(), anyString(), anyString())).thenReturn(new float[] {0.1f});
        when(vectors.search(anyString(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            Thread.sleep(Duration.ofSeconds(20));
            return List.of();
        });

        long started = System.nanoTime();
        RetrievalService.Retrieval result = retrieval.retrieve(org, "annual leave", 8, null, false);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(4));
        assertThat(result.degraded()).isTrue();
        assertThat(result.passages()).extracting(RetrievalService.Passage::chunkId).containsExactly(passage);
    }

    @Test
    @DisplayName("a vector store that refuses costs the meaning-based half, not the answer")
    void failingVectorStoreDegrades() {
        Source policies = source("Policies", "gemini", false);
        visible(false, policies);
        UUID passage = keywordFinds(policies, "Leave policy.pdf");
        when(embeddings.embedQuery(any(), anyString(), anyString(), anyString())).thenReturn(new float[] {0.1f});
        when(vectors.search(anyString(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The knowledge base is unavailable."));

        RetrievalService.Retrieval result = retrieval.retrieve(org, "annual leave", 8, null, false);

        assertThat(result.degraded()).isTrue();
        assertThat(result.passages()).extracting(RetrievalService.Passage::chunkId).containsExactly(passage);
    }

    @Test
    @DisplayName("a healthy meaning-based half is fused in, embedded with the source's own model, filtered to its sources")
    void healthyDenseIsFused() {
        Source policies = source("Policies", "gemini", false);
        visible(false, policies);
        UUID keyword = keywordFinds(policies, "Leave policy.pdf");
        UUID meaning = UUID.randomUUID();
        stored.add(new Row(meaning, UUID.randomUUID(), policies.getId(), "Holidays.pdf", null, 2, null, "Time off."));
        when(embeddings.embedQuery(org, "gemini", "gemini-model", "annual leave")).thenReturn(new float[] {0.1f});
        when(vectors.search(eq(policies.getCollection()), eq(org), any(), any(), anyInt(), any()))
                .thenReturn(List.of(new QdrantClient.Hit(meaning, 0.8, java.util.Map.of())));

        RetrievalService.Retrieval result = retrieval.retrieve(org, "annual leave", 8, null, false);

        assertThat(result.degraded()).isFalse();
        assertThat(result.passages()).extracting(RetrievalService.Passage::chunkId).containsExactlyInAnyOrder(keyword, meaning);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> searched = ArgumentCaptor.forClass(Collection.class);
        verify(vectors).search(eq(policies.getCollection()), eq(org), searched.capture(), any(), anyInt(), any());
        assertThat(searched.getValue()).containsExactly(policies.getId());
    }

    @Test
    @DisplayName("only the sandbox sources are left out of the meaning-based half when sources differ")
    void denseGroupsSkipSandboxAndEmptySources() {
        Source sandbox = source("Old", "sandbox", false);
        Source gemini = source("Gemini", "gemini", false);
        Source empty = source("Empty", "gemini", false);
        empty.setChunkCount(0);

        List<RetrievalService.DenseGroup> groups = RetrievalService.denseGroups(List.of(sandbox, gemini, empty));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).sourceIds()).containsExactly(gemini.getId());
        assertThat(groups.get(0).provider()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("requested sources are narrowed to the ones the caller may read, inside the searches")
    void requestedSourcesAreIntersected() {
        Source policies = source("Policies", "sandbox", false);
        Source travel = source("Travel", "sandbox", false);
        UUID restricted = UUID.randomUUID();
        // The repository leaves restricted sources out for someone who may not see them.
        visible(false, policies, travel);
        keywordFinds(travel, "Travel.pdf");

        retrieval.retrieve(org, "annual leave", 8, List.of(travel.getId(), restricted), false);

        assertThat(allowedSentToKeywordSearch()).isEqualTo(array(travel.getId()));
        verify(chunks).findCitable(eq(org), anyList(), eq(array(travel.getId())));
    }

    @Test
    @DisplayName("asking only for sources the caller may not read searches nothing and finds nothing")
    void nothingAllowedSearchesNothing() {
        visible(false, source("Policies", "sandbox", false));

        RetrievalService.Retrieval result = retrieval.retrieve(org, "salary bands", 8, List.of(UUID.randomUUID()), false);

        assertThat(result.passages()).isEmpty();
        assertThat(result.degraded()).isFalse();
        verify(chunks, never()).searchLexical(any(), anyString(), anyString(), anyDouble(), anyDouble(), any());
        verifyNoInteractions(vectors, embeddings);
    }

    @Test
    @DisplayName("someone who manages knowledge searches restricted sources too")
    void managersSearchRestricted() {
        Source policies = source("Policies", "sandbox", false);
        Source hr = source("HR", "sandbox", true);
        visible(true, policies, hr);
        keywordFinds(hr, "Salary bands.pdf");

        retrieval.retrieve(org, "salary bands", 8, null, true);

        assertThat(allowedSentToKeywordSearch()).isEqualTo(array(policies.getId(), hr.getId()));
    }

    @Test
    @DisplayName("a limit above 20 is held to 20")
    void limitIsCapped() {
        Source policies = source("Policies", "sandbox", false);
        visible(false, policies);
        keywordFinds(policies, "Leave policy.pdf");

        retrieval.retrieve(org, "annual leave", 500, null, false);

        ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(chunks).searchLexical(eq(org), anyString(), anyString(), anyDouble(), anyDouble(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(RetrievalService.MAX_LIMIT * 3);
    }
}
