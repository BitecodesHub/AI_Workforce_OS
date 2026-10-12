// @find: tests for push service, subscribe device, unsubscribe ownership, push on approval raised, 410 removes subscription, failure count, rate limit, test notification, no payload body in push, PushServiceTest
// @what: Unit tests for PushService and PushDelivery: subscription checks and ownership, what a push carries (read back as the browser would), the push service's answers, limits and the test button.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** A person's devices, and what reaches them when work waits on that person. */
class PushServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID MAYA = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID PRIYA = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    // A real browser key pair and secret (RFC 8291's example), so a test can read what was sent.
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/";
    private static final String CHROME_ANDROID =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";

    private record Sent(URI endpoint, Map<String, String> headers, byte[] body) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<Sent> sent = new ArrayList<>();
    private Function<URI, Integer> answer;
    private InMemoryPushSubscriptions store;
    private PushRecipients recipients;
    private WebPushCrypto.RawKeyPair vapid;
    private PushService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store = new InMemoryPushSubscriptions();
        recipients = mock(PushRecipients.class);
        vapid = WebPushCrypto.generateKeyPair();
        answer = endpoint -> 201;
        service = service(true);
    }

    private PushService service(boolean enabled) {
        VapidKeyStore keys = new VapidKeyStore(
                mock(JdbcTemplate.class), mock(EnvelopeEncryptionService.class), vapid.publicKey(), vapid.privateKey(), clock);
        PushDelivery.Transport transport = (endpoint, headers, body) -> {
            sent.add(new Sent(endpoint, headers, body));
            Integer status = answer.apply(endpoint);
            if (status == null) {
                throw new IOException("connection reset");
            }
            return status;
        };
        return new PushService(
                store,
                keys,
                recipients,
                new PushEndpoints(PushEndpoints.DEFAULT_HOSTS, false),
                json,
                "mailto:no-reply@example.com",
                transport,
                Runnable::run,
                enabled,
                clock);
    }

    private Approval approval(String actionClass) {
        Approval approval = new Approval();
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setTool("gmail.send_message");
        approval.setActionClass(actionClass);
        approval.setSummary("Send something outside the workspace using gmail.send_message");
        approval.setPayload("{\"to\":\"jane@customer.example\",\"subject\":\"Your salary review\",\"body\":\"Dear Jane, private terms\"}");
        approval.setRequestedBy(PRIYA);
        approval.setExpiresAt(clock.instant().plus(2, ChronoUnit.HOURS));
        return approval;
    }

    private JsonNode read(Sent message) throws Exception {
        return json.readTree(PushTestCrypto.decrypt(message.body(), UA_PRIVATE, UA_PUBLIC, AUTH));
    }

    // ---- Subscriptions -----------------------------------------------------------------------

    @Test
    @DisplayName("subscribing saves the browser with a label it can be told apart by, and the same browser again keeps one row")
    void subscribeSavesAndRefreshes() {
        PushSubscription first = service.subscribe(ORG, MAYA, FCM + "abc", UA_PUBLIC, AUTH, CHROME_ANDROID);
        PushSubscription again = service.subscribe(ORG, MAYA, FCM + "abc", UA_PUBLIC + "==", AUTH, CHROME_ANDROID);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.label()).isEqualTo("Chrome on Android");
        assertThat(again.p256dh()).isEqualTo(UA_PUBLIC);
        assertThat(service.devices(ORG, MAYA)).hasSize(1);
        assertThat(service.devices(ORG, PRIYA)).isEmpty();
    }

    @Test
    @DisplayName("an endpoint that is not a browser push service over https, or keys that are not a browser's, are refused")
    void subscribeChecksWhatArrives() {
        assertValidation(() -> service.subscribe(ORG, MAYA, "http://fcm.googleapis.com/fcm/send/x", UA_PUBLIC, AUTH, null), "endpoint");
        assertValidation(() -> service.subscribe(ORG, MAYA, "https://169.254.169.254/latest/meta-data", UA_PUBLIC, AUTH, null), "endpoint");
        assertValidation(() -> service.subscribe(ORG, MAYA, "https://fcm.googleapis.com.evil.example/x", UA_PUBLIC, AUTH, null), "endpoint");
        assertValidation(() -> service.subscribe(ORG, MAYA, "https://user:pw@fcm.googleapis.com/x", UA_PUBLIC, AUTH, null), "endpoint");
        byte[] offCurve = WebPushCrypto.fromBase64Url(UA_PUBLIC);
        offCurve[64] ^= 0x01;
        assertValidation(() -> service.subscribe(ORG, MAYA, FCM + "x", WebPushCrypto.base64Url(offCurve), AUTH, null), "keys.p256dh");
        assertValidation(() -> service.subscribe(ORG, MAYA, FCM + "x", UA_PUBLIC, "c2hvcnQ", null), "keys.auth");
        assertValidation(() -> service.subscribe(ORG, MAYA, FCM + "x", "!!!", AUTH, null), "keys.p256dh");
        assertThat(store.all()).isEmpty();

        // Every browser's push service is accepted.
        for (String endpoint : List.of(
                "https://updates.push.services.mozilla.com/wpush/v2/abc",
                "https://web.push.apple.com/QGv",
                "https://wns2-par02p.notify.windows.com/w/?token=abc")) {
            service.subscribe(ORG, MAYA, endpoint, UA_PUBLIC, AUTH, null);
        }
        assertThat(store.all()).hasSize(3);
    }

    private static void assertValidation(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.details().toString()).contains(field);
        });
    }

    @Test
    @DisplayName("a person keeps at most ten devices: the oldest goes when another arrives")
    void keepsTenDevices() {
        for (int i = 0; i < PushService.MAX_DEVICES + 1; i++) {
            service.subscribe(ORG, MAYA, FCM + "device-" + i, UA_PUBLIC, AUTH, null);
        }

        List<PushSubscription> devices = service.devices(ORG, MAYA);
        assertThat(devices).hasSize(PushService.MAX_DEVICES);
        assertThat(devices).extracting(PushSubscription::endpoint).doesNotContain(FCM + "device-0").contains(FCM + "device-10");
    }

    @Test
    @DisplayName("turning a device off removes only the caller's own subscription, in the caller's own workspace")
    void unsubscribeIsScopedToTheOwner() {
        PushSubscription priyas = store.add(ORG, PRIYA, FCM + "priya", UA_PUBLIC, AUTH);
        PushSubscription mayasElsewhere = store.add(OTHER_ORG, MAYA, FCM + "maya-other", UA_PUBLIC, AUTH);
        PushSubscription mayas = store.add(ORG, MAYA, FCM + "maya", UA_PUBLIC, AUTH);

        assertThat(service.unsubscribe(ORG, MAYA, FCM + "priya")).isEmpty();
        assertThat(service.unsubscribe(ORG, MAYA, FCM + "maya-other")).isEmpty();
        assertThat(service.unsubscribe(ORG, MAYA, FCM + "maya")).contains(mayas.id());

        assertThat(store.byId(priyas.id())).isPresent();
        assertThat(store.byId(mayasElsewhere.id())).isPresent();
        assertThat(store.byId(mayas.id())).isEmpty();
        assertThatThrownBy(() -> service.unsubscribe(ORG, MAYA, " ")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a different person turning push on in the same browser takes the device over")
    void sameBrowserNewPersonTakesOver() {
        store.add(ORG, PRIYA, FCM + "shared", UA_PUBLIC, AUTH);

        service.subscribe(ORG, MAYA, FCM + "shared", UA_PUBLIC, AUTH, null);

        assertThat(service.devices(ORG, PRIYA)).isEmpty();
        assertThat(service.devices(ORG, MAYA)).hasSize(1);
    }

    // ---- What reaches the device -------------------------------------------------------------

    @Test
    @DisplayName("an approval reaches the deciders' devices as a sentence and a link, encrypted, never with what the agent wants to send")
    void approvalPushCarriesNoPayload() throws Exception {
        store.add(ORG, MAYA, FCM + "maya-phone", UA_PUBLIC, AUTH);
        store.add(ORG, PRIYA, FCM + "priya-phone", UA_PUBLIC, AUTH);
        Approval approval = approval("OUTBOUND");
        when(recipients.forApproval(approval)).thenReturn(List.of(MAYA));

        service.onApprovalRaised(null, null, approval);

        assertThat(sent).hasSize(1);
        Sent message = sent.get(0);
        assertThat(message.endpoint().toString()).isEqualTo(FCM + "maya-phone");
        JsonNode body = read(message);
        assertThat(body.get("title").asText()).isEqualTo("An agent needs your approval");
        assertThat(body.get("url").asText()).isEqualTo("/approvals#approval-" + approval.getId());
        assertThat(body.get("tag").asText()).isEqualTo("approval-" + approval.getId());
        assertThat(body.get("kind").asText()).isEqualTo("approval");
        assertThat(body.get("body").asText()).contains("outside the workspace").contains("approve or reject");
        String everything = body.toString();
        assertThat(everything)
                .doesNotContain("jane@customer.example")
                .doesNotContain("salary")
                .doesNotContain("private terms")
                .doesNotContain(approval.getPayload());
        assertThat(new String(message.body(), StandardCharsets.ISO_8859_1)).doesNotContain("approval");

        assertThat(message.headers())
                .containsEntry("Content-Encoding", "aes128gcm")
                .containsEntry("Urgency", "high")
                .containsEntry("TTL", "7200");
        String authorization = message.headers().get("Authorization");
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + vapid.publicKey());
        String token = authorization.substring("vapid t=".length(), authorization.indexOf(','));
        JsonNode claims = json.readTree(WebPushCrypto.fromBase64Url(token.split("\\.")[1]));
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:no-reply@example.com");
        assertThat(store.byEndpoint.get(FCM + "maya-phone").lastSuccessAt()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("a removal says it cannot be undone")
    void destructiveSaysSo() throws Exception {
        store.add(ORG, MAYA, FCM + "maya-phone", UA_PUBLIC, AUTH);
        Approval approval = approval("DESTRUCTIVE");
        approval.setTool("drive.delete_file");
        when(recipients.forApproval(approval)).thenReturn(List.of(MAYA));

        service.onApprovalRaised(null, null, approval);

        assertThat(read(sent.get(0)).get("body").asText()).contains("cannot be undone");
    }

    @Test
    @DisplayName("a question reaches the person it asks, linking to the conversation, without the question's words")
    void questionPush() throws Exception {
        store.add(ORG, PRIYA, FCM + "priya-phone", UA_PUBLIC, AUTH);
        RunQuestion question = new RunQuestion();
        question.setOrgId(ORG);
        question.setRunId(UUID.randomUUID());
        question.setConversationId(UUID.randomUUID());
        question.setRequestedBy(PRIYA);
        question.setQuestionsJson("[{\"question\":\"Which client, Acme or Globex?\"}]");
        question.setExpiresAt(clock.instant().plus(1, ChronoUnit.DAYS));
        when(recipients.forQuestion(question)).thenReturn(List.of(PRIYA));

        service.onQuestionAsked(null, null, question);

        JsonNode body = read(sent.get(0));
        assertThat(body.get("title").asText()).isEqualTo("An agent has a question for you");
        assertThat(body.get("url").asText())
                .isEqualTo("/chat?c=" + question.getConversationId() + "#question-" + question.getId());
        assertThat(body.toString()).doesNotContain("Acme").doesNotContain("Globex");
    }

    @Test
    @DisplayName("a subscription the push service says is gone (404 or 410) is removed at once")
    void goneSubscriptionsAreRemoved() {
        PushSubscription gone = store.add(ORG, MAYA, FCM + "gone", UA_PUBLIC, AUTH);
        PushSubscription missing = store.add(ORG, MAYA, FCM + "missing", UA_PUBLIC, AUTH);
        PushSubscription fine = store.add(ORG, MAYA, FCM + "fine", UA_PUBLIC, AUTH);
        answer = endpoint -> endpoint.toString().endsWith("gone") ? 410 : endpoint.toString().endsWith("missing") ? 404 : 201;
        Approval approval = approval("OUTBOUND");
        when(recipients.forApproval(approval)).thenReturn(List.of(MAYA));

        service.onApprovalRaised(null, null, approval);

        assertThat(sent).hasSize(3);
        assertThat(store.byId(gone.id())).isEmpty();
        assertThat(store.byId(missing.id())).isEmpty();
        assertThat(store.byId(fine.id())).isPresent();
    }

    @Test
    @DisplayName("other failures are counted, a success starts the count again, and ten in a row remove the subscription")
    void failuresAreCounted() {
        PushSubscription device = store.add(ORG, MAYA, FCM + "flaky", UA_PUBLIC, AUTH);
        when(recipients.forApproval(any())).thenReturn(List.of(MAYA));
        service = service(true);

        answer = endpoint -> 500;
        service.send(ORG, List.of(MAYA), PushMessage.test());
        answer = endpoint -> null; // no answer at all
        service.send(ORG, List.of(MAYA), PushMessage.test());
        assertThat(store.byId(device.id()).orElseThrow().failureCount()).isEqualTo(2);

        answer = endpoint -> 201;
        service.send(ORG, List.of(MAYA), PushMessage.test());
        assertThat(store.byId(device.id()).orElseThrow().failureCount()).isZero();

        answer = endpoint -> 429;
        PushDelivery delivery = new PushDelivery(
                store,
                new VapidKeyStore(mock(JdbcTemplate.class), mock(EnvelopeEncryptionService.class), vapid.publicKey(), vapid.privateKey(), clock),
                new PushEndpoints(PushEndpoints.DEFAULT_HOSTS, false),
                (endpoint, headers, body) -> answer.apply(endpoint),
                json,
                "mailto:no-reply@example.com",
                clock);
        for (int i = 0; i < PushDelivery.MAX_FAILURES - 1; i++) {
            assertThat(delivery.deliver(store.byId(device.id()).orElseThrow(), PushMessage.test()))
                    .isEqualTo(PushDelivery.Outcome.FAILED);
        }
        assertThat(store.byId(device.id())).isPresent();
        delivery.deliver(store.byId(device.id()).orElseThrow(), PushMessage.test());
        assertThat(store.byId(device.id())).isEmpty();
    }

    @Test
    @DisplayName("nothing that goes wrong while telling people reaches the code that raised the approval")
    void failuresNeverReachTheCaller() {
        Approval approval = approval("OUTBOUND");
        when(recipients.forApproval(approval)).thenThrow(new IllegalStateException("identity is down"));

        service.onApprovalRaised(null, null, approval);
        service.onApprovalRaised(null, null, null);
        PushService refusing = new PushService(
                store, mock(VapidKeyStore.class), recipients, new PushEndpoints(List.of(), false), json, "mailto:x@example.com",
                (endpoint, headers, body) -> 201,
                task -> {
                    throw new java.util.concurrent.RejectedExecutionException("shut down");
                },
                true,
                clock);
        refusing.onApprovalRaised(null, null, approval("OUTBOUND"));

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("people are chosen as the platform itself, even when a person's decision resumed the run that raised the approval")
    void recipientsAreChosenAsThePlatform() {
        Actor approver = Actor.user(MAYA.toString(), ORG.toString(), "manager", java.util.Set.of("approval:decide"), 0L);
        List<Actor> seen = new ArrayList<>();
        Approval approval = approval("OUTBOUND");
        when(recipients.forApproval(approval)).thenAnswer(call -> {
            seen.add(RequestContext.actor().orElse(null));
            return List.of();
        });
        RequestContext.setActor(approver);
        try {
            service.onApprovalRaised(null, null, approval);

            assertThat(seen).containsExactly(Actor.SYSTEM);
            assertThat(RequestContext.actor()).contains(approver);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("a person is told at most eight times in five minutes; the rest wait on the Approvals page")
    void notificationsAreRateLimitedPerPerson() {
        store.add(ORG, MAYA, FCM + "maya-phone", UA_PUBLIC, AUTH);
        store.add(ORG, PRIYA, FCM + "priya-phone", UA_PUBLIC, AUTH);
        when(recipients.forApproval(any())).thenReturn(List.of(MAYA));

        for (int i = 0; i < PushService.NOTIFY_LIMIT + 3; i++) {
            service.onApprovalRaised(null, null, approval("OUTBOUND"));
        }
        assertThat(sent).hasSize(PushService.NOTIFY_LIMIT);

        // Another person has a limit of their own.
        when(recipients.forApproval(any())).thenReturn(List.of(PRIYA));
        service.onApprovalRaised(null, null, approval("OUTBOUND"));
        assertThat(sent).hasSize(PushService.NOTIFY_LIMIT + 1);
    }

    // ---- The test button and the switch ------------------------------------------------------

    @Test
    @DisplayName("the test reaches every one of the caller's devices and says how it went, three times a minute at most")
    void testButton() throws Exception {
        assertThat(service.sendTest(ORG, MAYA).message()).isEqualTo("No device has push notifications turned on yet.");

        store.add(ORG, MAYA, FCM + "phone", UA_PUBLIC, AUTH);
        store.add(ORG, MAYA, FCM + "old-laptop", UA_PUBLIC, AUTH);
        store.add(ORG, PRIYA, FCM + "priyas", UA_PUBLIC, AUTH);
        answer = endpoint -> endpoint.toString().endsWith("old-laptop") ? 410 : 201;

        PushService.TestResult result = service.sendTest(ORG, MAYA);

        assertThat(result.devices()).isEqualTo(2);
        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.message())
                .isEqualTo("The test notification was sent to 1 of your 2 devices. One device no longer accepts notifications and was removed.");
        assertThat(sent).extracting(message -> message.endpoint().toString()).doesNotContain(FCM + "priyas");
        assertThat(read(sent.get(0)).get("kind").asText()).isEqualTo("test");

        service.sendTest(ORG, MAYA);
        assertThatThrownBy(() -> service.sendTest(ORG, MAYA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("with push switched off, the key is not offered, nothing can subscribe and nothing is sent")
    void switchedOff() {
        PushService off = service(false);
        store.add(ORG, MAYA, FCM + "phone", UA_PUBLIC, AUTH);
        when(recipients.forApproval(any())).thenReturn(List.of(MAYA));

        assertThat(off.status()).isEqualTo(new PushService.Status(false, null));
        assertThatThrownBy(() -> off.subscribe(ORG, MAYA, FCM + "x", UA_PUBLIC, AUTH, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_DEGRADED));
        off.onApprovalRaised(null, null, approval("OUTBOUND"));
        assertThat(sent).isEmpty();

        assertThat(service.status()).isEqualTo(new PushService.Status(true, vapid.publicKey()));
    }

    @Test
    @DisplayName("the VAPID contact is the configured one, else no-reply at the host the app is served from")
    void subjectFromTheHost() {
        assertThat(PushService.subjectFor("mailto:ops@example.com", "", null)).isEqualTo("mailto:ops@example.com");
        assertThat(PushService.subjectFor("", "https://console.example.com/", null)).isEqualTo("mailto:no-reply@console.example.com");
        assertThat(PushService.subjectFor("", "", null)).isEqualTo("mailto:no-reply@localhost");
    }

    @Test
    @DisplayName("the test sentence reads right for one device, all devices and none")
    void testSentences() {
        assertThat(PushService.testSentence(1, 1, 0)).isEqualTo("The test notification was sent to your device.");
        assertThat(PushService.testSentence(3, 3, 0)).isEqualTo("The test notification was sent to all 3 of your devices.");
        assertThat(PushService.testSentence(2, 0, 2))
                .isEqualTo("The test notification could not be sent. 2 devices no longer accept notifications and were removed.");
    }
}
