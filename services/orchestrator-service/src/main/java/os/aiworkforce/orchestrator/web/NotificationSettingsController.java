// @find: notification settings, webhook url, notify on approval, test webhook, /api/orchestrator/notification-settings, Notifications settings page
// @what: REST endpoints to read, change and test the workspace's outbound webhook notifications.
// @flow: Delegates to NotificationService
package os.aiworkforce.orchestrator.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.NotificationService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Where a workspace is told, outside the app, that an approval is waiting or a schedule paused.
 *
 * <p>One webhook address per workspace, optionally signed with a secret that is stored encrypted
 * and never returned: the page can say a secret is set, and replace it, but nobody can read it
 * back. Changing any of this needs {@code workspace:update}, the same permission as the rest of
 * the workspace's settings.
 */
@RestController
@RequestMapping("/api/orchestrator/notification-settings")
@Tag(name = "Notifications")
public class NotificationSettingsController {

    private final NotificationService notifications;
    private final AuditClient audit;

    public NotificationSettingsController(NotificationService notifications, AuditClient audit) {
        this.notifications = notifications;
        this.audit = audit;
    }

    /** An event a workspace can choose to hear, and what it means in words. */
    public record EventOption(String id, String label) {}

    /**
     * @param webhookUrl where messages go; empty when notifications are off
     * @param hasSecret whether messages are signed; the secret itself is never returned
     * @param events the events being sent, from {@code availableEvents}
     */
    public record SettingsView(
            String webhookUrl, boolean hasSecret, List<String> events, List<EventOption> availableEvents) {}

    /**
     * @param webhookUrl where messages go; empty turns notifications off, and removes the secret
     * @param secret a new secret to sign with: absent keeps the stored one, empty removes it
     * @param events the events to send
     */
    public record UpdateRequest(
            @Size(max = 2_000) String webhookUrl,
            @Size(max = NotificationService.SECRET_MAX) String secret,
            List<String> events) {}

    public record TestView(boolean delivered, Integer statusCode, String message) {}

    private static final List<EventOption> AVAILABLE = List.of(
            new EventOption(NotificationService.APPROVAL_RAISED, "An agent is waiting for an approval"),
            new EventOption(NotificationService.APPROVAL_EXPIRED, "An approval expired before anybody decided it"),
            new EventOption(NotificationService.SCHEDULE_PAUSED, "A schedule paused itself after repeated failures"));

    // @find: get notification settings, GET /api/orchestrator/notification-settings
    @GetMapping
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Where this workspace is told that something is waiting")
    public SettingsView get() {
        return view(notifications.settings(orgId()));
    }

    // @find: update notification settings, PUT /api/orchestrator/notification-settings
    @PutMapping
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Set the webhook, its secret and the events it hears")
    public SettingsView update(@Valid @RequestBody UpdateRequest request) {
        UUID orgId = orgId();
        Actor actor = RequestContext.requireActor();
        NotificationService.SecretChange secret =
                notifications.update(orgId, request.webhookUrl(), request.secret(), request.events(), actor.id());
        NotificationService.Settings saved = notifications.settings(orgId);

        // What changed, never the address (it often carries its own token) or the secret.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("webhookConfigured", !saved.webhookUrl().isBlank());
        detail.put("secret", secret.name().toLowerCase());
        detail.put("events", saved.events());
        audit.record(orgId, actor, "notifications.update", "workspace", orgId.toString(), "succeeded", detail);
        return view(saved);
    }

    // @find: send test notification, POST .../notification-settings/test
    @PostMapping("/test")
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Send a test message to the saved webhook and say how it went")
    public TestView test() {
        NotificationService.TestResult result = notifications.sendTest(orgId());
        return new TestView(result.delivered(), result.statusCode(), result.message());
    }

    private static SettingsView view(NotificationService.Settings settings) {
        return new SettingsView(settings.webhookUrl(), settings.hasSecret(), settings.events(), AVAILABLE);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
