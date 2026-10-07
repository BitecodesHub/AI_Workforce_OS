package os.aiworkforce.knowledge.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Takes a document from bytes to something an agent can cite, and erases it again on request.
 *
 * <p>The pipeline is extract, hash, chunk, embed, store. Five decisions in it are worth stating,
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
 *       find it. The old vectors go too, once the new ones are written.
 *   <li><b>No transaction is held across a remote call.</b> The document and its passages are
 *       committed in one short transaction, embedded and written to the vector store with none
 *       open, and the outcome recorded in a second short one. Embedding a large file takes
 *       minutes; a connection held for all of them is one the pool cannot give to a search.
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** What the vector-store failure is reported as, for one document and for its source. */
    static final String VECTORS_UNAVAILABLE = "Indexed for keyword search. The vector store was unavailable, so "
            + "meaning-based search will be less accurate until it is reindexed.";

    static final String SOURCE_VECTOR_ERROR = "Vector indexing is unavailable: ";

    /** A transaction that lost a race on the source's counts is run again, this many times in all. */
    private static final int ATTEMPTS = 3;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final Sources sources;
    private final Documents documents;
    private final Chunks chunks;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingService embeddings;
    private final QdrantClient vectors;
    private final TransactionTemplate transactions;

    public IngestionService(
            Sources sources,
            Documents documents,
            Chunks chunks,
            TextExtractor extractor,
            Chunker chunker,
            EmbeddingService embeddings,
            QdrantClient vectors,
            PlatformTransactionManager transactionManager) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * What an upload does with a file whose name is already in the source.
     *
     * <p>A document's identity is its name, so the choice has to be the person's: the same name
     * can be a new version of the same policy or an unrelated contract from another folder.
     */
    public enum UploadMode {
        /** The new file takes the place of the old one, which stops being citable. */
        REPLACE,
        /** Both are kept: the new file is stored under the next free name, "Contract (2).pdf". */
        KEEP_BOTH;

        /** Absent means replace, which is what re-uploading an edited file has always done. */
        public static UploadMode fromWire(String mode) {
            if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("replace")) {
                return REPLACE;
            }
            if (mode.equalsIgnoreCase("keep_both")) {
                return KEEP_BOTH;
            }
            throw ApiException.validation("mode", "must be replace or keep_both");
        }
    }

    /**
     * @param documentId the stored document
     * @param status what happened: indexed, replaced, skipped, unchanged or failed
     * @param chunkCount passages written
     * @param detail everything worth saying about it, in sentences: the version it replaced, its
     *     notice, and whether meaning-based search could be written
     * @param searchable whether it can be found right now, which vectors being down can change
     * @param title the name it is stored under, which differs from the file's when both were kept
     * @param notice the document's own notice, such as only the first part of a long file being
     *     indexed; also part of {@code detail}
     * @param vectorWarning set when meaning-based search could not be written; also part of
     *     {@code detail}, and said once for several files rather than once for each
     * @param replacedIndexedAt when the version this upload took the place of was indexed, so the
     *     console can say which one in the reader's own time zone
     */
    public record IngestResult(
            UUID documentId,
            String status,
            int chunkCount,
            String detail,
            boolean searchable,
            String title,
            String notice,
            String vectorWarning,
            Instant replacedIndexedAt) {}

    /**
     * Creates a source to upload into, so a workspace has somewhere to put its first file.
     *
     * @param restricted whether only people who manage knowledge may search it. Settable here so a
     *     source meant to be private is never searchable by the workspace between its creation and
     *     a later change.
     */
    @Transactional
    public Source createSource(UUID orgId, String name, String kind, boolean restricted) {
        return createSource(orgId, null, name, kind, restricted);
    }

    /**
     * As {@link #createSource(UUID, String, String, boolean)}; with an {@code agentId} the source
     * belongs to that one agent, which alone searches it.
     */
    @Transactional
    public Source createSource(UUID orgId, UUID agentId, String name, String kind, boolean restricted) {
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
        source.setRestricted(restricted);
        source.setAgentId(agentId);
        // The collection name carries the embedding width, so changing model cannot write
        // vectors of one size into an index built for another.
        source.setCollection(QdrantClient.collectionFor("aiwos", orgId, source.getEmbeddingDimension()));
        return sources.save(source);
    }

    /**
     * Renames a source, or changes who may search it.
     *
     * <p>Restricting a source takes effect on the next search: retrieval reads the flag on every
     * request, so nothing is cached that could keep serving its passages. It cannot take back
     * passages already shown to somebody, such as those quoted in a conversation before the change.
     *
     * @param name the new name, or null to keep it
     * @param restricted the new setting, or null to keep it
     */
    public Source updateSource(UUID orgId, UUID sourceId, String name, Boolean restricted) {
        return transactionally(() -> {
            Source source = requireSource(orgId, sourceId);
            if (name != null && !name.equals(source.getName())) {
                boolean taken = sources.findByOrgIdOrderByName(orgId).stream()
                        .anyMatch(other -> !other.getId().equals(sourceId) && other.getName().equalsIgnoreCase(name));
                if (taken) {
                    throw new ApiException(
                            os.aiworkforce.platform.error.ErrorCode.ALREADY_EXISTS,
                            "A source with that name already exists in this workspace.");
                }
                source.setName(name);
            }
            if (restricted != null && restricted != source.isRestricted()) {
                source.setRestricted(restricted);
                // Who may read a source is an access decision, so it is logged with who made it.
                log.info(
                        "Source {} is now {} by {}",
                        sourceId,
                        restricted ? "restricted to people who manage knowledge" : "searchable by the whole workspace",
                        actor());
            }
            return sources.save(source);
        });
    }

    /** Ingests one uploaded file, replacing any document of the same name. */
    public IngestResult ingest(UUID orgId, UUID sourceId, String filename, byte[] content) {
        return ingest(orgId, sourceId, filename, content, UploadMode.REPLACE);
    }

    /**
     * Ingests one uploaded file.
     *
     * <p>Three steps, and only the first and last hold a database connection. A failure in the
     * vector store leaves a document findable by keyword and says so; a failure after the vectors
     * are written removes them again, so no point outlives the passage it stands for.
     */
    public IngestResult ingest(UUID orgId, UUID sourceId, String filename, byte[] content, UploadMode mode) {
        Source source = requireSource(orgId, sourceId);

        // Before any transaction opens: parsing a large file takes seconds, and a connection
        // held for them is one nobody else can use.
        TextExtractor.Extraction extraction = extractor.extract(content, filename);

        Staged staged = stageFirstOrAgain(orgId, sourceId, filename, content, extraction, mode);
        if (staged.outcome() != null) {
            // Unchanged, or not indexable. In the second case the old passages are gone, and the
            // vectors that pointed at them are retired with them.
            vectors.deletePoints(source.getCollection(), staged.replacedIds());
            return staged.outcome();
        }

        List<UUID> written = staged.stored().stream().map(Chunk::getId).toList();
        String vectorWarning = null;
        String sourceError = null;
        try {
            writeVectors(source, staged.documentId(), staged.stored());
        } catch (RuntimeException e) {
            // Not a failed upload. The passages are in Postgres, so keyword search finds the
            // document; what is missing is the meaning-based half, and the person is told so.
            vectorWarning = VECTORS_UNAVAILABLE;
            sourceError = SOURCE_VECTOR_ERROR + e.getMessage();
            log.warn("Vector indexing failed for {}; keyword search still works", filename);
        }

        Finished finished;
        try {
            String warning = vectorWarning;
            String error = sourceError;
            finished = transactionally(() -> finish(orgId, staged, extraction, warning, error));
        } catch (RuntimeException e) {
            // Nothing after the upsert may leave points behind that the database does not stand
            // behind. The passages they belong to stay, findable by keyword.
            vectors.deletePoints(source.getCollection(), written);
            throw e;
        } finally {
            // The passages these pointed at were deleted when the first step committed, so they
            // are orphans whatever happened since; until removed they take search slots.
            vectors.deletePoints(source.getCollection(), staged.replacedIds());
        }
        if (finished.superseded()) {
            // The other upload replaced these passages with its own, so the points written for
            // them stand for nothing.
            vectors.deletePoints(source.getCollection(), written);
            return finished.result();
        }
        IngestResult result = finished.result();

        log.info(
                "{} {} into {} passage(s), vectors={}",
                result.status().equals("replaced") ? "Replaced" : "Indexed",
                staged.title(),
                result.chunkCount(),
                vectorWarning == null);
        return result;
    }

    /**
     * What the first step left for the rest.
     *
     * @param outcome the final answer when there is nothing to embed, otherwise null
     * @param documentId the document being indexed
     * @param title the name it is stored under
     * @param stored its new passages, committed
     * @param replacedIds the passages it had before, deleted, whose vectors are now orphans
     * @param replacedIndexedAt when the version this replaces was indexed, or null when it
     *     replaces nothing that was searchable
     * @param renamedFrom the file's own name, when both were kept and this one was renamed
     */
    private record Staged(
            IngestResult outcome,
            UUID documentId,
            String title,
            List<Chunk> stored,
            List<UUID> replacedIds,
            Instant replacedIndexedAt,
            String renamedFrom) {

        static Staged done(IngestResult outcome, List<UUID> replacedIds) {
            return new Staged(outcome, outcome.documentId(), outcome.title(), List.of(), replacedIds, null, null);
        }
    }

    /**
     * The first step, run again when another upload of the same name got there first.
     *
     * <p>Two uploads of one name at once both looked for the document, found none, and both
     * inserted one; the second hit the unique (source, name) index and was refused with a 409 that
     * explained nothing. The first one's row is committed by then, so a second look finds it, and
     * the upload ends the way the person asked: replace takes that document as the one to replace,
     * and keep both moves on to the next free name.
     */
    private Staged stageFirstOrAgain(
            UUID orgId,
            UUID sourceId,
            String filename,
            byte[] content,
            TextExtractor.Extraction extraction,
            UploadMode mode) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionally(() -> stage(orgId, sourceId, filename, content, extraction, mode));
            } catch (DataIntegrityViolationException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                log.debug("Another upload of {} created it first; looking again", filename);
            }
        }
    }

    /**
     * What the last step decided.
     *
     * @param superseded true when an upload of the same file, started later, replaced this one's
     *     passages while they were being indexed; that upload finishes the job, and the points this
     *     one wrote are orphans
     */
    private record Finished(IngestResult result, boolean superseded) {}

    /** The first step: the document and its passages, committed before anything remote is asked. */
    private Staged stage(
            UUID orgId,
            UUID sourceId,
            String filename,
            byte[] content,
            TextExtractor.Extraction extraction,
            UploadMode mode) {
        Source source = requireSource(orgId, sourceId);

        String externalId = mode == UploadMode.KEEP_BOTH ? freeName(sourceId, filename) : filename;
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
            return Staged.done(
                    new IngestResult(
                            document.getId(),
                            "unchanged",
                            document.getChunkCount(),
                            "The file has not changed since it was last indexed.",
                            true,
                            document.getTitle(),
                            document.getNotice(),
                            null,
                            null),
                    List.of());
        }

        // Read before anything is overwritten: whether a searchable version is being replaced,
        // and which vector points belong to it.
        Instant replacedIndexedAt = document.isIndexed() ? indexedAtOf(document) : null;
        List<UUID> replacedIds = document.isNew() ? List.of() : chunks.findIdsByDocumentId(document.getId());

        document.setTitle(externalId);
        document.setMediaType(extraction.mediaType());
        document.setContentHash(extraction.contentHash());
        document.setByteSize(content.length);
        document.setPageCount(extraction.pageCount());
        // A re-upload starts clean. A tombstone left on the row would hide the new version from
        // retrieval while it reported "indexed"; an old reason would describe the old file.
        document.clearTombstone();
        document.setSkipReason(null);
        document.setNotice(null);

        if (!extraction.isIndexable()) {
            return Staged.done(skip(source, document, extraction.skipReason(), replacedIndexedAt), replacedIds);
        }

        documents.save(document);

        // Replaced, never appended: the old passages must stop being citable the moment the
        // document they came from changes.
        chunks.deleteByDocumentId(document.getId());

        List<Chunker.Chunk> pieces = chunker.chunk(extraction.text(), source.getChunkSize(), source.getChunkOverlap());
        if (pieces.isEmpty()) {
            return Staged.done(
                    skip(source, document, "The document produced no passages long enough to index.", replacedIndexedAt),
                    replacedIds);
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

        // Pending until the outcome is recorded. Keyword search finds the passages already; the
        // status says the indexing has not finished, which stays true if it never does.
        document.setStatus("pending");
        document.setChunkCount(stored.size());
        documents.save(document);

        return new Staged(
                null,
                document.getId(),
                externalId,
                stored,
                replacedIds,
                replacedIndexedAt,
                externalId.equals(filename) ? null : filename);
    }

    /**
     * Records a document that cannot be indexed, with its reason, rather than dropping it. A count
     * that silently omits the unreadable files is a count nobody can act on.
     */
    private IngestResult skip(Source source, Document document, String reason, Instant replacedIndexedAt) {
        document.setStatus("skipped");
        document.setSkipReason(reason);
        document.setChunkCount(0);
        documents.save(document);
        chunks.deleteByDocumentId(document.getId());
        refreshCounts(source, true);
        log.info("Skipped {}: {}", document.getTitle(), reason);

        String detail = replacedIndexedAt == null
                ? reason
                : sentences(
                        reason,
                        "The earlier version, indexed on " + DAY.format(replacedIndexedAt)
                                + ", is no longer searchable.");
        return new IngestResult(
                document.getId(), "skipped", 0, detail, false, document.getTitle(), null, null, replacedIndexedAt);
    }

    /** The last step: the outcome, written once the vectors have been. */
    private Finished finish(
            UUID orgId,
            Staged staged,
            TextExtractor.Extraction extraction,
            String vectorWarning,
            String sourceError) {
        Document document = documents
                .findByIdAndOrgId(staged.documentId(), orgId)
                .orElseThrow(() -> ApiException.conflict("The document was deleted while it was being indexed."));
        Set<UUID> current = new HashSet<>(chunks.findIdsByDocumentId(document.getId()));
        if (!current.containsAll(staged.stored().stream().map(Chunk::getId).toList())) {
            if (extraction.contentHash().equals(document.getContentHash())) {
                // The same bytes uploaded twice at once, as a double drop does. Nothing conflicts:
                // the later upload is indexing exactly this file, so this one stands down.
                return new Finished(
                        new IngestResult(
                                document.getId(),
                                "unchanged",
                                document.getChunkCount(),
                                "The same file was uploaded again while this one was being indexed; that upload "
                                        + "finishes indexing it.",
                                true,
                                document.getTitle(),
                                null,
                                null,
                                null),
                        true);
            }
            throw ApiException.conflict("Another upload of this file replaced it while it was being indexed.");
        }

        document.setStatus("indexed");
        document.setIndexedAt(Instant.now());
        document.setNotice(extraction.notice());
        documents.save(document);

        Source source = requireSource(orgId, document.getSourceId());
        source.setStatus("ready");
        source.setLastError(sourceError);
        refreshCounts(source, true);

        boolean replaced = staged.replacedIndexedAt() != null;
        String detail = sentences(
                replaced ? "Replaced the version indexed on " + DAY.format(staged.replacedIndexedAt()) + "." : null,
                staged.renamedFrom() == null
                        ? null
                        : "Saved as " + staged.title() + ", beside the file already called " + staged.renamedFrom()
                                + ".",
                extraction.notice(),
                vectorWarning);
        return new Finished(
                new IngestResult(
                        document.getId(),
                        replaced ? "replaced" : "indexed",
                        staged.stored().size(),
                        detail,
                        true,
                        staged.title(),
                        extraction.notice(),
                        vectorWarning,
                        staged.replacedIndexedAt()),
                false);
    }

    /**
     * Embeds a document's passages and writes their vectors, or throws.
     *
     * <p>Run with no transaction open. The caller decides what a failure means: for an upload, a
     * document findable by keyword only, which is imperfect but real, and which the person is told
     * about; failing the whole upload would throw away work that mostly succeeded.
     */
    private void writeVectors(Source source, UUID documentId, List<Chunk> stored) {
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
                            "documentId", documentId.toString(),
                            "position", chunk.getPosition())));
        }
        vectors.upsert(source.getCollection(), points);
    }

    /**
     * Places a passage on a page, for the citation.
     *
     * <p>An estimate from the character offset, because the extractor flattens a PDF into one
     * string and recovering the exact page would mean re-parsing per chunk. A citation that says
     * "about page 4" and is occasionally one out is far more useful than one with no page at all.
     * The span is the pages the kept text covers, which is fewer than the file's when a long PDF
     * was cut at the extraction limit.
     */
    private static Integer estimatePage(Chunker.Chunk piece, TextExtractor.Extraction extraction) {
        Integer pages = extraction.textPages();
        if (pages == null || pages < 1 || extraction.text().isEmpty()) {
            return null;
        }
        double position = (double) piece.charStart() / extraction.text().length();
        return Math.max(1, Math.min(pages, (int) Math.ceil(position * pages)));
    }

    /**
     * @param documentCount documents whose vectors were written again, including recovered ones
     * @param recovered documents whose indexing had stopped part way and is now finished
     */
    public record ReindexResult(int documentCount, int chunkCount, boolean vectorised, String detail, int recovered) {}

    /**
     * Re-writes vectors for every indexed document in a source, from its already-stored chunks,
     * and finishes any document whose indexing stopped part way.
     *
     * <p>The one real reason to reindex is on this page already: the vector store was down
     * during ingestion, so a document sits at "indexed for keyword search" with its meaning-based
     * half missing. The text survived that failure - it is in Postgres - so recovering does not
     * need the original file again, only another attempt at embedding and upserting what is
     * already chunked.
     *
     * <p>The second reason is a document left "pending": its passages were committed, then the
     * service stopped before recording the outcome. It is findable by keyword yet counted nowhere,
     * and reindexing used to pass over it because it was not "indexed". It is treated the same way
     * now, then marked indexed - unless another upload replaced its passages meanwhile, in which
     * case that upload records the outcome. Its notice, such as only part of a long file being kept,
     * cannot be recovered without the file.
     *
     * <p>One document at a time and with no transaction open, so a large source neither holds a
     * connection for the whole run nor loads every passage at once.
     */
    public ReindexResult reindex(UUID orgId, UUID sourceId) {
        Source source = requireSource(orgId, sourceId);

        List<Document> candidates = documents.findBySourceIdOrderByTitle(sourceId).stream()
                .filter(document -> document.isIndexed() || isUnfinished(document))
                .toList();

        int totalChunks = 0;
        int attempted = 0;
        int recovered = 0;
        boolean anyVectorised = false;
        String failure = null;
        for (Document document : candidates) {
            UUID documentId = document.getId();
            List<Chunk> stored = chunks.findByDocumentIdOrderByPosition(documentId);
            if (stored.isEmpty()) {
                continue;
            }
            attempted++;
            totalChunks += stored.size();
            List<UUID> written = stored.stream().map(Chunk::getId).toList();
            try {
                writeVectors(source, documentId, stored);
                anyVectorised = true;
                // A document replaced or erased while its vectors were being written leaves points
                // for passages that no longer exist; they are taken back out at once.
                Set<UUID> current = new HashSet<>(chunks.findIdsByDocumentId(documentId));
                vectors.deletePoints(
                        source.getCollection(),
                        written.stream().filter(id -> !current.contains(id)).toList());
            } catch (RuntimeException e) {
                failure = e.getMessage();
                log.warn("Vector indexing failed for document {}; keyword search still works", documentId);
            }
            // Finished whether or not the vectors could be written: its passages are in Postgres,
            // so keyword search finds it, exactly as for an upload made while the store was down.
            if (isUnfinished(document) && Boolean.TRUE.equals(transactionally(() -> settle(orgId, documentId, written)))) {
                recovered++;
            }
        }

        boolean recordOutcome = attempted > 0;
        String error = failure == null ? null : SOURCE_VECTOR_ERROR + failure;
        transactionally(() -> {
            Source fresh = requireSource(orgId, sourceId);
            fresh.setStatus("ready");
            if (recordOutcome) {
                fresh.setLastError(error);
            }
            refreshCounts(fresh, true);
            return fresh;
        });

        String detail = anyVectorised || candidates.isEmpty()
                ? null
                : "The vector store is still unavailable. Keyword search is unaffected.";
        log.info(
                "Reindexed {} document(s), {} passage(s), {} recovered from an unfinished indexing, for source {}",
                candidates.size(),
                totalChunks,
                recovered,
                sourceId);
        return new ReindexResult(candidates.size(), totalChunks, anyVectorised, detail, recovered);
    }

    /** A document whose passages were committed but whose indexing never recorded an outcome. */
    private static boolean isUnfinished(Document document) {
        return "pending".equals(document.getStatus()) && document.getTombstonedAt() == null;
    }

    /**
     * Marks a recovered document indexed, if it is still the one that was recovered: still
     * pending, and still holding exactly the passages whose vectors were just written.
     */
    private boolean settle(UUID orgId, UUID documentId, List<UUID> written) {
        Document document = documents.findByIdAndOrgId(documentId, orgId).orElse(null);
        if (document == null || !isUnfinished(document)) {
            return false;
        }
        if (!new HashSet<>(chunks.findIdsByDocumentId(documentId)).equals(new HashSet<>(written))) {
            return false;
        }
        document.setStatus("indexed");
        document.setIndexedAt(Instant.now());
        documents.save(document);
        return true;
    }

    /**
     * Erases one document: its passages, its record and its vectors.
     *
     * <p>Deleted outright rather than tombstoned. A person deleting a file is often honouring an
     * erasure request or retiring something uploaded by mistake, and a tombstone would keep its
     * title, hash and every passage in the database. The vectors go after the commit: if they
     * cannot, retrieval still finds nothing, because every hit is resolved against the passages.
     */
    public void deleteDocument(UUID orgId, UUID sourceId, UUID documentId) {
        Erased erased = transactionally(() -> {
            Source source = requireSource(orgId, sourceId);
            Document document = documents
                    .findByIdAndOrgId(documentId, orgId)
                    .filter(found -> sourceId.equals(found.getSourceId()))
                    .orElseThrow(() -> ApiException.notFound("document", documentId));
            int passages = chunks.deleteByDocumentId(documentId);
            documents.delete(document);
            refreshCounts(source, false);
            return new Erased(source.getCollection(), 1, passages);
        });
        vectors.deleteByDocument(erased.collection(), documentId);
        log.info(
                "Deleted document {} and its {} passage(s) from source {} for {}",
                documentId,
                erased.passages(),
                sourceId,
                actor());
    }

    /**
     * Erases a source with every document and passage in it.
     *
     * <p>Its vectors are removed by filter, never by dropping the collection: one collection
     * holds every source in the workspace that shares an embedding width.
     */
    public void deleteSource(UUID orgId, UUID sourceId) {
        Erased erased = transactionally(() -> {
            Source source = requireSource(orgId, sourceId);
            int passages = chunks.deleteBySourceId(sourceId);
            int removed = documents.deleteBySourceId(sourceId);
            sources.delete(source);
            return new Erased(source.getCollection(), removed, passages);
        });
        vectors.deleteBySource(erased.collection(), sourceId);
        log.info(
                "Deleted source {} with {} document(s) and {} passage(s) for {}",
                sourceId,
                erased.documents(),
                erased.passages(),
                actor());
    }

    private record Erased(String collection, int documents, int passages) {}

    /**
     * Counts what is indexed, which is all the counts on a source claim to cover.
     *
     * @param ingested whether this follows indexing, which moves "last indexed"; a deletion does not
     */
    private void refreshCounts(Source source, boolean ingested) {
        List<Document> indexed = documents.findBySourceIdOrderByTitle(source.getId()).stream()
                .filter(Document::isIndexed)
                .toList();
        source.setDocumentCount(indexed.size());
        source.setChunkCount(indexed.stream().mapToInt(Document::getChunkCount).sum());
        if (ingested) {
            source.setLastIngestedAt(Instant.now());
            if (!"ready".equals(source.getStatus())) {
                source.setStatus("ready");
            }
        }
        sources.save(source);
    }

    /**
     * The name a kept-both upload is stored under: its own when that is free, otherwise the first
     * free "name (n).ext". The number goes before the extension, so the file still reads as what
     * it is.
     */
    private String freeName(UUID sourceId, String filename) {
        if (!documents.existsBySourceIdAndExternalId(sourceId, filename)) {
            return filename;
        }
        for (int n = 2; n < 1_000; n++) {
            String candidate = numbered(filename, n);
            if (!documents.existsBySourceIdAndExternalId(sourceId, candidate)) {
                return candidate;
            }
        }
        throw ApiException.conflict("This source already holds too many files called " + filename + ".");
    }

    static String numbered(String filename, int n) {
        int dot = filename.lastIndexOf('.');
        // A leading dot names a file (".env") rather than starting an extension.
        if (dot <= 0 || dot == filename.length() - 1) {
            return filename + " (" + n + ")";
        }
        return filename.substring(0, dot) + " (" + n + ")" + filename.substring(dot);
    }

    private Source requireSource(UUID orgId, UUID sourceId) {
        return sources.findByIdAndOrgId(sourceId, orgId).orElseThrow(() -> ApiException.notFound("source", sourceId));
    }

    /**
     * Runs one short transaction, again if it lost a race on a version column. Two uploads into
     * one source both rewrite its counts; the second should recount, not fail the person's upload.
     */
    private <T> T transactionally(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                log.debug("Retrying after a concurrent change: {}", e.getMessage());
            }
        }
    }

    /** The day a replaced version was indexed. Older rows may lack it; their last change stands in. */
    private static Instant indexedAtOf(Document document) {
        return Objects.requireNonNullElseGet(
                document.getIndexedAt(),
                () -> Objects.requireNonNullElseGet(document.getUpdatedAt(), Instant::now));
    }

    /** Joins the sentences that are present. */
    private static String sentences(String... parts) {
        String joined = Stream.of(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        return joined.isEmpty() ? null : joined;
    }

    static String actor() {
        return RequestContext.actor().map(a -> a.id()).orElse("system");
    }
}
