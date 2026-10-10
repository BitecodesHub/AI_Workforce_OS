// @find: agent memory, GET/POST/PUT/DELETE /api/memory/agents/{agentId}/memories, add note, edit note, delete note, pin note, forget all, Agent page memory tab, what an agent remembers
// @what: REST endpoints that let people view and manage what an AI employee remembers.
// @flow: Calls AgentMemoryService; permission agent:read / agent:update
package os.aiworkforce.memory.web;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.memory.domain.AgentMemory;
import os.aiworkforce.memory.service.AgentMemoryService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * What an AI employee remembers, for the people who look after it.
 *
 * <p>Anyone who can read the agent can read its memory; changing it takes the permission to change
 * the agent. A note that looks like a password, key or card number is refused. Every note belongs
 * to the one agent in the path, in the workspace the caller's token names.
 */
@RestController
@RequestMapping("/api/memory/agents/{agentId}/memories")
@Tag(name = "Agent memory")
public class AgentMemoryController {

    private final AgentMemoryService memories;

    public AgentMemoryController(AgentMemoryService memories) {
        this.memories = memories;
    }

    /** @param source {@code agent} when the agent kept it while working, {@code person} when somebody wrote it */
    public record MemoryView(
            UUID id,
            String kind,
            String content,
            String source,
            UUID runId,
            int recallCount,
            Instant lastRecalledAt,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            boolean pinned) {

        static MemoryView of(AgentMemory memory) {
            return new MemoryView(
                    memory.getId(),
                    memory.getKind(),
                    memory.getContent(),
                    memory.getSource(),
                    memory.getRunId(),
                    memory.getRecallCount(),
                    memory.getLastRecalledAt(),
                    memory.getCreatedAt(),
                    memory.getCreatedBy(),
                    memory.getUpdatedAt(),
                    memory.isPinned());
        }
    }

    public record MemoryList(List<MemoryView> memories, long total, int limit) {}

    public record MemoryRequest(
            @Size(max = 20) String kind, @NotBlank @Size(max = AgentMemory.MAX_CONTENT) String content) {}

    // @find: GET /api/memory/agents/{agentId}/memories, list, endpoint, agent memory
    @GetMapping
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "What this agent remembers, newest change first")
    public MemoryList list(@PathVariable UUID agentId, @RequestParam(defaultValue = "200") int limit) {
        UUID orgId = orgId();
        List<MemoryView> views =
                memories.list(orgId, agentId, limit).stream().map(MemoryView::of).toList();
        return new MemoryList(views, memories.count(orgId, agentId), AgentMemoryService.MAX_PER_AGENT);
    }

    // @find: POST /api/memory/agents/{agentId}/memories, add, endpoint, agent memory
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Write a note into this agent's memory")
    public MemoryView add(@PathVariable UUID agentId, @Valid @RequestBody MemoryRequest request) {
        AgentMemoryService.Saved saved = memories.add(
                orgId(), agentId, request.kind(), request.content(), "person", actorId(), null);
        return MemoryView.of(saved.memory());
    }

    // @find: PUT /api/memory/agents/{agentId}/memories/{memoryId}, update, endpoint, agent memory
    @PutMapping("/{memoryId}")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Correct a note")
    public MemoryView update(
            @PathVariable UUID agentId, @PathVariable UUID memoryId, @Valid @RequestBody MemoryRequest request) {
        return MemoryView.of(
                memories.update(orgId(), agentId, memoryId, request.kind(), request.content(), actorId()));
    }

    // @find: DELETE /api/memory/agents/{agentId}/memories/{memoryId}, delete, endpoint, agent memory
    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Remove a note")
    public void delete(@PathVariable UUID agentId, @PathVariable UUID memoryId) {
        memories.delete(orgId(), agentId, memoryId);
    }

    // @find: PUT /api/memory/agents/{agentId}/memories/{memoryId}/pin, pin, endpoint, agent memory
    @PutMapping("/{memoryId}/pin")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Pin a note so the agent always recalls it")
    public MemoryView pin(@PathVariable UUID agentId, @PathVariable UUID memoryId) {
        return MemoryView.of(memories.pin(orgId(), agentId, memoryId, true));
    }

    // @find: DELETE /api/memory/agents/{agentId}/memories/{memoryId}/pin, unpin, endpoint, agent memory
    @DeleteMapping("/{memoryId}/pin")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Unpin a note")
    public MemoryView unpin(@PathVariable UUID agentId, @PathVariable UUID memoryId) {
        return MemoryView.of(memories.pin(orgId(), agentId, memoryId, false));
    }

    public record Forgotten(int removed) {}

    /**
     * Removes every note, pinned ones too: the same permission as removing one note, since this is
     * that many times over. Episodes (the record of what happened) are kept.
     */
    // @find: DELETE /api/memory/agents/{agentId}/memories, forgetAll, endpoint, agent memory
    @DeleteMapping
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Forget everything this agent remembers")
    public Forgotten forgetAll(@PathVariable UUID agentId) {
        return new Forgotten(memories.forgetAll(orgId(), agentId));
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }

    private static String actorId() {
        Actor actor = RequestContext.requireActor();
        return actor.humanId() != null ? actor.humanId() : actor.id();
    }
}
