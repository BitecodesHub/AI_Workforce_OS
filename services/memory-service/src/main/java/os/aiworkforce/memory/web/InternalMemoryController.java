// @find: internal memory, POST /internal/memory/episodes, record episode, write episode, service token only, episode kind validation
// @what: Internal endpoint where services record an episode; refuses a person's own token.
// @flow: Calls EpisodicMemory.record
package os.aiworkforce.memory.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.service.EpisodicMemory;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Recording an episode, for a sibling service.
 *
 * <p>Only a service token reaches this: a person's token or a machine key is refused whatever it
 * carries, and the path is never routed through the gateway. An episode is later read back to an
 * agent as something that happened, so one a person could write would be a way to tell an agent
 * what to do without anyone approving it. The workspace is the one the service token names.
 *
 * <p>Everything is checked before it is saved. An unknown kind would otherwise reach the
 * database's own constraint and come back as a server fault, and an unbounded summary or detail
 * would be carried into every prompt that recalls it.
 *
 * <p>Not yet checked: that {@code agentId} and {@code runId} belong to the workspace on the token.
 * No service writes episodes today; when the orchestrator starts to, this should confirm both
 * with it (an internal lookup) before saving. Until then only a service token for the workspace
 * itself can write, so nothing reaches another workspace's memory.
 */
@RestController
@RequestMapping("/internal/memory")
@Tag(name = "Memory")
public class InternalMemoryController {

    /** Long enough for a paragraph; a recalled memory has to fit in a prompt beside others. */
    static final int MAX_SUMMARY_CHARS = 2_000;

    /** The structured detail, measured as the JSON that is stored. */
    static final int MAX_DETAIL_BYTES = 16 * 1024;

    private static final String KIND_LIST =
            Episode.KINDS.stream().sorted().collect(Collectors.joining(", "));

    private final EpisodicMemory memory;
    private final ObjectMapper json;

    public InternalMemoryController(EpisodicMemory memory, ObjectMapper json) {
        this.memory = memory;
        this.json = json;
    }

    /**
     * @param agentId the agent the episode belongs to
     * @param runId the run it happened in, when there was one
     * @param importance 1 to 10, higher kept longer; 5 when not given
     */
    public record EpisodeRequest(
            UUID agentId,
            UUID runId,
            String kind,
            String summary,
            Integer importance,
            Map<String, Object> detail) {}

    // @find: POST /internal/memory/episodes, record, endpoint, internal memory
    @PostMapping("/episodes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Internal: record what an agent observed, decided or did")
    public MemoryController.EpisodeView record(@RequestBody EpisodeRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());

        // Checked here rather than with bean validation, so a caller that may not write at all
        // learns that first, not which field it got wrong.
        if (request.agentId() == null) {
            throw ApiException.validation("agentId", "must not be empty");
        }
        String kind = request.kind() == null ? "" : request.kind().strip();
        if (!Episode.KINDS.contains(kind)) {
            throw ApiException.validation("kind", "must be one of " + KIND_LIST);
        }
        String summary = request.summary() == null ? "" : request.summary().strip();
        if (summary.isEmpty()) {
            throw ApiException.validation("summary", "must not be blank");
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            throw ApiException.validation("summary", "must be at most " + MAX_SUMMARY_CHARS + " characters");
        }
        Map<String, Object> detail = request.detail() == null ? Map.of() : request.detail();
        if (sizeOf(detail) > MAX_DETAIL_BYTES) {
            throw ApiException.validation("detail", "must be at most 16 KB");
        }

        Episode episode = memory.record(
                orgId,
                request.agentId(),
                request.runId(),
                kind,
                summary,
                request.importance() == null ? 5 : request.importance(),
                detail);
        return MemoryController.EpisodeView.of(episode);
    }

    private int sizeOf(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException notJson) {
            throw ApiException.validation("detail", "must be a JSON object");
        }
    }
}
