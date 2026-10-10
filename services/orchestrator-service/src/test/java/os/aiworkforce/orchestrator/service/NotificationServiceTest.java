// @find: tests for notification service, notifications, webhook, signed webhook, approval alerts, test notification
// @what: Unit and integration tests (21 cases) for notification service, for example: sends asigned sentence and alink; signature matches aknown vector; unsigned without asecret; expiry and pause messages.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener.SchedulePause;
import os.aiworkforce.platform.crypto.EncryptedValue;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

/**
 * What leaves the workspace: a sentence and a link, signed when there is a secret, never the
 * payload; the secret kept encrypted; a failure retried twice, then logged; and no address that
 * points back into the network the service runs in.
 */
class NotificationServiceTest {

    // A public address written as its number, so no test depends on a name lookup.
    private static final String URL = "https://93.184.216.34/services/T1/B2/abc";
    private static final String SECRET = "s3cret-value-1";

    private record Sent(URI target, String body, String signature) {}

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> store = new HashMap<>();
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private RuntimeConfigService config;
    private EnvelopeEncryptionService encryption;
    private NotificationService.Sender sender;
    private NotificationService service;
    /** What the receiver answers, per attempt; the last entry repeats. */
    private List<Object> answers = List.of(200);
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        config = mock(RuntimeConfigService.class);
        when(config.getString(any(ConfigKey.class), any())).thenAnswer(call -> {
            ConfigKey key = call.getArgument(0);
            return store.getOrDefault(key.name(), String.valueOf(key.defaultValue()));
        });
        doAnswer(call -> store.put(call.getArgument(0), call.getArgument(2)))
                .when(config)
                .set(anyString(), anyString(), anyString(), any());
        doAnswer(call -> store.remove(call.getArgument(0)))
                .when(config)
                .clear(anyString(), anyString(), any());

        encryption = mock(EnvelopeEncryptionService.class);
        // Not real encryption, but reversible and unreadable as stored: what is under test is that
        // the secret goes through this service on the way in and out, never into the setting in clear.
        when(encryption.encrypt(any(), any()))
                .thenAnswer(call -> EncryptedValue.of(
                        "test-key", new byte[] {1, 2}, new byte[] {3}, ((String) call.getArgument(1)).getBytes(StandardCharsets.UTF_8)));
        when(encryption.decrypt(any(String.class), any(String.class)))
                .thenAnswer(call ->
                        new String(EncryptedValue.parse(call.getArgument(1)).ciphertext(), StandardCharsets.UTF_8));

        sender = (target, body, signature) -> {
            int n = attempts.getAndIncrement();
            sent.add(new Sent(target, body, signature));
            Object answer = answers.get(Math.min(n, answers.size() - 1));
            if (answer instanceof IOException failure) {
                throw failure;
            }
            return (Integer) answer;
        };
        service = serviceWith(true, sender);
    }

    private NotificationService serviceWith(boolean strict, NotificationService.Sender via) {
        return new NotificationService(
                config,
                encryption,
                json,
                strict,
                "https://app.example.com",
                via,
                Runnable::run,
                List.of(Duration.ZERO, Duration.ZERO));
    }

    private void configure(String secret, List<String> events) {
        service.update(ORG, URL, secret, events, "user-1");
    }

    private static Approval approval(String actionClass, String tool) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setTool(tool);
        approval.setActionClass(actionClass);
        approval.setSummary("Send something outside the workspace using gmail.send_message");
        approval.setPayload("{\"to\":\"jane.doe@customer.example\",\"body\":\"Your balance is 4,310.20\"}");
        return approval;
    }

    // ---- What is sent ---------------------------------------------------------------------------

    @Test
    @DisplayName("a raised approval sends a sentence and a link, signed over the exact body, and nothing the agent was sending")
    void sendsASignedSentenceAndALink() throws Exception {
        configure(SECRET, NotificationService.EVENTS);
        Approval approval = approval("OUTBOUND", "gmail.send_message");

        service.onApprovalRaised(null, null, approval);

        assertThat(sent).hasSize(1);
        Sent message = sent.get(0);
        assertThat(message.target()).isEqualTo(URI.create(URL));
        JsonNode body = json.readTree(message.body());
        assertThat(body.fieldNames()).toIterable().containsExactly("event", "workspaceId", "summary", "link");
        assertThat(body.get("event").asText()).isEqualTo("approvalRaised");
        assertThat(body.get("workspaceId").asText()).isEqualTo(ORG.toString());
        assertThat(body.get("summary").asText())
                .isEqualTo("An agent is waiting for approval to send something outside the workspace using gmail.send_message.");
        assertThat(body.get("link").asText()).isEqualTo("https://app.example.com/approvals#approval-" + approval.getId());
        assertThat(message.signature()).isEqualTo("sha256=" + NotificationService.hmacHex(SECRET, message.body()));
        // The payload holds a customer's address and balance: none of it may leave.
        assertThat(message.body()).doesNotContain("jane.doe").doesNotContain("4,310");
    }

    @Test
    @DisplayName("the signature is the HMAC-SHA256 of the body, in lower-case hex")
    void signatureMatchesAKnownVector() {
        // RFC 4231 test case 2.
        assertThat(NotificationService.hmacHex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @DisplayName("with no secret the message is sent unsigned")
    void unsignedWithoutASecret() {
        configure(null, NotificationService.EVENTS);

        service.onSchedulePaused(new SchedulePause(ORG, UUID.randomUUID(), null, "Paused after 3 failed runs in a row.", 3));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).signature()).isNull();
    }

    @Test
    @DisplayName("an expiry and a paused schedule each say so, and link to where to look")
    void expiryAndPauseMessages() throws Exception {
        configure(null, NotificationService.EVENTS);
        Approval approval = approval("DESTRUCTIVE", "stripe.refund_payment");

        service.onApprovalExpired(null, null, approval);
        service.onSchedulePaused(new SchedulePause(ORG, UUID.randomUUID(), UUID.randomUUID(), "x", 3));

        JsonNode expired = json.readTree(sent.get(0).body());
        assertThat(expired.get("event").asText()).isEqualTo("approvalExpired");
        assertThat(expired.get("summary").asText()).contains("expired").contains("nothing was sent");
        assertThat(expired.get("link").asText())
                .isEqualTo("https://app.example.com/approvals?tab=decided#approval-" + approval.getId());
        JsonNode paused = json.readTree(sent.get(1).body());
        assertThat(paused.get("event").asText()).isEqualTo("schedulePaused");
        assertThat(paused.get("summary").asText()).isEqualTo("A schedule paused itself after 3 failed runs in a row.");
        assertThat(paused.get("link").asText()).isEqualTo("https://app.example.com/schedules");
    }

    @Test
    @DisplayName("the sentence names what kind of action the agent wants, from the platform's words and never the arguments")
    void raisedSummaryByActionClass() {
        assertThat(NotificationService.raisedSummary(approval("DESTRUCTIVE", "stripe.refund_payment")))
                .isEqualTo("An agent is waiting for approval to remove something using stripe.refund_payment.");
        assertThat(NotificationService.raisedSummary(approval("WRITE", "crm.update_record")))
                .isEqualTo("An agent is waiting for approval to change something using crm.update_record.");
        assertThat(NotificationService.raisedSummary(approval(null, null)))
                .isEqualTo("An agent is waiting for approval to send something outside the workspace.");
    }

    @Test
    @DisplayName("events the workspace did not choose, and a workspace with no address, send nothing")
    void respectsTheChoiceOfEvents() {
        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));
        assertThat(sent).isEmpty();

        configure(null, List.of("schedulePaused"));
        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));
        assertThat(sent).isEmpty();

        service.onSchedulePaused(new SchedulePause(ORG, UUID.randomUUID(), null, "x", 3));
        assertThat(sent).hasSize(1);
    }

    // ---- Failure ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a message that is not accepted is tried twice more, then dropped")
    void retriesTwiceThenGivesUp() {
        configure(null, NotificationService.EVENTS);
        answers = List.of(500);

        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));

        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("a receiver that cannot be reached is retried the same way, and a later success stops the retries")
    void retriesAnUnreachableReceiver() {
        configure(null, NotificationService.EVENTS);
        answers = List.of(new IOException("connection refused"), 503, 204);

        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));

        assertThat(attempts).hasValue(3);

        attempts.set(0);
        answers = List.of(new IOException("connection refused"), 200);
        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("an address that is not allowed is not retried")
    void aForbiddenAddressIsNotRetried() {
        store.put("notifications.webhookUrl", "https://10.0.0.5/hook");
        store.put("notifications.events", "approvalRaised");

        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));

        assertThat(attempts).hasValue(0);
    }

    @Test
    @DisplayName("a failure to deliver, or to queue, never reaches the caller")
    void neverThrowsIntoTheCaller() {
        configure(null, NotificationService.EVENTS);
        NotificationService refusing = new NotificationService(
                config, encryption, json, true, "", sender, task -> {
                    throw new RejectedExecutionException("shut down");
                }, List.of());
        answers = List.of(new IOException("down"));

        assertThatCode(() -> refusing.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message")))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a message is handed to another thread, so a slow receiver cannot hold up the caller")
    void deliveryIsOffTheCallersThread() {
        configure(null, NotificationService.EVENTS);
        List<Runnable> queued = new ArrayList<>();
        NotificationService queueing =
                new NotificationService(config, encryption, json, true, "", sender, queued::add, List.of());

        queueing.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));

        assertThat(sent).isEmpty();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        assertThat(sent).hasSize(1);
    }

    // ---- Settings --------------------------------------------------------------------------------

    @Test
    @DisplayName("the secret is stored encrypted and never read back; the page can only see that one is set")
    void theSecretIsStoredEncrypted() {
        configure(SECRET, List.of("approvalRaised"));

        String stored = store.get("notifications.webhookSecret");
        assertThat(stored).startsWith("v1:").doesNotContain(SECRET);
        NotificationService.Settings settings = service.settings(ORG);
        assertThat(settings.hasSecret()).isTrue();
        assertThat(settings.webhookUrl()).isEqualTo(URL);
        assertThat(settings.events()).containsExactly("approvalRaised");
        assertThat(settings.toString()).doesNotContain(SECRET).doesNotContain("v1:");
    }

    @Test
    @DisplayName("an omitted secret keeps the stored one, an empty one removes it, a new one replaces it")
    void secretKeptRemovedOrReplaced() {
        assertThat(service.update(ORG, URL, SECRET, NotificationService.EVENTS, "u"))
                .isEqualTo(NotificationService.SecretChange.SET);
        String first = store.get("notifications.webhookSecret");

        assertThat(service.update(ORG, URL, null, NotificationService.EVENTS, "u"))
                .isEqualTo(NotificationService.SecretChange.KEPT);
        assertThat(store.get("notifications.webhookSecret")).isEqualTo(first);

        assertThat(service.update(ORG, URL, "another-secret-9", NotificationService.EVENTS, "u"))
                .isEqualTo(NotificationService.SecretChange.SET);
        assertThat(store.get("notifications.webhookSecret")).isNotEqualTo(first);

        assertThat(service.update(ORG, URL, "", NotificationService.EVENTS, "u"))
                .isEqualTo(NotificationService.SecretChange.REMOVED);
        assertThat(service.settings(ORG).hasSecret()).isFalse();
    }

    @Test
    @DisplayName("clearing the address turns notifications off and removes the secret with it")
    void clearingTheAddress() {
        configure(SECRET, NotificationService.EVENTS);

        assertThat(service.update(ORG, "  ", null, NotificationService.EVENTS, "u"))
                .isEqualTo(NotificationService.SecretChange.REMOVED);

        NotificationService.Settings settings = service.settings(ORG);
        assertThat(settings.webhookUrl()).isEmpty();
        assertThat(settings.hasSecret()).isFalse();
        service.onApprovalRaised(null, null, approval("OUTBOUND", "gmail.send_message"));
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("a workspace that never set anything hears the three events and has no address")
    void defaults() {
        NotificationService.Settings settings = service.settings(ORG);

        assertThat(settings.webhookUrl()).isEmpty();
        assertThat(settings.hasSecret()).isFalse();
        assertThat(settings.events()).containsExactlyElementsOf(NotificationService.EVENTS);
    }

    @Test
    @DisplayName("an event this cannot send, a secret of the wrong length, and an unusable address are refused with the field named")
    void validation() {
        assertThatThrownBy(() -> service.update(ORG, URL, null, List.of("approvalRaised", "everything"), "u"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "events");
                });
        assertThatThrownBy(() -> service.update(ORG, URL, "short", NotificationService.EVENTS, "u"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("field", "secret"));
        assertThatThrownBy(() -> service.update(ORG, "http://93.184.216.34/x", null, NotificationService.EVENTS, "u"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.details()).containsEntry("field", "webhookUrl");
                    assertThat(e.details().get("problem")).isEqualTo("must start with https://");
                });
        assertThat(store).isEmpty();
    }

    // ---- The address -------------------------------------------------------------------------------

    @Test
    @DisplayName("a deployed workspace may post only to https at a public address")
    void strictAddresses() {
        for (String refused : List.of(
                "http://hooks.example.com/x",
                "ftp://hooks.example.com/x",
                "https://127.0.0.1/x",
                "https://localhost/x",
                "https://169.254.169.254/latest/meta-data",
                "https://10.1.2.3/x",
                "https://192.168.0.7/x",
                "https://172.16.0.1/x",
                "https://100.64.1.1/x",
                "https://0.0.0.0/x",
                "https://[::1]/x",
                "https://[fd00::1]/x",
                "https://[fe80::1]/x",
                "https://user:password@93.184.216.34/x",
                "https:///nohost",
                "not a url")) {
            assertThatThrownBy(() -> NotificationService.checkTarget(refused, true))
                    .as(refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(NotificationService.checkTarget("https://93.184.216.34/hook", true).getHost())
                .isEqualTo("93.184.216.34");
        assertThat(NotificationService.checkTarget("https://[2606:4700:4700::1111]/hook", true))
                .isNotNull();
    }

    @Test
    @DisplayName("locally a developer may point it at a receiver on their own machine, still with no credentials in the address")
    void localAddresses() {
        assertThat(NotificationService.checkTarget("http://localhost:9999/hook", false).getPort())
                .isEqualTo(9999);
        assertThat(NotificationService.checkTarget("http://127.0.0.1:9999/hook", false)).isNotNull();
        assertThatThrownBy(() -> NotificationService.checkTarget("http://user:pw@localhost/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationService.checkTarget("gopher://localhost/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- The test button ---------------------------------------------------------------------------

    @Test
    @DisplayName("the test says plainly that no address is saved, that it was delivered, or why it was not")
    void testMessage() throws Exception {
        assertThat(service.sendTest(ORG).delivered()).isFalse();
        assertThat(service.sendTest(ORG).message()).isEqualTo("No webhook address is saved yet.");
        assertThat(sent).isEmpty();

        configure(SECRET, List.of());
        NotificationService.TestResult delivered = service.sendTest(ORG);
        assertThat(delivered.delivered()).isTrue();
        assertThat(delivered.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(sent.get(0).body());
        // Sent whatever events are chosen, and signed like any other.
        assertThat(body.get("event").asText()).isEqualTo("test");
        assertThat(sent.get(0).signature()).startsWith("sha256=");

        answers = List.of(404);
        attempts.set(0);
        NotificationService.TestResult refused = service.sendTest(ORG);
        assertThat(refused.delivered()).isFalse();
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(refused.message()).isEqualTo("The address answered with status 404, so it was not accepted.");
        assertThat(attempts).hasValue(1);

        answers = List.of(new IOException("refused"));
        assertThat(service.sendTest(ORG).message()).isEqualTo("Nothing answered at that address.");
    }

    // ---- Over a real connection ---------------------------------------------------------------------

    @Test
    @DisplayName("over a real connection: a JSON body, a content type, and the signature header a receiver can verify")
    void deliversOverHttp() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            received.add(exchange.getRequestHeaders().getFirst("X-Signature"));
            received.add(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            NotificationService local = serviceWith(false, NotificationService.httpSender());
            local.update(ORG, "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", SECRET, NotificationService.EVENTS, "u");

            NotificationService.TestResult result = local.sendTest(ORG);

            assertThat(result.delivered()).isTrue();
            assertThat(received).hasSize(3);
            assertThat(received.get(0)).isEqualTo("application/json");
            assertThat(received.get(1)).isEqualTo("sha256=" + NotificationService.hmacHex(SECRET, received.get(2)));
            assertThat(json.readTree(received.get(2)).get("event").asText()).isEqualTo("test");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a redirect is not followed, so an address cannot bounce the request somewhere it may not go")
    void redirectsAreNotFollowed() throws Exception {
        AtomicInteger elsewhere = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/internal", exchange -> {
            elsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            NotificationService local = serviceWith(false, NotificationService.httpSender());
            local.update(ORG, "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", null, NotificationService.EVENTS, "u");

            NotificationService.TestResult result = local.sendTest(ORG);

            assertThat(result.delivered()).isFalse();
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(elsewhere).hasValue(0);
        } finally {
            server.stop(0);
        }
    }
}
