package os.aiworkforce.orchestrator.schedule;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Schedules, as other services need them: today, only what happens when a person leaves.
 *
 * <p>A schedule fires as its owner, so once that person is removed from a workspace every later
 * run would still start in their name - an agent acting for somebody who no longer works there.
 * identity-service calls {@code owner-removed} once a removal commits, and every schedule that
 * person owned is paused until somebody else takes it on.
 *
 * <p>Internal only, and refused to a person's token or a machine key: pausing everyone's work in
 * somebody's name is a decision identity makes on the back of a removal, never one a caller can
 * ask for directly.
 */
@RestController
@RequestMapping("/internal/schedules")
@Tag(name = "Internal")
public class InternalScheduleController {

    private final ScheduleService service;

    public InternalScheduleController(ScheduleService service) {
        this.service = service;
    }

    public record OwnerRemovedRequest(@NotNull UUID orgId, @NotNull UUID userId) {}

    /** @param paused how many enabled schedules this paused */
    public record OwnerRemovedResponse(int paused) {}

    @PostMapping("/owner-removed")
    @Operation(summary = "Internal: pause every schedule that runs as a person who has left the workspace")
    public OwnerRemovedResponse ownerRemoved(@Valid @RequestBody OwnerRemovedRequest request) {
        Actor caller = RequestContext.requireActor();
        // Only the platform's own services (identity, on removing a member) may call this; an
        // agent's token cannot pause other people's schedules.
        if (caller.kind() != Actor.Kind.SYSTEM) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        // A service token minted for one workspace speaks for that workspace alone.
        if (caller.orgId() != null && !caller.orgId().equals(request.orgId().toString())) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "This service token belongs to a different workspace.");
        }
        return new OwnerRemovedResponse(service.pauseForRemovedOwner(request.orgId(), request.userId(), caller));
    }
}
