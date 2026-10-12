// @find: tests for run glance, latest step of a run, Working now tile, GET /api/runs/{runId}/glance
// @what: Unit tests for RunGlanceController: only the facts a caption needs leave the server, whether the run used the browser, and hidden runs are not found.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** A Working now tile reads a run at a glance: a few facts, not the whole trace. */
class RunGlanceControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Runs runs;
    private RunSteps steps;
    private RunGlanceController controller;
    private Run run;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        controller = new RunGlanceController(runs, steps);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("run:read"), 0L));
        run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setAgentId(UUID.randomUUID());
        run.setStatus("running");
        when(runs.findByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("answers the latest step's kind and caption facts only, never its page text or results, and whether the browser was used")
    void captionFactsOnly() {
        RunStep latest = RunStep.of(ORG, run.getId(), 7, "tool_call", Map.of(
                "tool", "browser.open_page",
                "status", "SUCCEEDED",
                "summary", "Opened Example Domain.",
                "modelContent", "{\"text\":\"" + "page text ".repeat(500) + "\"}",
                "screenshotId", "shot-1"));
        when(steps.findFirstByRunIdOrderByPositionDesc(run.getId())).thenReturn(Optional.of(latest));
        when(steps.usedBrowser(run.getId())).thenReturn(true);

        RunGlanceController.RunGlance glance = controller.glance(run.getId()).getBody();

        assertThat(glance).isNotNull();
        assertThat(glance.kind()).isEqualTo("tool_call");
        assertThat(glance.detail()).containsOnlyKeys("tool", "status");
        assertThat(glance.detail()).containsEntry("tool", "browser.open_page");
        assertThat(glance.browsing()).isTrue();
        assertThat(glance.startedAt()).isEqualTo(run.getStartedAt());
        assertThat(controller.glance(run.getId()).getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("a run with no steps yet answers no kind and an empty detail")
    void noStepsYet() {
        when(steps.findFirstByRunIdOrderByPositionDesc(run.getId())).thenReturn(Optional.empty());

        RunGlanceController.RunGlance glance = controller.glance(run.getId()).getBody();

        assertThat(glance).isNotNull();
        assertThat(glance.kind()).isNull();
        assertThat(glance.detail()).isEmpty();
        assertThat(glance.browsing()).isFalse();
    }

    @Test
    @DisplayName("a run behind a private conversation the caller is not part of, or in another workspace, is not found")
    void hiddenRunsAreNotFound() {
        UUID task = UUID.randomUUID();
        run.setTaskId(task);
        ConversationAccess access = mock(ConversationAccess.class);
        when(access.hiddenTaskIds(eq(ORG), any())).thenReturn(Set.of(task));
        ReflectionTestUtils.setField(controller, "access", access);

        assertThatThrownBy(() -> controller.glance(run.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> controller.glance(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
