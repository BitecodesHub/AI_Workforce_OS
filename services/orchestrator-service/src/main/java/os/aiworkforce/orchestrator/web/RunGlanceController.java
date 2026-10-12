// @find: run glance, latest step of a run, what an agent is doing now, Working now tile, GET /api/runs/{runId}/glance
// @what: A light read of one run for a Working now tile: when it started, its latest step's kind and the few facts a caption needs, and whether it has used the web browser.
// @flow: Called by the Working now tiles on the Agents page (web useRunGlance); reads Runs and RunSteps
package os.aiworkforce.orchestrator.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * A run at a glance. The full trace ({@code GET /api/runs/{runId}/steps}) carries every step with
 * its page text and results; a tile that polls every few seconds needs only the latest step's kind
 * and a few names, so this answers those in two small queries.
 */
@RestController
@Tag(name = "Runs")
public class RunGlanceController {

    /** The only facts of a step's detail a caption reads; everything else (page text, results) stays out. */
    static final List<String> CAPTION_KEYS = List.of("type", "tool", "toolCalls", "status");

    private final Runs runs;
    private final RunSteps steps;

    /** Who may read which conversation's work; absent only where a test builds the controller by hand. */
    @Autowired(required = false)
    private ConversationAccess access;

    /** Creates the controller over the run and step repositories. */
    public RunGlanceController(Runs runs, RunSteps steps) {
        this.runs = runs;
        this.steps = steps;
    }

    /**
     * One run at a glance.
     *
     * @param startedAt when the run started
     * @param kind the latest step's kind, or null before the first step
     * @param detail the latest step's type, tool, planned tool calls and status, when present
     * @param occurredAt when the latest step happened, or null
     * @param browsing whether the run has used the web browser
     */
    public record RunGlance(Instant startedAt, String kind, Map<String, Object> detail, Instant occurredAt, boolean browsing) {}

    // @find: run glance, GET /api/runs/{runId}/glance
    /**
     * The run's start, its latest step (only the facts a caption needs) and whether it has used the
     * web browser. Route {@code GET /api/runs/{runId}/glance}; requires {@code run:read} and the same
     * visibility as the trace, so a run behind someone else's private conversation is "not found".
     * Two indexed reads; never cached.
     *
     * @throws ApiException not found when the run is absent, in another workspace or hidden
     */
    @GetMapping("/api/runs/{runId}/glance")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "What a run is doing now, in a few words' worth of facts, for a small live tile")
    public ResponseEntity<RunGlance> glance(@PathVariable UUID runId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Run run = runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        if (access != null && run.getTaskId() != null
                && access.hiddenTaskIds(orgId, RequestContext.requireActor()).contains(run.getTaskId())) {
            throw ApiException.notFound("run", runId);
        }
        RunStep latest = steps.findFirstByRunIdOrderByPositionDesc(runId).orElse(null);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (latest != null && latest.getDetail() != null) {
            for (String key : CAPTION_KEYS) {
                Object value = latest.getDetail().get(key);
                if (value != null) {
                    detail.put(key, value);
                }
            }
        }
        RunGlance glance = new RunGlance(
                run.getStartedAt(),
                latest == null ? null : latest.getKind(),
                detail,
                latest == null ? null : latest.getOccurredAt(),
                steps.usedBrowser(runId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(glance);
    }
}
