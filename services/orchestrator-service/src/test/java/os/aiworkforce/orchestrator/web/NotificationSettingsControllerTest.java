package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.NotificationService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/** The settings page's three calls: read what is set up, save it, and send a test. */
class NotificationSettingsControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String URL = "https://hooks.example.com/services/T1/B2/abc";

    private NotificationService notifications;
    private AuditClient audit;
    private NotificationSettingsController controller;
    private Actor admin;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        audit = mock(AuditClient.class);
        controller = new NotificationSettingsController(notifications, audit);
        admin = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("workspace:update"), 0L);
        RequestContext.setActor(admin);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("reading says where messages go, whether they are signed and which events are sent, and offers the events")
    void readsTheSettings() {
        when(notifications.settings(ORG))
                .thenReturn(new NotificationService.Settings(URL, true, List.of("approvalRaised", "schedulePaused")));

        NotificationSettingsController.SettingsView view = controller.get();

        assertThat(view.webhookUrl()).isEqualTo(URL);
        assertThat(view.hasSecret()).isTrue();
        assertThat(view.events()).containsExactly("approvalRaised", "schedulePaused");
        assertThat(view.availableEvents())
                .extracting(NotificationSettingsController.EventOption::id)
                .containsExactlyElementsOf(NotificationService.EVENTS);
    }

    @Test
    @DisplayName("saving passes the secret on to be encrypted, and audits what changed without the address or the secret")
    @SuppressWarnings("unchecked")
    void savingIsAudited() {
        when(notifications.update(eq(ORG), eq(URL), eq("a-new-secret-1"), any(), eq(admin.id())))
                .thenReturn(NotificationService.SecretChange.SET);
        when(notifications.settings(ORG))
                .thenReturn(new NotificationService.Settings(URL, true, List.of("approvalRaised")));

        NotificationSettingsController.SettingsView view = controller.update(
                new NotificationSettingsController.UpdateRequest(URL, "a-new-secret-1", List.of("approvalRaised")));

        assertThat(view.hasSecret()).isTrue();
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        eq(admin),
                        eq("notifications.update"),
                        eq("workspace"),
                        eq(ORG.toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("webhookConfigured", true)
                .containsEntry("secret", "set")
                .containsEntry("events", List.of("approvalRaised"));
        assertThat(detail.getValue().toString()).doesNotContain("hooks.example.com").doesNotContain("a-new-secret-1");
    }

    @Test
    @DisplayName("the test button reports how the test went")
    void testButton() {
        when(notifications.sendTest(ORG))
                .thenReturn(new NotificationService.TestResult(false, 404, "The address answered with status 404, so it was not accepted."));

        NotificationSettingsController.TestView view = controller.test();

        assertThat(view.delivered()).isFalse();
        assertThat(view.statusCode()).isEqualTo(404);
        assertThat(view.message()).contains("404");
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
