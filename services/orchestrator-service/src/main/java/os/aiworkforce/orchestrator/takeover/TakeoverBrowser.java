// @find: takeover browser port, person controls run browser, has page open, hold page, act on page, TakeoverBrowser
// @what: What the takeover service needs from the web browser: whether a run has a page open, holding that page, doing a person's action on it, the page's address now, and telling the run's own server what the person did.
// @flow: Implemented by GatewayTakeoverBrowser over mcp-core BrowserAdapter; used by TakeoverService
package os.aiworkforce.orchestrator.takeover;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The web browser as the takeover service sees it. An interface, so the service is tested without
 * a browser runner and the runner's client stays the only code that speaks its protocol.
 */
public interface TakeoverBrowser {

    /** What a person's action produced: done or refused with a sentence, the page afterwards, and the field label typed into. */
    record Answer(boolean ok, String problem, String url, String title, String frame, String field) {

        /** An action that was not done, with the sentence that says why. */
        public static Answer refused(String problem) {
            return new Answer(false, problem, "", "", "", "");
        }
    }

    /** Whether the run has a page open in the browser right now. False when the browser is not set up or does not answer. */
    boolean hasPage(UUID runId);

    /** Asks the browser to keep the run's page open while a person holds it, or to stop. Never throws. */
    void hold(UUID runId, boolean on);

    /**
     * Does one checked person action on the run's page ({@code click}, {@code type}, {@code key},
     * {@code scroll} or {@code navigate}). Never throws; a failure is a refused answer.
     */
    Answer act(UUID runId, String type, Map<String, Object> body);

    /**
     * The full address of the page the run's browser shows now, read from its live view. A person's
     * last action can start a page load that ends after it answered (pressing Enter in a search box
     * answered before the results page opened, 13 Oct 2026), so what the agent is told about "the
     * page now" is read here when control comes back. Empty when the browser is not set up, has no
     * page or does not answer in a few seconds. Never throws.
     */
    default Optional<String> pageAddress(UUID runId) {
        return Optional.empty();
    }

    /**
     * Tells the browser on the server driving the run that a person acted on its page while they had
     * control: the labels of the fields they typed into (never what they typed). The browser then
     * stops treating the agent's earlier typing as what the page holds, so an approval of the agent's
     * next click or submit says a person had control. Called by the run's own loop when control comes
     * back. Never throws.
     */
    void personActed(UUID runId, List<String> fields);
}
