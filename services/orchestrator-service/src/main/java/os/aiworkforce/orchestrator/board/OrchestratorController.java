package os.aiworkforce.orchestrator.board;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** The live view of everything the workforce is doing, and the one button that stops it all. */
@RestController
@RequestMapping("/api/orchestrator")
@Tag(name = "Orchestrator")
public class OrchestratorController {

    /** {@code stop-all}'s optional body. A missing body, or a missing field, means "do not pause schedules". */
    public record StopAllRequest(Boolean pauseSchedules) {}

    private final BoardService board;
    private final GeneralEmployee generalEmployee;

    public OrchestratorController(BoardService board, GeneralEmployee generalEmployee) {
        this.board = board;
        this.generalEmployee = generalEmployee;
    }

    @GetMapping("/board")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Every agent, goal, queued task, question, approval and recent run in this workspace")
    public BoardService.Board board(@RequestParam(defaultValue = "PT2H") String window) {
        BoardService.Window parsed = BoardService.Window.parse(window);
        UUID orgId = orgId();
        generalEmployee.ensure(orgId);
        return board.board(orgId, parsed, RequestContext.requireActor());
    }

    @PostMapping("/stop-all")
    @RequiresPermission(Permission.Codes.RUN_CANCEL)
    @Operation(
            summary = "Cancel every active run and goal, withdraw every pending approval and question, and "
                    + "optionally pause every schedule")
    public BoardService.StopAllResult stopAll(@RequestBody(required = false) StopAllRequest request) {
        boolean pauseSchedules = Boolean.TRUE.equals(request == null ? null : request.pauseSchedules());
        return board.stopAll(orgId(), pauseSchedules, RequestContext.requireActor());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
