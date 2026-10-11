// @find: knowledge base, knowledge, documents, sources, agent documents, agent knowledge, AI employee documents, agent-owned source, upload file to agent, add note to agent, delete agent document, GET /api/knowledge/agents/{agentId}/documents, POST /api/knowledge/agents/{agentId}/notes, AgentKnowledgeController
// @what: REST endpoints for an AI employee's own private documents and notes, kept apart from workspace sources.
// @flow: Called by the agent detail page; delegates to IngestionService, Sources and Documents, creating the agent's own source on first use.
package os.aiworkforce.knowledge.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Documents;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.knowledge.service.IngestionService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * An AI employee's own documents: files and typed notes that only that agent searches.
 *
 * <p>They are kept in one source that belongs to the agent. It is not in the workspace's source
 * list, not counted there and not part of any workspace search; the agent's searches read it
 * alongside the workspace's documents. Anyone who can read the agent can read the list; adding and
 * removing takes the permission to change the agent.
 */
@RestController
@RequestMapping("/api/knowledge/agents/{agentId}")
@Tag(name = "Agent documents")
public class AgentKnowledgeController {

    /** The largest file accepted for an agent, 25 MB; a larger one is refused before it is read. */
    static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    /** The longest typed note accepted, in characters. */
    static final int MAX_NOTE_CHARS = 20_000;

    private final Sources sources;
    private final Documents documents;
    private final IngestionService ingestion;

    /**
     * Creates the controller.
     *
     * @param sources reads the agent's own sources
     * @param documents lists the documents of those sources
     * @param ingestion creates the source and does the indexing and deleting
     */
    public AgentKnowledgeController(Sources sources, Documents documents, IngestionService ingestion) {
        this.sources = sources;
        this.documents = documents;
        this.ingestion = ingestion;
    }

    /** The body of the note endpoint: a title, which becomes the file name, and the note text. */
    public record NoteRequest(
            @NotBlank @Size(max = 120) String title, @NotBlank @Size(max = MAX_NOTE_CHARS) String text) {}

    // @find: list agent documents, GET /api/knowledge/agents/{agentId}/documents
    /**
     * {@code GET /api/knowledge/agents/{agentId}/documents}: lists the documents and notes the agent keeps.
     *
     * <p>Permission: {@code agent:read}. Read-only. An agent with nothing yet gives an empty list.
     *
     * @return one view per document, ordered by title within each of the agent's sources
     */
    @GetMapping("/documents")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "The documents and notes this agent keeps for itself")
    public List<KnowledgeController.DocumentView> list(@PathVariable UUID agentId) {
        List<KnowledgeController.DocumentView> views = new ArrayList<>();
        for (Source source : sources.findByOrgIdAndAgentIdOrderByName(orgId(), agentId)) {
            documents.findBySourceIdOrderByTitle(source.getId()).forEach(document -> views.add(KnowledgeController.toView(document)));
        }
        return views;
    }

    // @find: upload document to an agent, give agent a file, POST /api/knowledge/agents/{agentId}/documents
    /**
     * {@code POST /api/knowledge/agents/{agentId}/documents}: adds an uploaded file to the agent's own documents.
     *
     * <p>Permission: {@code agent:update}. Creates the agent's own source on first use, then ingests the
     * file (text extraction, chunking, embedding and vector writes, all inside {@code IngestionService}).
     * A file with the name of an existing document is handled as a re-upload by that service.
     *
     * @param file the multipart file, not empty and at most 25 MB
     * @return the ingestion result, including a skip reason when the file could not be indexed
     * @throws ApiException a validation error for an empty or oversized file; {@code INGESTION_FAILED} when the upload cannot be read
     */
    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Add a file to this agent's own documents")
    public IngestionService.IngestResult upload(@PathVariable UUID agentId, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.validation("file", "must not be empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.validation("file", "must be 25 MB or smaller");
        }
        try {
            String name = file.getOriginalFilename();
            return ingestion.ingest(orgId(), ownSource(agentId).getId(), name == null || name.isBlank() ? "upload" : name, file.getBytes());
        } catch (java.io.IOException unreadable) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.INGESTION_FAILED, "The uploaded file could not be read.", unreadable);
        }
    }

    // @find: add note to agent knowledge, write note, POST /api/knowledge/agents/{agentId}/notes
    /**
     * {@code POST /api/knowledge/agents/{agentId}/notes}: writes a typed note into the agent's own documents.
     *
     * <p>Permission: {@code agent:update}. Replaces characters that are unsafe in a file name with
     * spaces, collapses runs of spaces, and ingests the text as {@code <title>.txt}, so a note with the
     * same title is handled as a re-upload by {@code IngestionService}. Creates the agent's source on first use.
     *
     * @return the ingestion result, with status 201
     * @throws ApiException a validation error when the title is empty after cleaning
     */
    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Write a note into this agent's own documents")
    public IngestionService.IngestResult note(@PathVariable UUID agentId, @Valid @RequestBody NoteRequest request) {
        String title = request.title().strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (title.isEmpty()) {
            throw ApiException.validation("title", "must not be empty");
        }
        return ingestion.ingest(
                orgId(), ownSource(agentId).getId(), title + ".txt", request.text().strip().getBytes(StandardCharsets.UTF_8));
    }

    // @find: delete agent document, DELETE /api/knowledge/agents/{agentId}/documents/{documentId}
    /**
     * {@code DELETE /api/knowledge/agents/{agentId}/documents/{documentId}}: removes a document or note.
     *
     * <p>Permission: {@code agent:update}. Looks for the document among the agent's own sources only, so
     * a workspace document or another agent's cannot be deleted through this route. Deleting is done by
     * {@code IngestionService.deleteDocument}, which removes its passages and vectors.
     *
     * @return nothing, with status 204
     * @throws ApiException not found when the agent has no such document
     */
    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Remove a document or note from this agent's own documents")
    public void delete(@PathVariable UUID agentId, @PathVariable UUID documentId) {
        UUID orgId = orgId();
        for (Source source : sources.findByOrgIdAndAgentIdOrderByName(orgId, agentId)) {
            if (documents.findBySourceIdOrderByTitle(source.getId()).stream()
                    .anyMatch(document -> document.getId().equals(documentId))) {
                ingestion.deleteDocument(orgId, source.getId(), documentId);
                return;
            }
        }
        throw ApiException.notFound("document", documentId);
    }

    /**
     * The agent's own source, made the first time something is added to it.
     *
     * <p>Creates, when none exists, an unrestricted source of kind {@code upload} named after the
     * agent, owned by the agent. Called by the upload and note endpoints.
     */
    private Source ownSource(UUID agentId) {
        UUID orgId = orgId();
        List<Source> own = sources.findByOrgIdAndAgentIdOrderByName(orgId, agentId);
        if (!own.isEmpty()) {
            return own.get(0);
        }
        return ingestion.createSource(orgId, agentId, "Agent documents " + agentId, "upload", false);
    }

    /** The workspace of the current request, as an identifier. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
