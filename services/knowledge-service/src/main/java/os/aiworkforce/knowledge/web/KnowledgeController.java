package os.aiworkforce.knowledge.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;
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
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Knowledge")
public class KnowledgeController {

    /** A single upload is capped here as well as at the edge, so the limit holds either way. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    private final Sources sources;
    private final Documents documents;
    private final RetrievalService retrieval;
    private final IngestionService ingestion;

    public KnowledgeController(
            Sources sources, Documents documents, RetrievalService retrieval, IngestionService ingestion) {
        this.sources = sources;
        this.documents = documents;
        this.retrieval = retrieval;
        this.ingestion = ingestion;
    }

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
            String lastError) {}

    public record DocumentView(
            UUID id,
            String title,
            String mediaType,
            String status,
            String skipReason,
            int chunkCount,
            Instant indexedAt,
            boolean removedAtSource) {}

    public record SearchRequest(@NotBlank @Size(max = 1_000) String query, Integer limit, List<UUID> sourceIds) {}

    /**
     * @param passages what supports an answer, with enough detail to cite each one
     * @param grounded false when nothing matched, so the caller says "no source supports this"
     *     rather than presenting an empty result as a failure
     */
    public record SearchResponse(List<RetrievalService.Passage> passages, boolean grounded) {}

    @GetMapping("/sources")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Connected knowledge sources")
    public List<SourceView> listSources() {
        return sources.findByOrgIdOrderByName(orgId()).stream()
                .map(KnowledgeController::toView)
                .toList();
    }

    @GetMapping("/sources/{sourceId}")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "One source")
    public SourceView getSource(@PathVariable UUID sourceId) {
        return toView(sources.findByIdAndOrgId(sourceId, orgId())
                .orElseThrow(() -> ApiException.notFound("source", sourceId)));
    }

    @GetMapping("/sources/{sourceId}/documents")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Documents in a source, including the ones that could not be indexed")
    public List<DocumentView> listDocuments(@PathVariable UUID sourceId) {
        sources.findByIdAndOrgId(sourceId, orgId()).orElseThrow(() -> ApiException.notFound("source", sourceId));
        return documents.findBySourceIdOrderByTitle(sourceId).stream()
                .map(KnowledgeController::toView)
                .toList();
    }

    @PostMapping("/knowledge/search")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_QUERY)
    @Operation(summary = "Search the knowledge base, with a citation for every passage")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        List<RetrievalService.Passage> passages = retrieval.retrieve(
                orgId(), request.query(), request.limit() == null ? 8 : request.limit(), request.sourceIds());
        // Reported as ungrounded rather than as an error: "no document supports an answer" is a
        // real answer, and an agent must be able to say it instead of inventing one.
        return new SearchResponse(passages, !passages.isEmpty());
    }

    public record CreateSourceRequest(@NotBlank @Size(max = 120) String name, String kind) {}

    @PostMapping("/sources")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Create a source to upload documents into")
    public SourceView createSource(@Valid @RequestBody CreateSourceRequest request) {
        return toView(ingestion.createSource(orgId(), request.name().strip(), request.kind()));
    }

    /**
     * Uploads and indexes one document.
     *
     * <p>Returns the outcome rather than a bare acknowledgement, because the interesting cases are
     * not success: a scan with no text layer, an encrypted file, or a document that has not
     * changed since last time each need to be reported differently.
     */
    @PostMapping(value = "/sources/{sourceId}/documents", consumes = "multipart/form-data")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Upload a document and index it")
    public IngestionService.IngestResult upload(@PathVariable UUID sourceId, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.validation("file", "must not be empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.validation(
                    "file", "must be 25 MB or smaller; this file is " + (file.getSize() / (1024 * 1024)) + " MB");
        }
        try {
            String name = file.getOriginalFilename();
            return ingestion.ingest(
                    orgId(), sourceId, name == null || name.isBlank() ? "upload" : name, file.getBytes());
        } catch (java.io.IOException e) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.INGESTION_FAILED,
                    "The uploaded file could not be read.",
                    e);
        }
    }

    public record ReindexResponse(
            UUID sourceId, String status, int documentsQueued, boolean vectorised, String detail) {}

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
        return new ReindexResponse(sourceId, "reindexed", result.documentCount(), result.vectorised(), result.detail());
    }

    @GetMapping("/knowledge/health")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "Whether the knowledge base can currently answer")
    public java.util.Map<String, Object> health(@RequestParam(defaultValue = "false") boolean includeCounts) {
        List<Source> all = sources.findByOrgIdOrderByName(orgId());
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
                source.getLastError());
    }

    private static DocumentView toView(Document document) {
        return new DocumentView(
                document.getId(),
                document.getTitle(),
                document.getMediaType(),
                document.getStatus(),
                document.getSkipReason(),
                document.getChunkCount(),
                document.getIndexedAt(),
                document.getTombstonedAt() != null);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
