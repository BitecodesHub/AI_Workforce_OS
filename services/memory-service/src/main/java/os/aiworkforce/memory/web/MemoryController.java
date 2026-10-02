package os.aiworkforce.memory.web;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.service.EpisodicMemory;
import os.aiworkforce.platform.context.RequestContext;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final EpisodicMemory memory;

    public MemoryController(EpisodicMemory memory) {
        this.memory = memory;
    }

    @GetMapping("/episodes")
    public List<Episode> getEpisodes(
            @RequestParam UUID orgId,
            @RequestParam UUID agentId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") int limit) {
        RequestContext.requireOrgId();
        return memory.recall(orgId, agentId, query, limit);
    }

    @GetMapping("/episodes/run/{runId}")
    public List<Episode> getEpisodesForRun(
            @RequestParam UUID orgId,
            @PathVariable UUID runId) {
        RequestContext.requireOrgId();
        return memory.forRun(orgId, runId);
    }

    @PostMapping("/episodes")
    public Episode createEpisode(
            @RequestParam UUID orgId,
            @RequestParam UUID agentId,
            @RequestParam UUID runId,
            @RequestBody EpisodeRequest request) {
        RequestContext.requireOrgId();
        return memory.record(orgId, agentId, runId, request.kind(), request.summary(), 
                           request.importance(), request.detail());
    }

    public record EpisodeRequest(
            String kind,
            String summary,
            int importance,
            java.util.Map<String, Object> detail) {}
}
