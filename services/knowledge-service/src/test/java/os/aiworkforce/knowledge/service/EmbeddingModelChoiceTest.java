// @find: tests for choose embedding model, change embedding model, reindex progress, model that cannot be used changes nothing, embedding settings, knowledge base
// @what: Checks choosing an embedding model: an unusable one changes nothing, a usable one rebuilds the index.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.error.ApiException;

/**
 * Choosing the embedding model: a model that cannot be used changes nothing, a model that can
 * moves every source to its own collection, embeds the stored passages again and removes the old
 * model's vectors, and collections are named per model so two models never share one.
 */
class EmbeddingModelChoiceTest {

    private final UUID org = UUID.randomUUID();
    private final EmbeddingSettings settings = mock(EmbeddingSettings.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final IngestionService ingestion = mock(IngestionService.class);
    private final Sources sources = mock(Sources.class);
    private final QdrantClient vectors = mock(QdrantClient.class);
    private final EmbeddingModelChange change = new EmbeddingModelChange(settings, embeddings, ingestion, sources, vectors);

    @Test
    @DisplayName("collections are named per model, so two models of one width never share a collection")
    void collectionPerModel() {
        String a = QdrantClient.collectionFor("aiwos", org, "nvidia", "nvidia/nemotron-3-embed-1b", 2048);
        String b = QdrantClient.collectionFor("aiwos", org, "nvidia", "nvidia/llama-3.2-nv-embedqa-1b-v1", 2048);
        assertThat(a).isNotEqualTo(b).matches("[a-z0-9_]+").endsWith("_d2048");
        String long1 = QdrantClient.collectionFor("aiwos", org, "openrouter", "vendor/" + "x".repeat(90) + "-one", 1024);
        String long2 = QdrantClient.collectionFor("aiwos", org, "openrouter", "vendor/" + "x".repeat(90) + "-two", 1024);
        assertThat(long1).isNotEqualTo(long2);
    }

    @Test
    @DisplayName("a measured model has its own floor; an unmeasured one keeps the default")
    void floors() {
        assertThat(EmbeddingSettings.meaningFloor("nvidia/nemotron-3-embed-1b")).isEqualTo(0.19);
        assertThat(EmbeddingSettings.meaningFloor("someone/else")).isEqualTo(EmbeddingSettings.DEFAULT_MEANING_FLOOR);
    }

    @Test
    @DisplayName("a model the provider refuses is refused with its reason, and nothing is saved or re-indexed")
    void refusedModelChangesNothing() {
        when(embeddings.probeDimension(org, "nvidia", "nvidia/embed-qa-4"))
                .thenThrow(new EmbeddingService.EmbeddingRefused(
                        "nvidia", "nvidia/embed-qa-4", "NVIDIA NIM does not serve nvidia/embed-qa-4 for this workspace's key."));

        ApiException refused =
                catchThrowableOfType(() -> change.change(org, "nvidia", "nvidia/embed-qa-4", "owner"), ApiException.class);

        assertThat(refused.getMessage()).isEqualTo("NVIDIA NIM does not serve nvidia/embed-qa-4 for this workspace's key.");
        verify(settings, never()).save(any(), any(), any());
        verify(ingestion, never()).switchEmbedding(any(), any(), any());
    }

    @Test
    @DisplayName("a working model is measured, saved, and every source re-embedded into its collection, old vectors removed")
    void workingModelReindexes() {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setOrgId(org);
        source.setName("Policies");
        source.setChunkCount(4);
        when(sources.findByOrgIdOrderByName(org)).thenReturn(List.of(source));
        when(embeddings.probeDimension(org, "nvidia", "nvidia/nemotron-3-embed-1b")).thenReturn(2048);
        when(settings.current(org)).thenReturn(new EmbeddingSettings.Choice("nvidia", "nvidia/nemotron-3-embed-1b", 2048));
        when(settings.read(eq(org), anyString(), eq(EmbeddingModelChange.Progress.class))).thenReturn(Optional.empty());
        when(ingestion.switchEmbedding(eq(org), eq(source.getId()), any()))
                .thenReturn(new IngestionService.PreviousEmbedding("aiwos_old_d768", true));
        when(ingestion.reindex(eq(org), eq(source.getId()), any()))
                .thenReturn(new IngestionService.ReindexResult(1, 4, true, null, 0));

        EmbeddingModelChange.Status started = change.change(org, "nvidia", "nvidia/nemotron-3-embed-1b", "owner");
        assertThat(started.searchMode()).isEqualTo("keyword+meaning");

        verify(settings).save(org, new EmbeddingSettings.Choice("nvidia", "nvidia/nemotron-3-embed-1b", 2048), "owner");
        waitUntil(() -> !change.progress(org).running());
        verify(ingestion).reindex(eq(org), eq(source.getId()), any());
        verify(vectors).deleteBySource("aiwos_old_d768", source.getId());
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("re-indexing did not finish within 5 s");
            }
            Thread.onSpinWait();
        }
    }
}
