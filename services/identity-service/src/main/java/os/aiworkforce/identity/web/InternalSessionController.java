// @find: internal sessions, which sessions are signed in, live sessions, signed out device, push notifications sign out, POST /internal/sessions/live, InternalSessionController
// @what: Service-to-service endpoint answering which sign-in sessions still belong to a signed-in device.
// @flow: Called by the orchestrator (push.IdentitySessionLiveness) with a service token before it sends a push notification; reads Sessions.findSignedInIds
package os.aiworkforce.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.repository.Sessions;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Whether the devices behind some sessions are still signed in.
 *
 * <p>The orchestrator sends push notifications to a person's phone or laptop, and remembers which
 * sign-in session saved each one. Signing a device out - on the device, from another device's
 * Profile page, everywhere at once, or by changing or resetting the password - ends that session's
 * family here, and nowhere else. So before it sends, the orchestrator asks: the sessions whose
 * family still has a live link, of an active account, are answered; every other one is left out,
 * and that device is not told about the workspace's work again.
 *
 * <p>Reachable only with a service token, never a person's own, and it reads only: an answer names
 * session ids the caller already holds, nothing more.
 */
@RestController
@RequestMapping("/internal/sessions")
@Tag(name = "Internal")
public class InternalSessionController {

    /** The most sessions asked about in one call. */
    public static final int MAX_SESSIONS = 500;

    private final Sessions sessions;

    /** Wires the session repository. */
    public InternalSessionController(Sessions sessions) {
        this.sessions = sessions;
    }

    /** Request body for {@link #live}: the session ids (an access token's {@code sid}) to check. */
    public record LiveRequest(@NotNull @Size(max = MAX_SESSIONS) List<@NotNull UUID> sessionIds) {}

    /** Response body for {@link #live}: the ones among those asked about that are still signed in. */
    public record LiveResponse(List<UUID> live) {}

    // @find: which sessions are still signed in, POST /internal/sessions/live
    /**
     * The sessions among those asked about whose device is still signed in.
     *
     * <p>Route: {@code POST /internal/sessions/live}; service token only. Reads only. An empty list
     * asks nothing and is answered with nothing.
     *
     * @throws ApiException permission denied for a person's token or an API key
     */
    @PostMapping("/live")
    @Operation(summary = "Internal: which sign-in sessions still belong to a signed-in device")
    @Transactional(readOnly = true)
    public LiveResponse live(@Valid @RequestBody LiveRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        if (request.sessionIds().isEmpty()) {
            return new LiveResponse(List.of());
        }
        return new LiveResponse(List.copyOf(sessions.findSignedInIds(request.sessionIds(), Instant.now())));
    }
}
