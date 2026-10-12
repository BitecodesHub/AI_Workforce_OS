// @find: takeover browser adapter lookup, browser runner client for takeover, BrowserAdapter takeover, hold page, GatewayTakeoverBrowser
// @what: The TakeoverBrowser backed by the built-in web browser connector (mcp-core BrowserAdapter) found through the tool gateway.
// @flow: Used by TakeoverService; calls BrowserAdapter.live, hold, takeover and personActed, the first three of which call infra/browser-runner
package os.aiworkforce.orchestrator.takeover;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import os.aiworkforce.mcp.builtin.BrowserAdapter;
import os.aiworkforce.mcp.policy.ToolGateway;

/**
 * Finds the web browser among the tool servers on every call, the way the live view does, so a
 * server without the browser simply answers that nothing is open.
 */
@Component
public class GatewayTakeoverBrowser implements TakeoverBrowser {

    /** Shown when the browser is not one of this server's tools. */
    static final String NO_BROWSER = "The web browser is not available on this server.";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GatewayTakeoverBrowser.class);

    private final ToolGateway gateway;

    /** Creates the browser with the tool gateway it finds the browser through. */
    public GatewayTakeoverBrowser(ToolGateway gateway) {
        this.gateway = gateway;
    }

    /** The built-in browser, when this server has one. */
    private Optional<BrowserAdapter> browser() {
        return gateway.adapter("browser")
                .filter(BrowserAdapter.class::isInstance)
                .map(BrowserAdapter.class::cast);
    }

    /** True when the runner's live view of the run answers with a page; at most three seconds. */
    @Override
    public boolean hasPage(UUID runId) {
        return browser()
                .map(adapter -> adapter.live(runId.toString()).block(Duration.ofSeconds(3)))
                .map(view -> !view.path("url").asText("").isBlank() || !view.path("frame").asText("").isBlank())
                .orElse(false);
    }

    /** Holds or lets go of the run's page; waits at most five seconds and ignores a failure. */
    @Override
    public void hold(UUID runId, boolean on) {
        browser().ifPresent(adapter -> adapter.hold(runId.toString(), on).block(Duration.ofSeconds(6)));
    }

    /** Sends the person's action to the runner and maps its answer; at most forty seconds. */
    @Override
    public Answer act(UUID runId, String type, Map<String, Object> body) {
        Optional<BrowserAdapter> adapter = browser();
        if (adapter.isEmpty()) {
            return Answer.refused(NO_BROWSER);
        }
        ObjectNode request = JSON.valueToTree(body);
        BrowserAdapter.TakeoverAnswer answer =
                adapter.get().takeover(runId.toString(), type, request).block(Duration.ofSeconds(40));
        if (answer == null) {
            return Answer.refused("The web browser did not answer, so nothing was done. Try again in a minute.");
        }
        return new Answer(answer.ok(), answer.problem(), answer.url(), answer.title(), answer.frame(), answer.field());
    }

    /**
     * Tells this server's browser connector what the person did, with the page's full address read
     * from the runner's live view (at most three seconds; the address is kept in memory only, never
     * stored). A browser that is not set up, or a live view that does not answer, leaves the
     * remembered address as it was. Never throws.
     */
    @Override
    public void personActed(UUID runId, List<String> fields) {
        try {
            browser().ifPresent(adapter -> {
                var view = adapter.live(runId.toString()).block(Duration.ofSeconds(3));
                String url = view == null ? null : view.path("url").asText("");
                adapter.personActed(runId.toString(), fields, url);
            });
        } catch (RuntimeException e) {
            log.warn("Run {}: the browser could not be told that a person acted on its page: {}", runId, e.getMessage());
        }
    }
}
