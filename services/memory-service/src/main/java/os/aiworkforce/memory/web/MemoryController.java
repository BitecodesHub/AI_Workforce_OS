package os.aiworkforce.memory.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.service.EpisodicMemory;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Reading what agents have remembered.
 *
 * <p>The workspace always comes from the caller's token, never from the request. An earlier
 * version took it as a query parameter and read whichever workspace was named, which let anyone
 * signed in to one workspace read another's memories. Writing is not offered here at all: agents
 * record episodes through {@link InternalMemoryController}, because a memory a person could plant
 * would later be read back to an agent as if it had happened.
 */
@RestController
@RequestMapping("/api/memory")
@Tag(name = "Memory")
public class MemoryController {

    private final EpisodicMemory memory;

    public MemoryController(EpisodicMemory memory) {
        this.memory = memory;
    }

    /** One episode as the API shows it, without the storage details of the entity. */
    public record EpisodeView(
            UUID id,
            UUID agentId,
            UUID runId,
            UUID taskId,
            String kind,
            String summary,
            Map<String, Object> detail,
            int importance,
            List<UUID> supersedes,
            boolean compacted,
            Instant occurredAt) {

        static EpisodeView of(Episode episode) {
            return new EpisodeView(
                    episode.getId(),
                    episode.getAgentId(),
                    episode.getRunId(),
                    episode.getTaskId(),
                    episode.getKind(),
                    episode.getSummary(),
                    episode.getDetail(),
                    episode.getImportance(),
                    episode.getSupersedes(),
                    episode.isCompacted(),
                    episode.getOccurredAt());
        }
    }

    @GetMapping("/episodes")
    @RequiresPermission(Permission.Codes.MEMORY_READ)
    @Operation(summary = "What one agent remembers, most important first, or matching a search")
    public List<EpisodeView> getEpisodes(
            @RequestParam UUID agentId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") int limit) {
        return memory.recall(orgId(), agentId, query, limit).stream()
                .map(EpisodeView::of)
                .toList();
    }

    @GetMapping("/episodes/run/{runId}")
    @RequiresPermission(Permission.Codes.MEMORY_READ)
    @Operation(summary = "What was remembered during one run, in order")
    public List<EpisodeView> getEpisodesForRun(@PathVariable UUID runId) {
        return memory.forRun(orgId(), runId).stream().map(EpisodeView::of).toList();
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
