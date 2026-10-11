// @find: browser screenshot api, GET /api/browser/shots/{id}, page screenshot image, live view image, run trace screenshot, BrowserShotController
// @what: REST endpoint returning one stored web browser screenshot as a JPEG image.
// @flow: Called by the web TraceStep and Chat live view; reads BrowserShots
package os.aiworkforce.orchestrator.browser;

import java.time.Duration;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** The screenshots the web browser took during runs, with the same checks as a run's voice clips. */
@RestController
@RequestMapping("/api/browser")
@Tag(name = "Browser")
public class BrowserShotController {

    private final BrowserShots shots;

    public BrowserShotController(BrowserShots shots) {
        this.shots = shots;
    }

    // @find: get browser screenshot, GET /api/browser/shots/{shotId}
    @GetMapping("/shots/{shotId}")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "One screenshot the web browser took during a run")
    public ResponseEntity<byte[]> shot(@PathVariable UUID shotId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        BrowserShot shot = shots.findByIdAndOrgId(shotId, orgId)
                .orElseThrow(() -> ApiException.notFound("browser screenshot", shotId));
        // Never changes once written, but stays private to the workspace.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(MediaType.IMAGE_JPEG)
                .body(shot.getJpeg());
    }
}
