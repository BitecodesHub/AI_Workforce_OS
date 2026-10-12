// @find: tests for push controller, /api/push, subscribe audited, API key refused, my devices only, remove device by id, PushControllerTest
// @what: Unit tests for PushController: only a person's own session may use it, every call is scoped to the caller and tied to their sign-in session, and the audit records real changes only, never the endpoint or keys.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The calls the settings card makes, as the person signed in. */
class PushControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ME = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";
    private static final UUID SESSION = UUID.fromString("00000000-0000-7000-8000-00000000c0de");

    private PushService push;
    private AuditClient audit;
    private PushController controller;
    private Actor me;

    @BeforeEach
    void setUp() {
        push = mock(PushService.class);
        audit = mock(AuditClient.class);
        controller = new PushController(push, audit);
        // An employee: no special permission is needed for one's own devices. The token names its session.
        me = new Actor(ME.toString(), Actor.Kind.USER, ORG.toString(), "employee", Set.of("run:read", "chat:use"), 0L,
                null, SESSION.toString(), null, Map.of());
        RequestContext.setActor(me);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static PushSubscription row(String endpoint) {
        return new PushSubscription(
                UUID.randomUUID(), ORG, ME, endpoint, "p256dh-secret-key", "auth-secret", "Safari on iPhone", SESSION,
                Instant.parse("2026-10-12T09:00:00Z"), null, 0);
    }

    @Test
    @DisplayName("the key endpoint says whether push is offered and with which key")
    void publicKey() {
        when(push.status()).thenReturn(new PushService.Status(true, "BPUBLIC"));

        assertThat(controller.publicKey()).isEqualTo(new PushController.KeyView(true, "BPUBLIC"));
    }

    @Test
    @DisplayName("subscribing saves the browser for the caller and audits the device label, never the endpoint or keys")
    @SuppressWarnings("unchecked")
    void subscribeIsAuditedWithoutSecrets() {
        PushSubscription saved = row(ENDPOINT);
        when(push.subscribe(ORG, ME, SESSION, ENDPOINT, "BKEY", "AUTH", "UA")).thenReturn(new PushService.Saved(saved, true));

        PushController.DeviceView view = controller.subscribe(
                new PushController.SubscribeRequest(ENDPOINT, null, new PushController.SubscriptionKeys("BKEY", "AUTH")), "UA");

        assertThat(view.id()).isEqualTo(saved.id());
        assertThat(view.label()).isEqualTo("Safari on iPhone");
        assertThat(view.toString()).doesNotContain("p256dh-secret-key").doesNotContain("auth-secret");
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ORG), eq(me), eq("push.subscribe"), eq("push_subscription"), eq(saved.id().toString()),
                eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("device", "Safari on iPhone");
        assertThat(detail.getValue().toString()).doesNotContain("fcm.googleapis.com").doesNotContain("BKEY");
    }

    @Test
    @DisplayName("a browser telling the server again what it already knew, as on every visit, writes no audit entry")
    void unchangedSubscriptionIsNotAudited() {
        when(push.subscribe(ORG, ME, SESSION, ENDPOINT, "BKEY", "AUTH", "UA")).thenReturn(new PushService.Saved(row(ENDPOINT), false));

        controller.subscribe(
                new PushController.SubscribeRequest(ENDPOINT, null, new PushController.SubscriptionKeys("BKEY", "AUTH")), "UA");

        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a device is removed by its id as the caller's own, audited only when one of theirs went")
    void removeDeviceById() {
        UUID lostPhone = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        when(push.removeDevice(ORG, ME, lostPhone)).thenReturn(true);
        when(push.removeDevice(ORG, ME, someoneElses)).thenReturn(false);

        controller.removeDevice(lostPhone);
        controller.removeDevice(someoneElses);

        verify(audit).record(eq(ORG), eq(me), eq("push.unsubscribe"), eq("push_subscription"), eq(lostPhone.toString()),
                eq("succeeded"), any());
        verify(audit, never()).record(any(), any(), any(), any(), eq(someoneElses.toString()), any(), any());
    }

    @Test
    @DisplayName("the device list and the removal are the caller's own, in the caller's workspace")
    void scopedToTheCaller() {
        when(push.devices(ORG, ME)).thenReturn(List.of(row(ENDPOINT)));
        UUID removed = UUID.randomUUID();
        when(push.unsubscribe(ORG, ME, ENDPOINT)).thenReturn(Optional.of(removed));

        assertThat(controller.devices()).singleElement().satisfies(device -> assertThat(device.endpoint()).isEqualTo(ENDPOINT));
        controller.unsubscribe(new PushController.UnsubscribeRequest(ENDPOINT));

        verify(push).unsubscribe(ORG, ME, ENDPOINT);
        verify(audit).record(eq(ORG), eq(me), eq("push.unsubscribe"), eq("push_subscription"), eq(removed.toString()),
                eq("succeeded"), any());
    }

    @Test
    @DisplayName("removing a subscription that is not the caller's answers the same and audits nothing")
    void removingSomeoneElsesSaysNothing() {
        when(push.unsubscribe(ORG, ME, ENDPOINT)).thenReturn(Optional.empty());

        controller.unsubscribe(new PushController.UnsubscribeRequest(ENDPOINT));

        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the test button reports how the test went")
    void testButton() {
        when(push.sendTest(ORG, ME)).thenReturn(new PushService.TestResult(1, 1, 0, "The test notification was sent to your device."));

        assertThat(controller.test()).isEqualTo(new PushController.TestView(1, 1, 0, "The test notification was sent to your device."));
    }

    @Test
    @DisplayName("an API key, which has no device, cannot use any of it")
    void apiKeysAreRefused() {
        RequestContext.setActor(new Actor(
                "key-1", Actor.Kind.API_KEY, ORG.toString(), null, Set.of("approval:decide"), 0L, null, null, null, Map.of()));

        assertThatThrownBy(() -> controller.devices())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        assertThatThrownBy(() -> controller.subscribe(
                        new PushController.SubscribeRequest(ENDPOINT, null, new PushController.SubscriptionKeys("k", "a")), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.test()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.removeDevice(UUID.randomUUID())).isInstanceOf(ApiException.class);
        verifyNoInteractions(push);
    }
}
