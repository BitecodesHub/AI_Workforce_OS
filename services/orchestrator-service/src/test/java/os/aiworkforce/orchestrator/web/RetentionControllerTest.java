package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.RetentionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/** The settings page's two calls for how long run detail is kept: read it, and change it. */
class RetentionControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private RetentionService retention;
    private AuditClient audit;
    private RetentionController controller;
    private Actor admin;

    @BeforeEach
    void setUp() {
        retention = mock(RetentionService.class);
        audit = mock(AuditClient.class);
        controller = new RetentionController(retention, audit);
        admin = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("workspace:update"), 0L);
        RequestContext.setActor(admin);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("reading says how many days are kept, what the default is, and the range that may be chosen")
    void reads() {
        when(retention.runDetailDays(ORG)).thenReturn(90);

        RetentionController.SettingsView view = controller.get();

        assertThat(view.runDetailDays()).isEqualTo(90);
        assertThat(view.defaultRunDetailDays()).isEqualTo(180);
        assertThat(view.minRunDetailDays()).isEqualTo(7);
        assertThat(view.maxRunDetailDays()).isEqualTo(3650);
        assertThat(view.usageDays()).isEqualTo(400);
    }

    @Test
    @DisplayName("saving stores the days for this workspace and audits the old and new value")
    @SuppressWarnings("unchecked")
    void savingIsAudited() {
        when(retention.runDetailDays(ORG)).thenReturn(180, 365);

        RetentionController.SettingsView view = controller.update(new RetentionController.UpdateRequest(365));

        verify(retention).setRunDetailDays(ORG, 365, admin.id());
        assertThat(view.runDetailDays()).isEqualTo(365);
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        eq(admin),
                        eq("retention.update"),
                        eq("workspace"),
                        eq(ORG.toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("runDetailDays", 365)
                .containsEntry("previousRunDetailDays", 180);
    }

    @Test
    @DisplayName("both calls need the permission that guards the rest of the workspace's settings")
    void guarded() throws NoSuchMethodException {
        for (var method : new java.lang.reflect.Method[] {
            RetentionController.class.getMethod("get"),
            RetentionController.class.getMethod("update", RetentionController.UpdateRequest.class)
        }) {
            var guard = method.getAnnotation(os.aiworkforce.platform.rbac.RequiresPermission.class);
            assertThat(guard).as(method.getName()).isNotNull();
            assertThat(guard.value()).containsExactly("workspace:update");
        }
    }
}
