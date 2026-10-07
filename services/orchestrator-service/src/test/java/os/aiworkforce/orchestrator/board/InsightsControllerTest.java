package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** Who may read the workforce's figures, and what someone who may read runs but not Analytics is not shown. */
class InsightsControllerTest {

    private static final UUID ORG = UUID.randomUUID();

    private InsightsService service;
    private InsightsController controller;

    @BeforeEach
    void setUp() {
        service = mock(InsightsService.class);
        controller = new InsightsController(service);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static void signedInWith(String... permissions) {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(permissions), 0L));
    }

    @Test
    @DisplayName("the workspace's figures need analytics:read and the per-agent rows need run:read")
    void permissions() throws Exception {
        assertThat(InsightsController.class
                        .getMethod("insights", String.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.ANALYTICS_READ);
        assertThat(InsightsController.class
                        .getMethod("agents", String.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.RUN_READ);
    }

    @Test
    @DisplayName("the window the client asked for is passed on, for the caller's own workspace")
    void passesTheWindowOn() {
        signedInWith("analytics:read");

        controller.insights("7d");

        verify(service).insights(ORG, "7d");
    }

    private InsightsService.AgentInsights withRate() {
        return new InsightsService.AgentInsights(
                "30d",
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00Z"),
                InsightsService.BASIS,
                InsightsService.VALUE_LABEL,
                new BigDecimal("60"),
                List.of());
    }

    @Test
    @DisplayName("someone who can open Analytics sees the hourly cost beside the estimates it produces")
    void analyticsReaderSeesTheRate() {
        signedInWith("run:read", "analytics:read");
        when(service.agents(ORG, "30d")).thenReturn(withRate());

        assertThat(controller.agents("30d").hourlyRate()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("someone who can only read runs gets the agents' rows without the hourly cost")
    void runReaderDoesNotSeeTheRate() {
        signedInWith("run:read");
        when(service.agents(ORG, "30d")).thenReturn(withRate());

        InsightsService.AgentInsights seen = controller.agents("30d");

        assertThat(seen.hourlyRate()).isNull();
        assertThat(seen.valueLabel()).isEqualTo(InsightsService.VALUE_LABEL);
        assertThat(seen.window()).isEqualTo("30d");
    }
}
