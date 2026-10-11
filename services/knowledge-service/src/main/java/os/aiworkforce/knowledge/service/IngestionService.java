// @find: knowledge base, knowledge, documents, sources, where the knowledge base is created, create source, upload file, ingest, ingestion, how the knowledge base is updated, re-upload, replace document, keep both, reindex, re-index, delete document, delete source, rename source, restricted source, agent-owned source, chunking, embeddings, Qdrant, IngestionService
// @what: Core of the knowledge base: creates sources, ingests uploaded files (extract, chunk, embed, store), replaces re-uploads, reindexes, renames and deletes.
// @flow: Called by KnowledgeController, AgentKnowledgeController and EmbeddingModelChange; uses TextExtractor, Chunker, EmbeddingService, QdrantClient and the repositories.
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

    /** The same, when it was the embedding model rather than the vector store that failed. */
    static final String EMBEDDING_UNAVAILABLE = "Indexed for keyword search. The embedding model could not be used, so "
            + "meaning-based search will be less accurate until it is reindexed.";

    /** The prefix of the error stored on a source whose vectors could not be written; the cause is appended. */
    static final String SOURCE_VECTOR_ERROR = "Vector indexing is unavailable: ";

    /** A transaction that lost a race on the source's counts is run again, this many times in all. */
    private static final int ATTEMPTS = 3;

    /** Formats the day a replaced version was indexed, in UTC, for the sentences shown to a person. */
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

    /**
     * Creates the service.
     *
     * @param sources the source repository
     * @param documents the document repository
     * @param chunks the passage repository
     * @param extractor reads text out of files
     * @param chunker cuts text into passages
     * @param embeddings turns passages into vectors
     * @param vectors the vector store
     * @param transactionManager used to build the short transactions that surround each remote call
     */
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

    /** The workspace's embedding model choice; absent in tests that build this by hand. */
    private EmbeddingSettings settings;

    /**
     * Supplies the embedding settings by setter injection, optional so that tests can build the
     * service without them; without it every new source is keyword only.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmbeddingSettings(EmbeddingSettings settings) {
        this.settings = settings;
    }

    /** The embedding model new sources in a workspace start on. */
    private EmbeddingSettings.Choice choiceFor(UUID orgId) {
        return settings == null ? EmbeddingSettings.Choice.KEYWORD_ONLY : settings.current(orgId);
    }

    /**
     * Where a source's vectors were before a change of embedding model, returned by
     * {@link #switchEmbedding} so that {@code EmbeddingModelChange} can remove the old vectors.
     */
    public record PreviousEmbedding(String collection, boolean searchedByMeaning) {}

    // @find: switch source to a new embedding model, move source to new vector collection
    /**
     * Moves one source onto another embedding model: the new model, its width and its own
     * collection. The passages are untouched; their vectors are written again by {@link #reindex}.
     *
     * <p>Runs in one short transaction and updates the source row only. Called by
     * {@code EmbeddingModelChange.run} for each source of the workspace. For a keyword-only choice it
     * also clears the source's last error.
     *
     * @return where the vectors were before, so the caller can remove them
     * @throws ApiException not found when the source does not exist in the workspace
     */
    public PreviousEmbedding switchEmbedding(UUID orgId, UUID sourceId, EmbeddingSettings.Choice choice) {
        return transactionally(() -> {
            Source source = requireSource(orgId, sourceId);
            PreviousEmbedding previous =
                    new PreviousEmbedding(source.getCollection(), source.isSearchableByMeaning());
            source.setEmbeddingProvider(choice.provider());
            source.setEmbeddingModel(choice.model());
            source.setEmbeddingDimension(choice.dimension());
            source.setCollection(collectionOf(orgId, choice));
            if (!choice.searchesByMeaning()) {
                // Keyword search only, by choice: an old vector failure no longer describes it.
                source.setLastError(null);
            }
            sources.save(source);
            return previous;
        });
    }

    /**
     * The vector collection for a model choice: the model's own collection when it searches by meaning,
     * otherwise the width-only name used for keyword-only sources.
     */
    static String collectionOf(UUID orgId, EmbeddingSettings.Choice choice) {
        return choice.searchesByMeaning()
                ? QdrantClient.collectionFor("aiwos", orgId, choice.provider(), choice.model(), choice.dimension())
                : QdrantClient.collectionFor("aiwos", orgId, choice.dimension());
    }

    // @find: upload mode, replace existing file, keep both versions, re-upload same name
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

        /**
         * Absent means replace, which is what re-uploading an edited file has always done.
         *
         * @throws ApiException a validation error for any value other than replace or keep_both, in any case
         */
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
     * What an upload did, returned by {@code ingest} to the controllers and from there to the console.
     *
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

    // @find: create knowledge source, new source, where the knowledge base is created, add source
    /**
     * Creates a source to upload into, so a workspace has somewhere to put its first file.
     *
     * @param restricted whether only people who manage knowledge may search it. Settable here so a
     *     source meant to be private is never searchable by the workspace between its creation and
     *     a later change.
     * @param kind the kind of source; {@code upload} when null
     * @return the saved source, on the workspace's current embedding model
     * @throws ApiException {@code ALREADY_EXISTS} when the workspace already has a source of that name, in any case
     */
    @Transactional
    public Source createSource(UUID orgId, String name, String kind, boolean restricted) {
        return createSource(orgId, null, name, kind, restricted);
    }

    // @find: create agent-owned source, agent documents source, create knowledge base for an AI employee
    /**
     * As {@link #createSource(UUID, String, String, boolean)}; with an {@code agentId} the source
     * belongs to that one agent, which alone searches it.
     *
     * <p>Inserts one source row, in its own transaction, with the workspace's current embedding model
     * and that model's collection. No vector collection is created yet; that happens on the first
     * write. Called by {@code KnowledgeController.createSource} (through the four-argument form) and
     * by {@code AgentKnowledgeController} for an agent's first document.
     *
     * @throws ApiException {@code ALREADY_EXISTS} when the workspace already has a source of that name, in any case
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
        // On the workspace's chosen embedding model, in that model's own collection, so changing
        // model cannot write vectors of one model into an index built for another.
        EmbeddingSettings.Choice choice = choiceFor(orgId);
        source.setEmbeddingProvider(choice.provider());
        source.setEmbeddingModel(choice.model());
        source.setEmbeddingDimension(choice.dimension());
        source.setCollection(collectionOf(orgId, choice));
        return sources.save(source);
    }

    // @find: update source, rename source, restrict source, make source restricted, change who can search
    /**
     * Renames a source, or changes who may search it.
     *
     * <p>Restricting a source takes effect on the next search: retrieval reads the flag on every
     * request, so nothing is cached that could keep serving its passages. It cannot take back
     * passages already shown to somebody, such as those quoted in a conversation before the change.
     *
     * @param name the new name, or null to keep it
     * @param restricted the new setting, or null to keep it
     * @return the saved source
     * @throws ApiException not found when the source does not exist in the workspace; {@code ALREADY_EXISTS}
     *     when another source has the name. A change of restriction is written to the log with the actor.
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

    // @find: upload document, ingest file, re-upload replaces old version
    /**
     * Ingests one uploaded file, replacing any document of the same name. The same as the
     * five-argument form with {@link UploadMode#REPLACE}; called for an agent's documents and notes.
     */
    public IngestResult ingest(UUID orgId, UUID sourceId, String filename, byte[] content) {
        return ingest(orgId, sourceId, filename, content, UploadMode.REPLACE);
    }

    // @find: ingest uploaded file, update document by re-upload, replace or keep both, extract chunk embed store, unchanged file skipped
    /**
     * Ingests one uploaded file.
     *
     * <p>Three steps, and only the first and last hold a database connection. A failure in the
     * vector store leaves a document findable by keyword and says so; a failure after the vectors
     * are written removes them again, so no point outlives the passage it stands for.
     *
     * <p>Flow: extracts the text, then in a short transaction creates or updates the document and
     * replaces its passages, then embeds them and writes the vectors with no transaction open, then in a
     * second short transaction records the outcome and refreshes the source's counts. An unchanged file,
     * or one that cannot be indexed, ends after the first step. Old vectors of a replaced version are
     * deleted afterwards. Called by {@code KnowledgeController.upload} and {@code AgentKnowledgeController}.
     *
     * <p>Side effects: writes document, passage and source rows, calls the embedding service and the
     * vector store, and writes log lines. It writes no audit record; the controller does.
     *
     * @param filename the file's own name, which identifies the document within the source
     * @param mode what to do when the name already exists
     * @return what happened, including a vector warning when only keyword search could be written
     * @throws ApiException not found for an unknown source; a conflict when the document was deleted or
     *     replaced by another upload while it was being indexed. A vector or embedding failure is not
     *     thrown: it is reported in the result.
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
            vectorWarning = e instanceof EmbeddingService.EmbeddingRefused ? EMBEDDING_UNAVAILABLE : VECTORS_UNAVAILABLE;
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

        /** Builds the result for an upload that ends after the first step, with nothing to embed. */
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

    /**
     * The first step: the document and its passages, committed before anything remote is asked.
     *
     * <p>Finds or creates the document by name (a free name when keeping both), ends early for unchanged
     * bytes, clears any tombstone and old reason, records a skipped document when the file is not
     * indexable or yields no passages, and otherwise replaces the document's passages with the new
     * ones and leaves the document {@code pending}. Runs inside the caller's transaction.
     */
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
     *
     * <p>Marks the document skipped with the reason, deletes any passages it had, and recounts the
     * source. The result says when an earlier searchable version is no longer searchable.
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

    /**
     * The last step: the outcome, written once the vectors have been.
     *
     * <p>Checks that the document still holds the passages that were embedded, marks it indexed, sets
     * the source ready with the vector error if any, recounts the source and builds the result sentences.
     * Runs inside the caller's transaction.
     *
     * @throws ApiException a conflict when the document was deleted meanwhile, or when an upload of different
     *     content replaced its passages; the same bytes uploaded twice at once end as {@code unchanged}
     */
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
     *
     * <p>Does nothing for a source on the offline embeddings. Otherwise makes sure the collection
     * exists, embeds all the passages through the orchestrator and upserts one vector per passage with
     * the workspace, source and document in its payload. Called by {@code ingest} and {@code reindex}.
     *
     * @throws ApiException when the vector store is unavailable or refuses the write
     * @throws EmbeddingService.EmbeddingRefused when the embedding model cannot be used
     */
    private void writeVectors(Source source, UUID documentId, List<Chunk> stored) {
        if (!writesVectors(source)) {
            return;
        }
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
     * Whether a source's passages are written to the vector store at all. Not for a source on the
     * offline sandbox's embeddings: search never reads those vectors (they carry no meaning), so
     * writing them only cost a call, and with the store down it told people that search by meaning
     * had failed for a source that never had it (seen 8 Oct 2026).
     */
    static boolean writesVectors(Source source) {
        return source.isSearchableByMeaning();
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
     * The outcome of a reindex, returned to {@code KnowledgeController.reindex} and
     * {@code EmbeddingModelChange}.
     *
     * @param documentCount documents examined, which are the indexed ones and the unfinished ones
     * @param chunkCount passages whose vectors were attempted
     * @param vectorised whether the vectors of at least one document could be written
     * @param detail a sentence saying why no vectors could be written, or null
     * @param recovered documents whose indexing had stopped part way and is now finished
     */
    public record ReindexResult(int documentCount, int chunkCount, boolean vectorised, String detail, int recovered) {}

    // @find: reindex source, rebuild search index, re-embed documents, recover pending documents
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
     *
     * <p>Called by {@code KnowledgeController.reindex}. Writes vectors, may mark unfinished documents
     * indexed, and updates the source's status, last error and counts. A vector or embedding failure is
     * not thrown: it is reported in the result and on the source, and keyword search is unaffected.
     *
     * @return the number of documents and passages handled, whether vectors were written and any detail
     * @throws ApiException not found when the source does not exist
     */
    public ReindexResult reindex(UUID orgId, UUID sourceId) {
        return reindex(orgId, sourceId, passages -> {});
    }

    // @find: reindex source with progress, re-index documents, progress bar
    /**
     * As {@link #reindex(UUID, UUID)}, saying after each document how many of its passages were
     * attempted, for a progress bar. Called by {@code EmbeddingModelChange.run} with a callback that
     * publishes its progress.
     *
     * @param progress called after each document with the number of its passages handled
     */
    public ReindexResult reindex(UUID orgId, UUID sourceId, java.util.function.IntConsumer progress) {
        Source source = requireSource(orgId, sourceId);

        List<Document> candidates = documents.findBySourceIdOrderByTitle(sourceId).stream()
                .filter(document -> document.isIndexed() || isUnfinished(document))
                .toList();

        int totalChunks = 0;
        int attempted = 0;
        int recovered = 0;
        boolean anyVectorised = false;
        String failure = null;
        boolean embeddingRefused = false;
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
                if (embeddingRefused) {
                    // The model refused an earlier document and will refuse this one the same
                    // way; asking again is a call, and on a metered provider a charge, for nothing.
                    throw new EmbeddingService.EmbeddingRefused(
                            source.getEmbeddingProvider(), source.getEmbeddingModel(), "it refused an earlier document.");
                }
                writeVectors(source, documentId, stored);
                anyVectorised = true;
                // A document replaced or erased while its vectors were being written leaves points
                // for passages that no longer exist; they are taken back out at once.
                Set<UUID> current = new HashSet<>(chunks.findIdsByDocumentId(documentId));
                vectors.deletePoints(
                        source.getCollection(),
                        written.stream().filter(id -> !current.contains(id)).toList());
            } catch (RuntimeException e) {
                if (!embeddingRefused) {
                    failure = e.getMessage();
                }
                embeddingRefused = embeddingRefused || e instanceof EmbeddingService.EmbeddingRefused;
                log.warn("Vector indexing failed for document {}; keyword search still works", documentId);
            }
            // Finished whether or not the vectors could be written: its passages are in Postgres,
            // so keyword search finds it, exactly as for an upload made while the store was down.
            if (isUnfinished(document) && Boolean.TRUE.equals(transactionally(() -> settle(orgId, documentId, written)))) {
                recovered++;
            }
            progress.accept(stored.size());
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
                : embeddingRefused
                        ? failure + " Keyword search is unaffected."
                        : "The vector store is still unavailable. Keyword search is unaffected.";
        log.info(
                "Reindexed {} document(s), {} passage(s), {} recovered from an unfinished indexing, for source {}",
                candidates.size(),
                totalChunks,
                recovered,
                sourceId);
        return new ReindexResult(candidates.size(), totalChunks, anyVectorised, detail, recovered);
    }

    /**
     * A document whose passages were committed but whose indexing never recorded an outcome: its
     * status is still {@code pending} and it is not tombstoned.
     */
    private static boolean isUnfinished(Document document) {
        return "pending".equals(document.getStatus()) && document.getTombstonedAt() == null;
    }

    /**
     * Marks a recovered document indexed, if it is still the one that was recovered: still
     * pending, and still holding exactly the passages whose vectors were just written.
     *
     * <p>Runs in a short transaction started by {@code reindex}; writes the document's status and time.
     *
     * @return true when the document was marked indexed, false when it had changed meanwhile
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

    // @find: delete document, remove file from knowledge base, erase passages and vectors
    /**
     * Erases one document: its passages, its record and its vectors.
     *
     * <p>Deleted outright rather than tombstoned. A person deleting a file is often honouring an
     * erasure request or retiring something uploaded by mistake, and a tombstone would keep its
     * title, hash and every passage in the database. The vectors go after the commit: if they
     * cannot, retrieval still finds nothing, because every hit is resolved against the passages.
     *
     * <p>Called by {@code KnowledgeController.deleteDocument} and {@code AgentKnowledgeController}. Deletes
     * the passages and the document and recounts the source in one transaction, then removes the vectors
     * and logs the actor. The audit record is written by the controller.
     *
     * @throws ApiException not found when the source does not exist, or the document is not in that
     *     source of the workspace
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

    // @find: delete source, remove knowledge source, erase all documents in source
    /**
     * Erases a source with every document and passage in it.
     *
     * <p>Its vectors are removed by filter, never by dropping the collection: one collection
     * holds every source in the workspace that shares an embedding width.
     *
     * <p>Called by {@code KnowledgeController.deleteSource}. Deletes passages, documents and the source
     * in one transaction, then the vectors, and logs the actor. The audit record is written by the controller.
     *
     * @throws ApiException not found when the source does not exist in the workspace
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

    /** What a deletion removed, so its vectors can be removed after the commit and the log can report counts. */
    private record Erased(String collection, int documents, int passages) {}

    /**
     * Counts what is indexed, which is all the counts on a source claim to cover.
     *
     * <p>Counts the source's currently indexed documents and their passages and saves the source. It
     * must run inside a transaction opened by the caller.
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

    /**
     * The name with a number before the extension, such as {@code Contract (2).pdf}. A name with no
     * extension, or only a leading dot, gets the number at the end.
     */
    static String numbered(String filename, int n) {
        int dot = filename.lastIndexOf('.');
        // A leading dot names a file (".env") rather than starting an extension.
        if (dot <= 0 || dot == filename.length() - 1) {
            return filename + " (" + n + ")";
        }
        return filename.substring(0, dot) + " (" + n + ")" + filename.substring(dot);
    }

    /** The source in the workspace, or a not found error. A source of another workspace is reported as absent. */
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

    /** The identifier of the acting person, agent or service for log lines; {@code system} when there is none. */
    static String actor() {
        return RequestContext.actor().map(a -> a.id()).orElse("system");
    }
}
