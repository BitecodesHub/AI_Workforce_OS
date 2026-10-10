// @find: tests for ingestion vectors, sandbox embeddings write no vectors, keyword-only sources, writesVectors, knowledge base ingestion
// @what: Checks which sources get vectors written during ingestion.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.knowledge.domain.Source;

class IngestionVectorsTest {

    @Test
    @DisplayName("only a source on a real embedding model has its passages written to the vector store")
    void sandboxSourcesWriteNoVectors() {
        Source sandbox = new Source();
        sandbox.setEmbeddingProvider("sandbox");
        Source real = new Source();
        real.setEmbeddingProvider("openai");

        // Search never reads a sandbox source's vectors, so writing them only invited a false
        // "search by meaning failed" whenever the vector store was down.
        assertThat(IngestionService.writesVectors(sandbox)).isFalse();
        assertThat(IngestionService.writesVectors(real)).isTrue();
    }
}
