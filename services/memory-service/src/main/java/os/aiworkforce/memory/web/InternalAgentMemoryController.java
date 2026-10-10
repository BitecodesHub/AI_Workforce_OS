// @find: internal agent memory, POST /internal/memory/agents/{agentId}/remember, POST /internal/memory/agents/{agentId}/recall, orchestrator remember recall, service token only
// @what: Internal endpoints the orchestrator uses to save and recall an agent's notes while it works.
// @flow: Calls AgentMemoryService; service token only, not routed by the gateway
package os.aiworkforce.memory.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.memory.domain.AgentMemory;
import os.aiworkforce.memory.service.AgentMemoryService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Remembering and recalling, for the orchestrator to do on an agent's behalf while it works.
 *
 * <p>Only a service token reaches this, never a person's token or a machine key, and it is not
 * routed through the gateway. The workspace is the one the service token names. A note is kept for
 * one agent and recalled only for that agent.
 */
@RestController
@RequestMapping("/internal/memory/agents/{agentId}")
@Tag(name = "Agent memory")
public class InternalAgentMemoryController {

    private final AgentMemoryService memories;

    public InternalAgentMemoryController(AgentMemoryService memories) {
        this.memories = memories;
    }

    public record RememberRequest(
            @Size(max = 20) String kind, @NotBlank @Size(max = AgentMemory.MAX_CONTENT) String content, UUID runId) {}

    /** @param created false when the same note was already there */
    public record Remembered(AgentMemoryController.MemoryView memory, boolean created) {}

    public record RecallRequest(@Size(max = 500) String query, Integer limit) {}

    public record Recalled(List<AgentMemoryController.MemoryView> memories) {}

    // @find: POST /internal/memory/agents/{agentId}/remember, remember, endpoint, internal agent memory
    @PostMapping("/remember")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Internal: keep a note for an agent")
    public Remembered remember(@PathVariable UUID agentId, @Valid @RequestBody RememberRequest request) {
        Actor actor = serviceOnly();
        AgentMemoryService.Saved saved = memories.add(
                orgId(), agentId, request.kind(), request.content(), "agent", actor.id(), request.runId());
        return new Remembered(AgentMemoryController.MemoryView.of(saved.memory()), saved.created());
    }

    // @find: POST /internal/memory/agents/{agentId}/recall, recall, endpoint, internal agent memory
    @PostMapping("/recall")
    @Operation(summary = "Internal: the notes that bear on a query, for one agent")
    public Recalled recall(@PathVariable UUID agentId, @Valid @RequestBody RecallRequest request) {
        serviceOnly();
        int limit = request.limit() == null ? 8 : request.limit();
        return new Recalled(memories.recall(orgId(), agentId, request.query(), limit).stream()
                .map(AgentMemoryController.MemoryView::of)
                .toList());
    }

    private static Actor serviceOnly() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        return actor;
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
