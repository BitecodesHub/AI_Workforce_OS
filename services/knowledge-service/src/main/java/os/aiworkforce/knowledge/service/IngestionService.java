package os.aiworkforce.knowledge.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.knowledge.domain.Chunk;
import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.Documents;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Takes a document from bytes to something an agent can cite.
 *
 * <p>The pipeline is extract, hash, chunk, embed, store. Four decisions in it are worth stating,
 * because each is the difference between a corpus that works and one that quietly does not:
 *
 * <ul>
 *   <li><b>A document that cannot be indexed is recorded, not dropped.</b> It keeps its reason,
 *       so the interface can say "this is a scan, run OCR over it" rather than showing a count
 *       that is mysteriously lower than the number of files.
 *   <li><b>An unchanged document is skipped by hash.</b> A nightly crawl of ten thousand files
 *       becomes a crawl of the dozen that moved, which on a metered embedding provider is the
 *       difference between a trivial bill and a serious one.
 *   <li><b>Text is stored before vectors are.</b> The lexical half of retrieval works from
 *       Postgres alone, so a vector store that is down degrades search rather than stopping
 *       ingestion - and the source says it is degraded instead of pretending otherwise.
 *   <li><b>Re-ingesting a changed document replaces its chunks.</b> Appending would leave the old
 *       passages citable, and an agent quoting a superseded policy is worse than one that cannot
 *       find it.
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Sources sources;
    private final Documents documents;
    private final Chunks chunks;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingService embeddings;
    private final QdrantClient vectors;

    public IngestionService(
            Sources sources,
            Documents documents,
            Chunks chunks,
            TextExtractor extractor,
            Chunker chunker,
            EmbeddingService embeddings,
            QdrantClient vectors) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    /**
     * @param documentId the stored document
     * @param status what happened: indexed, skipped, unchanged or failed
     * @param chunkCount passages written
     * @param detail the reason, when there is one worth showing
     * @param searchable whether it can be found right now, which vectors being down can change
     */
    public record IngestResult(UUID documentId, String status, int chunkCount, String detail, boolean searchable) {}

    /** Creates a source to upload into, so a workspace has somewhere to put its first file. */
    @Transactional
    public Source createSource(UUID orgId, String name, String kind) {
        if (sources.findByOrgIdOrderByName(orgId).stream()
                .anyMatch(existing -> existing.getName().equalsIgnoreCase(name))) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.ALREADY_EXISTS,
                    "A source with that name already exists in this workspace.");
        }

        Source source = new Source();
        source.setId(UuidV7.generate());
        source.setOrgId(orgId);
        source.setName(name);
        source.setKind(kind == null ? "upload" : kind);
        source.setStatus("idle");
        // The collection name carries the embedding width, so changing model cannot write
        // vectors of one size into an index built for another.
        source.setCollection(QdrantClient.collectionFor("aiwos", orgId, source.getEmbeddingDimension()));
        return sources.save(source);
    }

    /** Ingests one uploaded file. */
    @Transactional
    public IngestResult ingest(UUID orgId, UUID sourceId, String filename, byte[] content) {
        Source source =
                sources.findByIdAndOrgId(sourceId, orgId).orElseThrow(() -> ApiException.notFound("source", sourceId));

        TextExtractor.Extraction extraction = extractor.extract(content, filename);
        String externalId = filename;

        Document document = documents
                .findBySourceIdAndExternalId(sourceId, externalId)
                .orElseGet(() -> {
                    Document fresh = new Document();
                    fresh.setId(UuidV7.generate());
                    fresh.setOrgId(orgId);
                    fresh.setSourceId(sourceId);
                    fresh.setExternalId(externalId);
                    return fresh;
                });

        // Unchanged bytes mean unchanged text: nothing to re-chunk and nothing to re-embed.
        if (extraction.contentHash().equals(document.getContentHash()) && document.isIndexed()) {
            return new IngestResult(
                    document.getId(),
                    "unchanged",
                    document.getChunkCount(),
                    "The file has not changed since it was last indexed.",
                    true);
        }

        document.setTitle(filename);
        document.setMediaType(extraction.mediaType());
        document.setContentHash(extraction.contentHash());
        document.setByteSize(content.length);
        document.setPageCount(extraction.pageCount());

        if (!extraction.isIndexable()) {
            // Recorded with its reason rather than dropped. A count that silently omits the
            // unreadable files is a count nobody can act on.
            document.setStatus("skipped");
            document.setSkipReason(extraction.skipReason());
            document.setChunkCount(0);
            documents.save(document);
            chunks.deleteByDocumentId(document.getId());
            refreshCounts(source);
            log.info("Skipped {}: {}", filename, extraction.skipReason());
            return new IngestResult(document.getId(), "skipped", 0, extraction.skipReason(), false);
        }

        documents.save(document);

        // Replaced, never appended: the old passages must stop being citable the moment the
        // document they came from changes.
        chunks.deleteByDocumentId(document.getId());

        List<Chunker.Chunk> pieces = chunker.chunk(extraction.text(), source.getChunkSize(), source.getChunkOverlap());
        if (pieces.isEmpty()) {
            document.setStatus("skipped");
            document.setSkipReason("The document produced no passages long enough to index.");
            documents.save(document);
            return new IngestResult(document.getId(), "skipped", 0, document.getSkipReason(), false);
        }

        List<Chunk> stored = new ArrayList<>(pieces.size());
        for (Chunker.Chunk piece : pieces) {
            Chunk chunk = new Chunk();
            chunk.setOrgId(orgId);
            chunk.setDocumentId(document.getId());
            chunk.setSourceId(sourceId);
            chunk.setPosition(piece.position());
            chunk.setContent(piece.content());
            chunk.setTokenEstimate(piece.tokenEstimate());
            chunk.setHeading(piece.heading());
            chunk.setCharStart(piece.charStart());
            chunk.setCharEnd(piece.charEnd());
            chunk.setPageNumber(estimatePage(piece, extraction));
            stored.add(chunk);
        }
        chunks.saveAll(stored);

        document.setStatus("indexed");
        document.setSkipReason(null);
        document.setChunkCount(stored.size());
        document.setIndexedAt(Instant.now());
        documents.save(document);

        boolean vectorised = indexVectors(source, document, stored);
        refreshCounts(source);

        String detail = vectorised
                ? null
                : "Indexed for keyword search. The vector store was unavailable, so meaning-based "
                        + "search will be less accurate until it is reindexed.";
        log.info("Indexed {} into {} passage(s), vectors={}", filename, stored.size(), vectorised);
        return new IngestResult(document.getId(), "indexed", stored.size(), detail, true);
    }

    /**
     * Writes the vectors, and reports whether it managed to.
     *
     * <p>A failure here does not fail the ingestion. The passages are already in Postgres, so
     * keyword search works and the document is genuinely findable; what is lost is the
     * meaning-based half. Failing the whole upload would throw away work that mostly succeeded,
     * and would leave the person with nothing rather than with something imperfect they were
     * told about.
     */
    private boolean indexVectors(Source source, Document document, List<Chunk> stored) {
        try {
            vectors.ensureCollection(source.getCollection(), source.getEmbeddingDimension());

            List<float[]> embedded = embeddings.embed(
                    source.getOrgId(),
                    source.getEmbeddingProvider(),
                    source.getEmbeddingModel(),
                    stored.stream().map(Chunk::getContent).toList());

            List<QdrantClient.Point> points = new ArrayList<>(stored.size());
            for (int i = 0; i < stored.size(); i++) {
                Chunk chunk = stored.get(i);
                points.add(new QdrantClient.Point(
                        chunk.getId(),
                        embedded.get(i),
                        Map.of(
                                // The workspace is on every point so the store filters by tenant
                                // rather than the application filtering results afterwards.
                                "orgId", source.getOrgId().toString(),
                                "sourceId", source.getId().toString(),
                                "documentId", document.getId().toString(),
                                "position", chunk.getPosition())));
            }
            vectors.upsert(source.getCollection(), points);

            source.setStatus("ready");
            source.setLastError(null);
            return true;
        } catch (RuntimeException e) {
            source.setStatus("ready");
            source.setLastError("Vector indexing is unavailable: " + e.getMessage());
            log.warn("Vector indexing failed for {}; keyword search still works", document.getTitle());
            return false;
        }
    }

    /**
     * Places a passage on a page, for the citation.
     *
     * <p>An estimate from the character offset, because the extractor flattens a PDF into one
     * string and recovering the exact page would mean re-parsing per chunk. A citation that says
     * "about page 4" and is occasionally one out is far more useful than one with no page at all.
     */
    private static Integer estimatePage(Chunker.Chunk piece, TextExtractor.Extraction extraction) {
        if (extraction.pageCount() == null
                || extraction.pageCount() < 1
                || extraction.text().isEmpty()) {
            return null;
        }
        double position = (double) piece.charStart() / extraction.text().length();
        return Math.max(1, Math.min(extraction.pageCount(), (int) Math.ceil(position * extraction.pageCount())));
    }

    public record ReindexResult(int documentCount, int chunkCount, boolean vectorised, String detail) {}

    /**
     * Re-writes vectors for every indexed document in a source, from its already-stored chunks.
     *
     * <p>The one real reason to reindex is on this page already: the vector store was down
     * during ingestion, so a document sits at "indexed for keyword search" with its meaning-based
     * half missing. The text survived that failure - it is in Postgres - so recovering does not
     * need the original file again, only another attempt at embedding and upserting what is
     * already chunked.
     */
    @Transactional
    public ReindexResult reindex(UUID orgId, UUID sourceId) {
        Source source =
                sources.findByIdAndOrgId(sourceId, orgId).orElseThrow(() -> ApiException.notFound("source", sourceId));

        List<Document> indexed = documents.findBySourceIdOrderByTitle(sourceId).stream()
                .filter(Document::isIndexed)
                .toList();

        int totalChunks = 0;
        boolean anyVectorised = false;
        for (Document document : indexed) {
            List<Chunk> stored = chunks.findByDocumentIdOrderByPosition(document.getId());
            if (stored.isEmpty()) {
                continue;
            }
            totalChunks += stored.size();
            anyVectorised |= indexVectors(source, document, stored);
        }
        refreshCounts(source);

        String detail = anyVectorised || indexed.isEmpty()
                ? null
                : "The vector store is still unavailable. Keyword search is unaffected.";
        log.info("Reindexed {} document(s), {} passage(s) for source {}", indexed.size(), totalChunks, sourceId);
        return new ReindexResult(indexed.size(), totalChunks, anyVectorised, detail);
    }

    private void refreshCounts(Source source) {
        List<Document> all = documents.findBySourceIdOrderByTitle(source.getId());
        source.setDocumentCount((int) all.stream().filter(Document::isIndexed).count());
        source.setChunkCount(all.stream().mapToInt(Document::getChunkCount).sum());
        source.setLastIngestedAt(Instant.now());
        if (!"ready".equals(source.getStatus())) {
            source.setStatus("ready");
        }
        sources.save(source);
    }

    static String actor() {
        return RequestContext.actor().map(a -> a.id()).orElse("system");
    }
}
