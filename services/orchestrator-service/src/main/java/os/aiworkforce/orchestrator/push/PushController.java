// @find: push api, /api/push, vapid public key endpoint, push subscriptions endpoint, subscribe device, unsubscribe device, send test notification, push notifications on this device, PushController
// @what: REST endpoints for a person's own push notifications: the server's public key, their devices, turning a device on or off, and the test.
// @flow: Called by the web app's push settings (web/src/lib/push.ts); delegates to PushService; audits device changes through AuditClient
package os.aiworkforce.orchestrator.push;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * A person's own push notifications.
 *
 * <p>Every member may use these, whatever their role, because they concern only the person's own
 * devices: an employee is asked questions by agents too. They need a person's own session - an API
 * key has no device to notify - and every read and removal is scoped to that person in that
 * workspace. Who is then told about what is decided elsewhere, by the permissions the person holds
 * when something is waiting ({@link PushRecipients}).
 */
@RestController
@RequestMapping("/api/push")
@Tag(name = "Push notifications")
public class PushController {

    /** Whether push is offered here, and the key a browser subscribes with (null when it is not). */
    public record KeyView(boolean enabled, String publicKey) {}

    /** The two keys a browser's subscription carries, as PushSubscription.toJSON() names them. */
    public record SubscriptionKeys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 200) String auth) {}

    /**
     * A browser's subscription, exactly as {@code PushSubscription.toJSON()} gives it; {@code
     * expirationTime} is accepted and ignored.
     */
    public record SubscribeRequest(
            @NotBlank @Size(max = PushEndpoints.MAX_LENGTH) String endpoint,
            Long expirationTime,
            @NotNull @Valid SubscriptionKeys keys) {}

    /** Which subscription to remove: the browser's endpoint. */
    public record UnsubscribeRequest(@NotBlank @Size(max = PushEndpoints.MAX_LENGTH) String endpoint) {}

    /**
     * One of the person's devices. The endpoint is the person's own, so their browser can tell
     * which row is itself; the keys are never returned.
     */
    public record DeviceView(
            UUID id, String endpoint, String label, Instant createdAt, Instant lastSuccessAt, int failureCount) {}

    /** How the test went. */
    public record TestView(int devices, int delivered, int removed, String message) {}

    private final PushService push;
    private final AuditClient audit;

    /** Wires the push service and the audit client. */
    public PushController(PushService push, AuditClient audit) {
        this.push = push;
        this.audit = audit;
    }

    // @find: GET /api/push/vapid-public-key
    /** Route {@code GET /api/push/vapid-public-key}; any signed-in member. Says whether push is offered and with which key. */
    @GetMapping("/vapid-public-key")
    @Operation(summary = "Whether push notifications are available, and the key a browser subscribes with")
    public KeyView publicKey() {
        person();
        PushService.Status status = push.status();
        return new KeyView(status.enabled(), status.publicKey());
    }

    // @find: GET /api/push/subscriptions, my devices
    /** Route {@code GET /api/push/subscriptions}; the caller's own devices in this workspace, newest first. */
    @GetMapping("/subscriptions")
    @Operation(summary = "My devices with push notifications turned on")
    public List<DeviceView> devices() {
        Actor actor = person();
        return push.devices(orgOf(actor), userOf(actor)).stream().map(PushController::view).toList();
    }

    // @find: POST /api/push/subscriptions, turn on push for this device
    /**
     * Route {@code POST /api/push/subscriptions}; saves this browser's subscription for the caller
     * (201). The same browser again refreshes its row. Audited without the endpoint or keys.
     */
    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Turn on push notifications for this browser")
    public DeviceView subscribe(
            @Valid @RequestBody SubscribeRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Actor actor = person();
        UUID orgId = orgOf(actor);
        PushSubscription saved = push.subscribe(
                orgId, userOf(actor), request.endpoint(), request.keys().p256dh(), request.keys().auth(), userAgent);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("device", saved.label());
        audit.record(orgId, actor, "push.subscribe", "push_subscription", saved.id().toString(), "succeeded", detail);
        return view(saved);
    }

    // @find: DELETE /api/push/subscriptions, turn off push for this device
    /**
     * Route {@code DELETE /api/push/subscriptions} with the endpoint in the body; removes the
     * caller's own subscription for that browser (204 whether or not there was one, so nothing is
     * learnt about anybody else's).
     */
    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn off push notifications for one of my browsers")
    public void unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        Actor actor = person();
        UUID orgId = orgOf(actor);
        push.unsubscribe(orgId, userOf(actor), request.endpoint())
                .ifPresent(id -> audit.record(
                        orgId, actor, "push.unsubscribe", "push_subscription", id.toString(), "succeeded", Map.of()));
    }

    // @find: POST /api/push/test, send a test notification
    /** Route {@code POST /api/push/test}; sends the test to every one of the caller's devices and says how it went. */
    @PostMapping("/test")
    @Operation(summary = "Send a test notification to my devices")
    public TestView test() {
        Actor actor = person();
        PushService.TestResult result = push.sendTest(orgOf(actor), userOf(actor));
        return new TestView(result.devices(), result.delivered(), result.removed(), result.message());
    }

    // The caller, who must be a person signed in to a workspace: an API key or a service has no device.
    private static Actor person() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() != Actor.Kind.USER || actor.orgId() == null || actor.orgId().isBlank()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Push notifications belong to a person signed in to a workspace.");
        }
        return actor;
    }

    // The caller's workspace as a UUID.
    private static UUID orgOf(Actor actor) {
        return UUID.fromString(actor.orgId());
    }

    // The caller's own id as a UUID.
    private static UUID userOf(Actor actor) {
        try {
            return UUID.fromString(actor.id());
        } catch (IllegalArgumentException notAPerson) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Push notifications belong to a person signed in to a workspace.");
        }
    }

    // One subscription as the settings page shows it.
    private static DeviceView view(PushSubscription subscription) {
        return new DeviceView(
                subscription.id(),
                subscription.endpoint(),
                subscription.label(),
                subscription.createdAt(),
                subscription.lastSuccessAt(),
                subscription.failureCount());
    }
}
