package os.aiworkforce.knowledge.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.knowledge.domain.Chunk;
import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.Documents;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.knowledge.service.IngestionService;
import os.aiworkforce.knowledge.service.RetrievalService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Knowledge sources, their documents, and search over them.
 *
 * <p>The counts returned here deliberately separate what was indexed from what was skipped. A
 * source reporting "118 documents" while six of them are scanned images with no text layer will
 * answer some questions badly, and the only way anybody finds out which is by seeing what was
 * left out and why.
 *
 * <p>A restricted source is visible only to people who manage knowledge. To everyone else every
 * read here behaves as if it did not exist: it is missing from the list, the counts and search
 * results, and asking for it by id is a 404 rather than a 403, so even its name stays private.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Knowledge")
public class KnowledgeController {

    /** A single upload is capped here as well as at the edge, so the limit holds either way. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    /** The most passages one page of a document's passages holds. */
    private static final int MAX_PASSAGE_PAGE = 200;

    private final Sources sources;
    private final Documents documents;
    private final Chunks chunks;
    private final RetrievalService retrieval;
    private final IngestionService ingestion;

    /** Absent in a test that builds the controller by hand; present in the running service. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.platform.web.audit.AuditClient audit;

    public KnowledgeController(
            Sources sources,
            Documents documents,
            Chunks chunks,
            RetrievalService retrieval,
            IngestionService ingestion) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.retrieval = retrieval;
        this.ingestion = ingestion;
    }

    /**
     * @param restricted whether only people who manage knowledge can search and see it
     * @param searchMode how it is searched: {@code keyword} for a source whose embeddings are the
     *     offline sandbox's, which carry no meaning, otherwise {@code keyword+meaning}
     */
    public record SourceView(
            UUID id,
            String name,
            String kind,
            String status,
            int documentCount,
            int chunkCount,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimension,
            Instant lastIngestedAt,
            String lastError,
            boolean restricted,
            String searchMode) {}

    /**
     * @param skipReason why nothing of it could be indexed
     * @param notice something worth knowing about a document that was indexed, such as only the
     *     first part of a very long file being kept
     */
    public record DocumentView(
            UUID id,
            String title,
            String mediaType,
            String status,
            String skipReason,
            String notice,
            int chunkCount,
            Instant indexedAt,
            boolean removedAtSource) {}

    /**
     * @param limit how many passages to return, 8 when left out
     * @param sourceIds the sources to search, or none for every one the caller may read; a source
     *     the caller may not read is left out silently, as if it did not exist
     */
    public record SearchRequest(
            @NotBlank @Size(max = 1_000) String query,
            @Min(1) @Max(RetrievalService.MAX_LIMIT) Integer limit,
            List<UUID> sourceIds) {}

    /**
     * @param passages what supports an answer, with enough detail to cite each one
     * @param grounded false when nothing matched, so the caller says "no source supports this"
     *     rather than presenting an empty result as a failure
     * @param degraded true when meaning-based search was unavailable and these are keyword matches
     *     only, so the caller can say "keyword search only right now"
     */
    public record SearchResponse(List<RetrievalService.Passage> passages, boolean grounded, boolean degraded) {}

    @GetMapping("/sources")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Connected knowledge sources")
    public List<SourceView> listSources() {
        return sources.findVisible(orgId(), seesRestricted()).stream()
                .map(KnowledgeController::toView)
                .toList();
    }

    @GetMapping("/sources/{sourceId}")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "One source")
    public SourceView getSource(@PathVariable UUID sourceId) {
        return toView(visibleSource(sourceId));
    }

    public record UpdateSourceRequest(@Size(max = 120) String name, Boolean restricted) {}

    /**
     * Renames a source, or changes who may search it. Either field may be left out to keep it.
     *
     * <p>Restricting a source takes effect on the next search. It cannot take back passages already
     * shown to somebody, such as those quoted in a conversation before the change.
     */
    @PatchMapping("/sources/{sourceId}")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Rename a source, or restrict it to people who manage knowledge")
    public SourceView updateSource(@PathVariable UUID sourceId, @Valid @RequestBody UpdateSourceRequest request) {
        String name = request.name() == null ? null : request.name().strip();
        if (name != null && name.isEmpty()) {
            throw ApiException.validation("name", "must not be empty");
        }
        SourceView updated = toView(ingestion.updateSource(orgId(), sourceId, name, request.restricted()));
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        if (name != null) {
            detail.put("name", name);
        }
        if (request.restricted() != null) {
            detail.put("restricted", request.restricted());
        }
        record("source.update", "source", sourceId, detail);
        return updated;
    }

    @GetMapping("/sources/{sourceId}/documents")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Documents in a source, including the ones that could not be indexed")
    public List<DocumentView> listDocuments(@PathVariable UUID sourceId) {
        visibleSource(sourceId);
        return documents.findBySourceIdOrderByTitle(sourceId).stream()
                .map(KnowledgeController::toView)
                .toList();
    }

    /**
     * One passage of a document, as it was cut for search.
     *
     * @param position its place in the document, from 0
     * @param pageNumber the page it is estimated to sit on, for a paginated document
     * @param heading the section it sat under
     */
    public record PassageView(UUID id, int position, Integer pageNumber, String heading, String content) {}

    /**
     * @param total how many passages the document has in all
     * @param page which page of them this is, from 0
     */
    public record DocumentPassages(
            UUID documentId, String title, long total, int page, int size, List<PassageView> passages) {}

    /**
     * A document's passages in reading order, so a person can see what search will find in it -
     * and why a question it should answer comes back empty - without running an agent.
     */
    @GetMapping("/sources/{sourceId}/documents/{documentId}/chunks")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "The passages a document was cut into, in order")
    public DocumentPassages listPassages(
            @PathVariable UUID sourceId,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0) {
            throw ApiException.validation("page", "must be 0 or more");
        }
        if (size < 1 || size > MAX_PASSAGE_PAGE) {
            throw ApiException.validation("size", "must be between 1 and " + MAX_PASSAGE_PAGE);
        }
        visibleSource(sourceId);
        // Through the source it belongs to, as deleting is: a document id alone, with another
        // source's id beside it, must not open it.
        Document document = documents.findByIdAndOrgId(documentId, orgId())
                .filter(found -> sourceId.equals(found.getSourceId()))
                .orElseThrow(() -> ApiException.notFound("document", documentId));
        Page<Chunk> found = chunks.findPageByDocumentIdOrderByPosition(documentId, PageRequest.of(page, size));
        return new DocumentPassages(
                document.getId(),
                document.getTitle(),
                found.getTotalElements(),
                page,
                size,
                found.getContent().stream()
                        .map(chunk -> new PassageView(
                                chunk.getId(),
                                chunk.getPosition(),
                                chunk.getPageNumber(),
                                chunk.getHeading(),
                                chunk.getContent()))
                        .toList());
    }

    @PostMapping("/knowledge/search")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_QUERY)
    @Operation(summary = "Search the knowledge base, with a citation for every passage")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        RetrievalService.Retrieval found = retrieval.retrieve(
                orgId(),
                request.query(),
                request.limit() == null ? 8 : request.limit(),
                request.sourceIds(),
                seesRestricted());
        // Reported as ungrounded rather than as an error: "no document supports an answer" is a
        // real answer, and an agent must be able to say it instead of inventing one.
        return new SearchResponse(found.passages(), !found.passages().isEmpty(), found.degraded());
    }

    /** @param restricted whether only people who manage knowledge may search it; false when left out */
    public record CreateSourceRequest(@NotBlank @Size(max = 120) String name, String kind, Boolean restricted) {}

    @PostMapping("/sources")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Create a source to upload documents into")
    public SourceView createSource(@Valid @RequestBody CreateSourceRequest request) {
        SourceView created = toView(ingestion.createSource(
                orgId(), request.name().strip(), request.kind(), Boolean.TRUE.equals(request.restricted())));
        record(
                "source.create",
                "source",
                created.id(),
                java.util.Map.of("name", created.name(), "restricted", Boolean.TRUE.equals(request.restricted())));
        return created;
    }

    /**
     * Uploads and indexes one document.
     *
     * <p>Returns the outcome rather than a bare acknowledgement, because the interesting cases are
     * not success: a scan with no text layer, an encrypted file, a new version replacing an old
     * one, or a document that has not changed since last time each need to be reported
     * differently.
     *
     * <p>{@code mode} decides what happens when the source already holds a file of that name:
     * {@code replace}, the default, makes this the new version; {@code keep_both} keeps the old
     * one and stores this under the next free name.
     */
    @PostMapping(value = "/sources/{sourceId}/documents", consumes = "multipart/form-data")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Upload a document and index it")
    public IngestionService.IngestResult upload(
            @PathVariable UUID sourceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", required = false) String mode) {
        IngestionService.UploadMode uploadMode = IngestionService.UploadMode.fromWire(mode);
        if (file.isEmpty()) {
            throw ApiException.validation("file", "must not be empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.validation(
                    "file", "must be 25 MB or smaller; this file is " + (file.getSize() / (1024 * 1024)) + " MB");
        }
        try {
            String name = file.getOriginalFilename();
            IngestionService.IngestResult result = ingestion.ingest(
                    orgId(), sourceId, name == null || name.isBlank() ? "upload" : name, file.getBytes(), uploadMode);
            if (result.documentId() != null) {
                record(
                        result.replacedIndexedAt() != null ? "document.replace" : "document.upload",
                        "document",
                        result.documentId(),
                        java.util.Map.of("sourceId", sourceId.toString(), "title", String.valueOf(result.title())));
            }
            return result;
        } catch (java.io.IOException e) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.INGESTION_FAILED,
                    "The uploaded file could not be read.",
                    e);
        }
    }

    /** @param recovered documents whose indexing had stopped part way, now finished */
    public record ReindexResponse(
            UUID sourceId, String status, int documentsQueued, boolean vectorised, String detail, int recovered) {}

    /**
     * Re-embeds a source from its already-stored text, for when the vector store was down
     * during ingestion. Nothing is re-uploaded: the passages survived that failure in Postgres,
     * so this only retries the embed-and-upsert half.
     */
    @PostMapping("/sources/{sourceId}/reindex")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Retry vector indexing for every document already in a source")
    public ReindexResponse reindex(@PathVariable UUID sourceId) {
        IngestionService.ReindexResult result = ingestion.reindex(orgId(), sourceId);
        return new ReindexResponse(
                sourceId,
                "reindexed",
                result.documentCount(),
                result.vectorised(),
                result.detail(),
                result.recovered());
    }

    /**
     * Erases one document: its passages, its vectors and the record of it.
     *
     * <p>A hard delete, because this is what honours an erasure request or retires a file uploaded
     * by mistake. It stops being citable as soon as this returns.
     */
    @DeleteMapping("/sources/{sourceId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Delete a document and everything indexed from it")
    public void deleteDocument(@PathVariable UUID sourceId, @PathVariable UUID documentId) {
        ingestion.deleteDocument(orgId(), sourceId, documentId);
        record("document.delete", "document", documentId, java.util.Map.of("sourceId", sourceId.toString()));
    }

    /** Erases a source with every document in it. Other sources in the workspace are untouched. */
    @DeleteMapping("/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Delete a source and every document in it")
    public void deleteSource(@PathVariable UUID sourceId) {
        ingestion.deleteSource(orgId(), sourceId);
        record("source.delete", "source", sourceId, java.util.Map.of());
    }

    private void record(String action, String resourceType, UUID id, java.util.Map<String, Object> detail) {
        if (audit != null) {
            audit.record(action, resourceType, id.toString(), "succeeded", detail);
        }
    }

    @GetMapping("/knowledge/health")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Whether the knowledge base can currently answer")
    public java.util.Map<String, Object> health(@RequestParam(defaultValue = "false") boolean includeCounts) {
        // Counts only what the caller may see: a restricted source's document count is a fact
        // about it, and the source itself is not theirs to know about.
        List<Source> all = sources.findVisible(orgId(), seesRestricted());
        long ready = all.stream().filter(Source::isReady).count();
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("sources", all.size());
        status.put("ready", ready);
        status.put("needingAttention", all.size() - ready);
        if (includeCounts) {
            status.put(
                    "documents", all.stream().mapToInt(Source::getDocumentCount).sum());
            status.put("passages", all.stream().mapToInt(Source::getChunkCount).sum());
        }
        return status;
    }

    private static SourceView toView(Source source) {
        return new SourceView(
                source.getId(),
                source.getName(),
                source.getKind(),
                source.getStatus(),
                source.getDocumentCount(),
                source.getChunkCount(),
                source.getEmbeddingProvider(),
                source.getEmbeddingModel(),
                source.getEmbeddingDimension(),
                source.getLastIngestedAt(),
                source.getLastError(),
                source.isRestricted(),
                source.getSearchMode());
    }

    static DocumentView toView(Document document) {
        return new DocumentView(
                document.getId(),
                document.getTitle(),
                document.getMediaType(),
                document.getStatus(),
                document.getSkipReason(),
                document.getNotice(),
                document.getChunkCount(),
                document.getIndexedAt(),
                document.getTombstonedAt() != null);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }

    /** Whether the caller may see restricted sources: people who manage knowledge may. */
    private static boolean seesRestricted() {
        return RequestContext.requireActor().hasPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE);
    }

    /** A source the caller may see, or a 404 - the same answer as for one that does not exist. */
    private Source visibleSource(UUID sourceId) {
        return sources.findByIdAndOrgId(sourceId, orgId())
                .filter(source -> !source.isRestricted() || seesRestricted())
                .orElseThrow(() -> ApiException.notFound("source", sourceId));
    }
}
