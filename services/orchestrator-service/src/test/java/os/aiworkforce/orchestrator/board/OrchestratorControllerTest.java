package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

/** The orchestrator board and stop-all endpoints: window parsing, the actor and General Employee. */
class OrchestratorControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private BoardService board;
    private GeneralEmployee generalEmployee;
    private OrchestratorController controller;
    private Actor actor;

    @BeforeEach
    void setUp() {
        board = mock(BoardService.class);
        generalEmployee = mock(GeneralEmployee.class);
        controller = new OrchestratorController(board, generalEmployee);
        actor = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("run:read", "run:cancel"), 0L);
        RequestContext.setActor(actor);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static BoardService.Board emptyBoard(String window) {
        BoardService.Stats stats = new BoardService.Stats(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, 0);
        return new BoardService.Board(
                Instant.now(),
                "Australia/Melbourne",
                window,
                120,
                stats,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    @DisplayName("a window outside the allowed set is a validation error")
    void invalidWindowIs422() {
        ApiException thrown = catchThrowableOfType(() -> controller.board("PT3H"), ApiException.class);

        assertThat(thrown.details()).containsEntry("field", "window");
        verify(board, never()).board(any(), any(), any());
    }

    @Test
    @DisplayName("the TODAY window is accepted")
    void todayWindowIsAccepted() {
        when(board.board(eq(ORG), eq(BoardService.Window.TODAY), eq(actor))).thenReturn(emptyBoard("TODAY"));

        BoardService.Board result = controller.board("TODAY");

        assertThat(result.window()).isEqualTo("TODAY");
    }

    @Test
    @DisplayName("reading the board ensures General Employee exists and passes the acting person through")
    void boardEnsuresGeneralEmployeeAndPassesTheActor() {
        when(board.board(eq(ORG), eq(BoardService.Window.H2), eq(actor))).thenReturn(emptyBoard("PT2H"));

        controller.board("PT2H");

        verify(generalEmployee).ensure(ORG);
        verify(board).board(ORG, BoardService.Window.H2, actor);
    }

    @Test
    @DisplayName("a stop-all with no request body does not pause schedules")
    void stopAllWithNoBodyDoesNotPauseSchedules() {
        BoardService.StopAllResult result = new BoardService.StopAllResult(0, 0, 0, 0, 0, 0, 0);
        when(board.stopAll(eq(ORG), eq(false), eq(actor))).thenReturn(result);

        BoardService.StopAllResult actual = controller.stopAll(null);

        assertThat(actual).isEqualTo(result);
        verify(board).stopAll(ORG, false, actor);
    }
}
