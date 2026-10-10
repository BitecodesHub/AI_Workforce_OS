// @find: embeddings, embedding purpose, query vs passage embedding, nv-embedqa, E5, knowledge base embeddings, search by meaning, EmbeddingPurpose
// @what: Says whether text is embedded as a question or as a passage for models trained to tell them apart.
// @flow: Used by ChatProvider.embed and the knowledge-service embedding call.
package os.aiworkforce.llm.model;

import java.util.Locale;

/**
 * What a text is embedded for.
 *
 * <p>Retrieval models trained for question answering (NVIDIA's {@code nv-embedqa} family, E5, and
 * others) embed a search question and a stored passage differently, and NVIDIA's endpoint refuses a
 * call that does not say which. Embedding the question as a passage still returns a vector, but one
 * measurably further from the passages that answer it, so the purpose is carried to the adapter.
 */
public enum EmbeddingPurpose {
    /** A search question, embedded at query time. */
    QUERY,
    /** A stored passage, embedded at ingestion time. */
    PASSAGE;

    /** Reads the wire form, {@code query} or {@code passage}; anything else, including null, is a passage. */
    public static EmbeddingPurpose fromWire(String value) {
        return value != null && value.strip().toLowerCase(Locale.ROOT).equals("query") ? QUERY : PASSAGE;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
