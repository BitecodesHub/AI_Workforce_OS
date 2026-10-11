// @find: browser screenshot entity, web browser screenshot, page screenshot, live view image, browser_shots table, BrowserShot
// @what: JPA entity for one screenshot the built-in web browser took during a run.
// @flow: Stored in browser_shots by BrowserShotService; served by BrowserShotController
package os.aiworkforce.orchestrator.browser;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One screenshot of the page the web browser had open after a step, written once and never edited,
 * modelled directly the way {@code VoiceClip} is.
 */
@Entity
@Table(name = "browser_shots")
public class BrowserShot {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(nullable = false, columnDefinition = "text")
    private String url = "";

    @Column(nullable = false, columnDefinition = "text")
    private String title = "";

    @Column(nullable = false)
    private byte[] jpeg;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Builds a new screenshot row; a missing page address or title is stored as an empty string and the identifier and timestamp are assigned automatically. */
    public static BrowserShot of(UUID orgId, UUID runId, String toolCallId, String url, String title, byte[] jpeg) {
        BrowserShot shot = new BrowserShot();
        shot.orgId = orgId;
        shot.runId = runId;
        shot.toolCallId = toolCallId;
        shot.url = url == null ? "" : url;
        shot.title = title == null ? "" : title;
        shot.jpeg = jpeg;
        return shot;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public byte[] getJpeg() {
        return jpeg;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
