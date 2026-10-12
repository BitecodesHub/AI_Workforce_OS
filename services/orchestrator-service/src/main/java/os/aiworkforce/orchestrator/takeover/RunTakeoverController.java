// @find: takeover api, take over browser, hand back, GET /api/runs/{runId}/takeover, POST /api/runs/{runId}/takeover, POST /api/runs/{runId}/takeover/actions, POST /api/runs/{runId}/takeover/release, Agent desk, Take over button
// @what: REST endpoints for a person to see, take, use and hand back control of a run's web browser.
// @flow: Called by the Agent desk on the Agents and agent pages (web AgentDesk); delegates to TakeoverService
package os.aiworkforce.orchestrator.takeover;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Take over a run's web browser. Every route needs {@code run:read} and the same visibility as the
 * run's trace; taking control further needs {@code run:cancel} or being the person who started the
 * run, which {@link TakeoverService} checks, so the rule holds however the endpoint is reached.
 */
@RestController
@RequestMapping("/api/runs/{runId}/takeover")
@Tag(name = "Runs")
public class RunTakeoverController {

    private final TakeoverService takeovers;

    /** Creates the controller over the takeover service. */
    public RunTakeoverController(TakeoverService takeovers) {
        this.takeovers = takeovers;
    }

    /** The body of a hand back: an optional note for the agent, at most 500 characters kept. */
    public record ReleaseRequest(String note) {}

    // @find: who has control of the browser, GET /api/runs/{runId}/takeover
    /** Where the run's takeover stands for the caller; never cached. Route {@code GET /api/runs/{runId}/takeover}. */
    @GetMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Whether a person has control of the run's web browser, and whether the caller may take it")
    public ResponseEntity<TakeoverService.TakeoverView> view(@PathVariable UUID runId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(takeovers.view(orgId(), runId, RequestContext.requireActor()));
    }

    // @find: take over browser, POST /api/runs/{runId}/takeover
    /**
     * Asks for control of the run's web browser for the caller. The answer is "starting": the agent
     * finishes the step it is on, then stops and waits, and the caller has control ("active").
     * Route {@code POST /api/runs/{runId}/takeover}.
     */
    @PostMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Ask for control of the run's web browser; the agent stops after its current step and waits until it is handed back")
    public TakeoverService.TakeoverView start(@PathVariable UUID runId) {
        return takeovers.start(orgId(), runId, RequestContext.requireActor());
    }

    // @find: takeover action, click type key scroll navigate, POST /api/runs/{runId}/takeover/actions
    /**
     * Does one action on the page for the person holding control and answers a fresh frame. Route
     * {@code POST /api/runs/{runId}/takeover/actions} with {@code type} click {x, y, width, height},
     * type {text}, key {key}, scroll {dy} or navigate {url}; never cached.
     */
    @PostMapping("/actions")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Click, type, press a key, scroll or open an address on the page you have control of")
    public ResponseEntity<TakeoverService.ActionResult> act(
            @PathVariable UUID runId, @RequestBody TakeoverActions.ActionRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(takeovers.act(orgId(), runId, RequestContext.requireActor(), request));
    }

    // @find: hand back browser to agent, POST /api/runs/{runId}/takeover/release
    /** Hands control back to the agent with an optional note. Route {@code POST /api/runs/{runId}/takeover/release}. */
    @PostMapping("/release")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Hand control of the web browser back to the agent, with an optional note for it")
    public TakeoverService.TakeoverView release(
            @PathVariable UUID runId, @RequestBody(required = false) ReleaseRequest request) {
        return takeovers.release(orgId(), runId, RequestContext.requireActor(), request == null ? null : request.note());
    }

    /** The workspace id of the current request. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
