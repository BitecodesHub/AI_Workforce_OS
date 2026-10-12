// @find: tests for takeover api, GET /api/runs/{runId}/takeover, POST /api/runs/{runId}/takeover/actions, POST /api/runs/{runId}/takeover/release, never cached, caller workspace
// @what: Unit tests for RunTakeoverController: each route acts in the caller's workspace as the caller, answers are never cached, and a hand back without a body has no note.
package os.aiworkforce.orchestrator.takeover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

class RunTakeoverControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private final Actor person = Actor.user("u-1", ORG.toString(), "role", Set.of("run:read"), 0L);
    private TakeoverService service;
    private RunTakeoverController controller;

    @BeforeEach
    void setUp() {
        service = mock(TakeoverService.class);
        controller = new RunTakeoverController(service);
        RequestContext.setActor(person);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("the state and an action's frame are never cached, and come from the caller's workspace")
    void neverCached() {
        TakeoverService.TakeoverView view =
                new TakeoverService.TakeoverView("none", false, false, null, null, null, false, true, false, 0, null);
        when(service.view(ORG, RUN, person)).thenReturn(view);
        TakeoverActions.ActionRequest key =
                new TakeoverActions.ActionRequest("key", null, null, null, null, null, "Enter", null, null);
        when(service.act(ORG, RUN, person, key)).thenReturn(new TakeoverService.ActionResult("https://a.example/", "A", "/9j/"));

        assertThat(controller.view(RUN).getBody()).isEqualTo(view);
        assertThat(controller.view(RUN).getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(controller.act(RUN, key).getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(controller.act(RUN, key).getBody().frame()).isEqualTo("/9j/");
    }

    @Test
    @DisplayName("taking control and handing back act as the caller; a hand back without a body has no note")
    void startAndRelease() {
        controller.start(RUN);
        controller.release(RUN, null);
        controller.release(RUN, new RunTakeoverController.ReleaseRequest("Signed in."));

        verify(service).start(ORG, RUN, person);
        verify(service).release(eq(ORG), eq(RUN), eq(person), isNull());
        verify(service).release(ORG, RUN, person, "Signed in.");
    }
}
