package os.aiworkforce.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Finds the passages that support an answer.
 *
 * <p>Retrieval here is hybrid, and the reason is worth stating because dense-only search is the
 * common default. An embedding search understands meaning but is poor at exact tokens: ask about
 * invoice {@code INV-4417} or a colleague's surname and the nearest vectors are documents about
 * invoices in general. A lexical search has the opposite problem. Running both and fusing the
 * rankings gets the specific match and the conceptual one.
 *
 * <p>The fusion is reciprocal rank rather than a weighted score sum, because the two searches
 * produce scores on scales that cannot be compared - a cosine similarity of 0.82 and a BM25 rank
 * of 3.1 mean nothing to each other. Ranks are comparable; raw scores are not.
 *
 * <p>Every passage returned carries its document, page and offset. An answer the platform cannot
 * attribute is one nobody should act on, so a retrieval that finds nothing returns nothing rather
 * than the closest thing it could find.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * The constant in reciprocal rank fusion.
     *
     * <p>60 is the value from the original paper and behaves well here: it keeps a result ranked
     * first in one search from completely dominating one ranked second in both.
     */
    private static final int RRF_K = 60;

    /** Below this similarity a dense hit is noise, and including it invites a fabricated answer. */
    private static final double MIN_SCORE = 0.25;

    /**
     * The equivalent floor for keyword search.
     *
     * <p>Measured against this corpus: passages that genuinely answer a question rank between
     * 0.037 and 0.056, an incidental single-term match ranks 0.015. Without a floor, OR-combined
     * terms make every query match something, and "no document supports an answer" - the honest
     * response, and the one that stops an agent inventing one - becomes impossible to reach.
     */
    private static final double MIN_LEXICAL_RANK = 0.03;

    private final QdrantClient vectors;
    private final EmbeddingService embeddings;
    private final Chunks chunks;
    private final Sources sources;

    public RetrievalService(QdrantClient vectors, EmbeddingService embeddings, Chunks chunks, Sources sources) {
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.sources = sources;
    }

    /**
     * One passage, with everything needed to cite it.
     *
     * @param chunkId the passage
     * @param documentId the document it came from
     * @param documentTitle what to show in the citation
     * @param uri where to open the original, when there is somewhere to open
     * @param pageNumber the page, for a paginated document
     * @param heading the section it sat under, which gives the passage its context
     * @param content the text itself, so a person can check the claim against it
     * @param score fused rank score, for ordering and as a confidence signal
     */
    public record Passage(
            UUID chunkId,
            UUID documentId,
            String documentTitle,
            String uri,
            Integer pageNumber,
            String heading,
            String content,
            double score) {}

    @Transactional(readOnly = true)
    public List<Passage> retrieve(UUID orgId, String query, int limit, List<UUID> sourceFilter) {
        if (query == null || query.isBlank()) {
            throw ApiException.validation("query", "must not be empty");
        }
        int capped = Math.min(Math.max(limit, 1), 20);

        List<UUID> dense = denseSearch(orgId, query, capped * 3);
        List<UUID> lexical = lexicalSearch(orgId, query, capped * 3);

        if (dense.isEmpty() && lexical.isEmpty()) {
            // Returning nothing is the honest answer. Returning the least-bad match is how an
            // agent ends up citing an unrelated document with total confidence.
            log.debug("No passage matched the query in workspace {}", orgId);
            return List.of();
        }

        List<UUID> fused = fuse(dense, lexical, capped);
        return hydrate(orgId, fused, sourceFilter);
    }

    private List<UUID> denseSearch(UUID orgId, String query, int limit) {
        try {
            var source = sources.findFirstByOrgIdAndStatus(orgId, "ready");
            if (source.isEmpty()) {
                return List.of();
            }
            float[] vector = embeddings.embedOne(orgId, query);
            return vectors.search(source.get().getCollection(), orgId, vector, limit, MIN_SCORE).stream()
                    .map(QdrantClient.Hit::chunkId)
                    .toList();
        } catch (RuntimeException e) {
            /*
             * Caught broadly, and deliberately so. The degradation was written to catch
             * ApiException only, which meant a WebClient failure reaching the embedding service
             * propagated and failed the whole search - the opposite of the intent. Anything that
             * goes wrong in the dense half should cost accuracy, not the answer.
             */
            log.warn("Dense retrieval unavailable, falling back to keyword search: {}", e.toString());
            return List.of();
        }
    }

    private List<UUID> lexicalSearch(UUID orgId, String query, int limit) {
        return chunks.searchLexical(
                orgId, query, MIN_LEXICAL_RANK, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * Reciprocal rank fusion.
     *
     * <p>Each list contributes {@code 1 / (k + rank)} to a passage's score, so a document found by
     * both searches outranks one found emphatically by only one. This is what makes the hybrid
     * better than either half rather than merely different.
     */
    private List<UUID> fuse(List<UUID> dense, List<UUID> lexical, int limit) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (int rank = 0; rank < dense.size(); rank++) {
            scores.merge(dense.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < lexical.size(); rank++) {
            scores.merge(lexical.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Loads the passages and attaches their provenance.
     *
     * <p>Tombstoned documents are excluded here as well as at the vector store. A document deleted
     * at the source stops being citable immediately, before the vector purge has caught up -
     * otherwise a person would be shown a passage from a file that no longer exists.
     */
    private List<Passage> hydrate(UUID orgId, List<UUID> chunkIds, List<UUID> sourceFilter) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        var rows = chunks.findCitable(orgId, chunkIds);
        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            order.put(chunkIds.get(i), i);
        }

        List<Passage> passages = new ArrayList<>();
        for (var row : rows) {
            if (sourceFilter != null && !sourceFilter.isEmpty() && !sourceFilter.contains(row.getSourceId())) {
                continue;
            }
            passages.add(new Passage(
                    row.getChunkId(),
                    row.getDocumentId(),
                    row.getDocumentTitle(),
                    row.getUri(),
                    row.getPageNumber(),
                    row.getHeading(),
                    row.getContent(),
                    1.0 - (order.getOrDefault(row.getChunkId(), 0) / (double) Math.max(1, chunkIds.size()))));
        }
        passages.sort((a, b) -> Double.compare(b.score(), a.score()));
        return passages;
    }

    /**
     * Raised when an agent asked a question the corpus cannot support.
     *
     * <p>Surfaced to the caller as a distinct code so the interface can say "no document supports
     * an answer" rather than showing an empty result that looks like a failure.
     */
    static ApiException noEvidence() {
        return new ApiException(ErrorCode.NO_EVIDENCE_FOUND);
    }
}
