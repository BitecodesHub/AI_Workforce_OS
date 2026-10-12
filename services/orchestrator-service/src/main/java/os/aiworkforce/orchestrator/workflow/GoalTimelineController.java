// @find: goal timeline api, GET /api/goals/{goalId}/timeline, replay a goal, live workflow view, time-lapse, when tasks started and finished, GoalTimelineController
// @what: REST endpoint that returns one goal's timeline (tasks, runs, waits and the workflow canvas behind it) for the live view and the replay.
// @flow: Called by the workflow run view in the console; checks the goal is visible (private conversations stay private) and calls GoalTimelineService
package os.aiworkforce.orchestrator.workflow;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * One goal's timeline, for any goal - started from a workflow, from chat, by a schedule or by
 * hand.
 *
 * <p>Needs {@code task:read}, the same as reading the goal itself, and follows the same privacy
 * rule as the goals endpoint: a goal started from a private conversation the caller is not part of
 * does not exist for them. The timeline carries timing and status only, never the work's content.
 */
@RestController
@RequestMapping("/api/goals")
@Tag(name = "Goals")
public class GoalTimelineController {

    private final Goals goals;
    private final GoalTimelineService service;

    /** Who may read which conversation; absent only where a test builds this by hand. */
    @Autowired(required = false)
    private ConversationAccess access;

    /** Creates the controller with the goal repository and the timeline service. */
    public GoalTimelineController(Goals goals, GoalTimelineService service) {
        this.goals = goals;
        this.service = service;
    }

    /** Sets the conversation access check; for tests that build the controller by hand. */
    void setAccess(ConversationAccess access) {
        this.access = access;
    }

    // @find: goal timeline, GET /api/goals/{goalId}/timeline
    /** The goal's tasks, runs and waits with their timestamps, and the workflow canvas it ran, if any. */
    @GetMapping("/{goalId}/timeline")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "One goal's timeline: when each task started, waited, finished and handed over")
    public GoalTimelineService.Timeline timeline(@PathVariable UUID goalId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Goal goal = goals.findByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId));
        if (access != null && access.hidden(orgId, RequestContext.requireActor(), goal.getConversationId())) {
            throw ApiException.notFound("goal", goalId);
        }
        return service.timeline(orgId, goal);
    }
}
