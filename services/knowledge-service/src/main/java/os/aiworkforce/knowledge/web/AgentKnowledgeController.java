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

    static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    static final int MAX_NOTE_CHARS = 20_000;

    private final Sources sources;
    private final Documents documents;
    private final IngestionService ingestion;

    public AgentKnowledgeController(Sources sources, Documents documents, IngestionService ingestion) {
        this.sources = sources;
        this.documents = documents;
        this.ingestion = ingestion;
    }

    public record NoteRequest(
            @NotBlank @Size(max = 120) String title, @NotBlank @Size(max = MAX_NOTE_CHARS) String text) {}

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

    /** The agent's own source, made the first time something is added to it. */
    private Source ownSource(UUID agentId) {
        UUID orgId = orgId();
        List<Source> own = sources.findByOrgIdAndAgentIdOrderByName(orgId, agentId);
        if (!own.isEmpty()) {
            return own.get(0);
        }
        return ingestion.createSource(orgId, agentId, "Agent documents " + agentId, "upload", false);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
