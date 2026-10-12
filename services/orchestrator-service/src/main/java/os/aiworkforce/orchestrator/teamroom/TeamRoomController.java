// @find: team room api, team room controller, live presence endpoint, hand-off feed endpoint, GET /api/orchestrator/team-room, GET /api/orchestrator/team-room/feed, Team room page, TeamRoomController
// @what: REST endpoints for the team room: every agent's live presence and the hand-off conversation, both needing run:read.
// @flow: Called by the Team room page (web/src/lib/teamRoomQueries.ts); delegates to TeamRoomService.
package os.aiworkforce.orchestrator.teamroom;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.FeedPage;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.TeamRoom;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The team room's two reads. Both sit under {@code /api/orchestrator}, which the gateway, the
 * development proxy and the launcher already route to this service, and both need {@code
 * run:read}: the same permission as the run pages and the Orchestrator board, whose work they show.
 * Answers are never cached, because the page polls them for what is happening now.
 */
@RestController
@RequestMapping("/api/orchestrator/team-room")
@Tag(name = "Team room")
public class TeamRoomController {

    private final TeamRoomService teamRoom;

    /** Builds the controller; used by Spring. */
    public TeamRoomController(TeamRoomService teamRoom) {
        this.teamRoom = teamRoom;
    }

    /**
     * Route {@code GET /api/orchestrator/team-room}; requires {@code run:read}.
     *
     * <p>Every agent that is not retired, in name order, with what it is doing now. Read only; see
     * {@link TeamRoomService#presence}.
     */
    // @find: team room presence, GET /api/orchestrator/team-room
    @GetMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Every agent and what it is doing right now")
    public ResponseEntity<TeamRoom> presence() {
        TeamRoom room = teamRoom.presence(orgId(), RequestContext.requireActor());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(room);
    }

    /**
     * Route {@code GET /api/orchestrator/team-room/feed}; requires {@code run:read}.
     *
     * <p>One page of the hand-off conversation, oldest first: the newest page by default, the
     * events after {@code after} (a page's {@code newestCursor}, which may carry the floor a backlog
     * must be read again from), or the events before {@code before}. {@code limit} is held between
     * 1 and {@value TeamRoomService#MAX_LIMIT}. Fails with a validation error for a cursor this
     * service did not write, or for both cursors at once. Read only; see {@link TeamRoomService#feed}.
     */
    // @find: team room feed, hand-off conversation, GET /api/orchestrator/team-room/feed
    @GetMapping("/feed")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "The conversation of who asked, started, handed over, waited and finished, oldest first")
    public ResponseEntity<FeedPage> feed(
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "" + TeamRoomService.DEFAULT_LIMIT) int limit) {
        FeedCursor.Poll afterCursor = FeedCursor.Poll.decode(after, "after");
        FeedCursor beforeCursor = FeedCursor.decode(before, "before");
        if (afterCursor != null && beforeCursor != null) {
            throw ApiException.validation("before", "cannot be used together with after");
        }
        int size = Math.clamp(limit, 1, TeamRoomService.MAX_LIMIT);
        FeedPage page = teamRoom.feed(orgId(), RequestContext.requireActor(), afterCursor, beforeCursor, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    /** The workspace of the current request. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
