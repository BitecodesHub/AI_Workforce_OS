package os.aiworkforce.orchestrator.board;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** The live view of everything the workforce is doing, and the one button that stops it all. */
@RestController
@RequestMapping("/api/orchestrator")
@Tag(name = "Orchestrator")
public class OrchestratorController {

    private final BoardService board;

    public OrchestratorController(BoardService board) {
        this.board = board;
    }

    @GetMapping("/board")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Every agent, goal, queued task and recent run in this workspace")
    public BoardService.Board board() {
        return board.board(orgId());
    }

    @PostMapping("/stop-all")
    @RequiresPermission(Permission.Codes.RUN_CANCEL)
    @Operation(summary = "Cancel every active run and goal, and withdraw every pending approval")
    public BoardService.StopAllResult stopAll() {
        return board.stopAll(orgId());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
