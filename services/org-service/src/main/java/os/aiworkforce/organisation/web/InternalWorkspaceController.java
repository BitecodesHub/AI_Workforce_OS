package os.aiworkforce.organisation.web;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * A workspace's name and timezone, for a sibling service.
 *
 * <p>The orchestrator needs the timezone to fire schedules at the right hour, and identity needs
 * the name to list the workspaces a person belongs to. Both ask with a service token that may
 * name no workspace at all - a scheduled run has none - so this cannot be scoped to the token's
 * workspace the way the public read is. It is scoped to services instead: a person's token or a
 * machine key is refused whatever it carries, and the path is never routed through the gateway.
 */
@RestController
@RequestMapping("/internal/workspaces")
@Tag(name = "Workspaces")
public class InternalWorkspaceController {

    private final Organisations organisations;

    public InternalWorkspaceController(Organisations organisations) {
        this.organisations = organisations;
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "Internal: one workspace, for a sibling service")
    public WorkspaceController.WorkspaceView get(@PathVariable UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        return WorkspaceController.toView(
                organisations.findById(workspaceId).orElseThrow(() -> ApiException.notFound("workspace", workspaceId)));
    }
}
