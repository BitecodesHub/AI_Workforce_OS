// @find: knowledge base, knowledge, documents, sources, embedding settings, change embedding model, which model searches documents, reindex everything, GET /api/knowledge/embedding, PUT /api/knowledge/embedding, POST /api/knowledge/embedding/reindex, Embedding model setting, EmbeddingSettingsController
// @what: REST endpoints to read the embedding model status, choose a new one and resume a full reindex.
// @flow: Calls EmbeddingModelChange.
package os.aiworkforce.knowledge.web;

import java.util.UUID;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.knowledge.service.EmbeddingModelChange;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The workspace's choice of embedding model for searching documents by meaning, and the progress
 * of re-indexing after it changes.
 */
@RestController
@RequestMapping("/api/knowledge/embedding")
@Tag(name = "Knowledge")
public class EmbeddingSettingsController {

    private final EmbeddingModelChange change;

    /**
     * Creates the controller.
     *
     * @param change the service that reads and changes the model
     */
    public EmbeddingSettingsController(EmbeddingModelChange change) {
        this.change = change;
    }

    /**
     * The body of the model change.
     *
     * @param providerId the provider, or {@code sandbox} (or blank) for keyword search only
     * @param modelId the embedding model of that provider; required unless the provider is {@code sandbox} or blank
     */
    public record ChangeRequest(@Size(max = 100) String providerId, @Size(max = 300) String modelId) {}

    // @find: embedding model status, GET /api/knowledge/embedding
    /**
     * {@code GET /api/knowledge/embedding}: the workspace's embedding model and re-indexing progress.
     *
     * <p>Permission: {@code knowledge:read}. Read-only.
     */
    @GetMapping
    @RequiresPermission(Permission.Codes.KNOWLEDGE_READ)
    @Operation(summary = "The embedding model documents are searched by meaning with, and re-indexing progress")
    public EmbeddingModelChange.Status status() {
        return change.status(orgId());
    }

    /**
     * {@code PUT /api/knowledge/embedding}: chooses the embedding model and starts re-indexing in the background.
     *
     * <p>Permission: {@code knowledge:source_manage}. Delegates to {@code EmbeddingModelChange.change}
     * with the acting person's identifier. Returns the status with progress {@code running}; the work
     * continues after the response.
     *
     * @throws ApiException a conflict when a re-index is already running; a validation error when the model
     *     is missing or refused; {@code UPSTREAM_UNAVAILABLE} when the model service cannot be reached
     */
    @PutMapping
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(
            summary = "Choose the embedding model and re-index every source in the background",
            description = "The model is tried first with one short passage; one that cannot be used is refused"
                    + " with the reason and nothing changes. Keyword search keeps working while passages are"
                    + " embedded again.")
    // @find: choose embedding model, change embedding model, PUT /api/knowledge/embedding
    public EmbeddingModelChange.Status choose(@RequestBody ChangeRequest request) {
        return change.change(orgId(), request.providerId(), request.modelId(), actorId());
    }

    // @find: resume reindex, re-embed all knowledge, POST /api/knowledge/embedding/reindex
    /**
     * {@code POST /api/knowledge/embedding/reindex}: re-indexes every source with the current model.
     *
     * <p>Permission: {@code knowledge:source_manage}. Delegates to {@code EmbeddingModelChange.resume}; the
     * model is not tried again. Returns the status with progress {@code running}.
     *
     * @throws ApiException a conflict when a re-index is already running
     */
    @PostMapping("/reindex")
    @RequiresPermission(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE)
    @Operation(summary = "Re-index every source with the current embedding model, after a failure or interruption")
    public EmbeddingModelChange.Status reindex() {
        return change.resume(orgId());
    }

    /** The workspace of the current request, as an identifier. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }

    /** The identifier of the acting person or service, or {@code system} when there is none. */
    private static String actorId() {
        return RequestContext.actor().map(actor -> actor.id()).orElse("system");
    }
}
