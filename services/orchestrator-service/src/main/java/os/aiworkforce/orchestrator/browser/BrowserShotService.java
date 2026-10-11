// @find: browser screenshot service, save screenshot after browser step, capture page screenshot, live view, BrowserShotService
// @what: Saves the screenshot a web browser tool call returned, so the run's trace can show it.
// @flow: Called by AgentRunner after a browser tool call; reads BrowserAdapter's result metadata; writes BrowserShots
package os.aiworkforce.orchestrator.browser;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.mcp.builtin.BrowserAdapter;
import os.aiworkforce.mcp.model.ToolResult;

/**
 * Turns the screenshot in a browser tool result into a stored picture.
 *
 * <p>Swallows everything that can go wrong, the way {@code VoiceClipService} does: a run that read
 * a page must not fail because its picture could not be kept; the step simply shows no image.
 */
@Service
public class BrowserShotService {

    private static final Logger log = LoggerFactory.getLogger(BrowserShotService.class);

    /** A screenshot larger than this is not kept: the runner sends about 100 KB at 1280x800. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private final BrowserShots shots;

    /** Creates the service with the repository it writes screenshots to. */
    public BrowserShotService(BrowserShots shots) {
        this.shots = shots;
    }

    // @find: store browser screenshot from tool result
    /**
     * @return the saved screenshot's id, or empty when the result carried none or it could not be kept
     */
    public Optional<UUID> save(UUID orgId, UUID runId, String toolCallId, ToolResult result) {
        String encoded = result.metadata().get(BrowserAdapter.SCREENSHOT);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] jpeg = Base64.getDecoder().decode(encoded);
            if (jpeg.length == 0 || jpeg.length > MAX_BYTES) {
                return Optional.empty();
            }
            BrowserShot shot = BrowserShot.of(
                    orgId,
                    runId,
                    toolCallId,
                    result.metadata().getOrDefault(BrowserAdapter.PAGE_URL, ""),
                    result.metadata().getOrDefault(BrowserAdapter.PAGE_TITLE, ""),
                    jpeg);
            shots.save(shot);
            return Optional.of(shot.getId());
        } catch (RuntimeException e) {
            log.warn("The browser screenshot for run {} could not be kept: {}", runId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
