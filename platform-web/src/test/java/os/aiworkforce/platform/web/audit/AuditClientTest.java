// @find: tests for audit client, record audit event, outbox write, audit detail redaction
// @what: Checks the audit client writes the expected entries to the outbox.
package os.aiworkforce.platform.web.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/** What gets stored for an event, when it is sent, and what is never stored. */
class AuditClientTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryAuditOutbox outbox;
    private AuditOutboxRelay relay;
    private AuditProperties properties;
    private AuditClient client;

    @BeforeEach
    void setUp() {
        outbox = new InMemoryAuditOutbox();
        relay = mock(AuditOutboxRelay.class);
        properties = properties(true);
        client = new AuditClient(outbox, relay, json, properties);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    static AuditProperties properties(boolean enabled) {
        return new AuditProperties(
                enabled,
                50,
                20,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an event names the signed-in person, their workspace and the request it came from")
    void actorFromTheRequest() {
        RequestContext.setRequestId("req-42");
        RequestContext.setActor(Actor.user("user-1", ORG.toString(), "role", Set.of(), 0));

        UUID id = client.record("member.role_change", "member", "user-2", "succeeded", Map.of("to", "owner"));

        AuditOutbox.Event stored = outbox.all().getFirst();
        assertThat(stored.id()).isEqualTo(id);
        assertThat(stored.orgId()).isEqualTo(ORG);
        assertThat(stored.actorId()).isEqualTo("user-1");
        assertThat(stored.actorKind()).isEqualTo("USER");
        assertThat(stored.onBehalfOf()).isNull();
        assertThat(stored.action()).isEqualTo("member.role_change");
        assertThat(stored.resourceType()).isEqualTo("member");
        assertThat(stored.resourceId()).isEqualTo("user-2");
        assertThat(stored.outcome()).isEqualTo("succeeded");
        assertThat(stored.detailJson()).isEqualTo("{\"to\":\"owner\"}");
        assertThat(stored.requestId()).isEqualTo("req-42");
        assertThat(stored.attempts()).isZero();
        // Whole microseconds, which is all the audit table keeps.
        assertThat(stored.occurredAt().getNano() % 1000).isZero();
        assertThat(stored.occurredAt()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("an agent's event carries the person it acted for")
    void agentActsForSomebody() {
        RequestContext.setActor(Actor.user("boss-1", ORG.toString(), "role", Set.of(), 0).asAgent("agent-1", Set.of()));

        client.record("run.complete", "run", "run-1", "succeeded", Map.of());

        AuditOutbox.Event stored = outbox.all().getFirst();
        assertThat(stored.actorKind()).isEqualTo("AGENT");
        assertThat(stored.actorId()).isEqualTo("agent-1");
        assertThat(stored.onBehalfOf()).isEqualTo("boss-1");
    }

    @Test
    @DisplayName("with no signed-in actor an event is the platform's own, with no workspace")
    void noActor() {
        client.record("auth.sign_in", "user", null, "failed", Map.of());

        AuditOutbox.Event stored = outbox.all().getFirst();
        assertThat(stored.orgId()).isNull();
        assertThat(stored.actorId()).isEqualTo("system");
        assertThat(stored.actorKind()).isEqualTo("SYSTEM");
        assertThat(stored.resourceId()).isNull();
    }

    @Test
    @DisplayName("an event can name its actor and workspace outright, for what happens before anybody is signed in")
    void explicitActor() {
        client.record(ORG, "anonymous", "ANONYMOUS", null, "auth.sign_in", "user", "u1", "locked", Map.of("n", 8));

        AuditOutbox.Event stored = outbox.all().getFirst();
        assertThat(stored.orgId()).isEqualTo(ORG);
        assertThat(stored.actorKind()).isEqualTo("ANONYMOUS");
        assertThat(stored.outcome()).isEqualTo("locked");
    }

    @Test
    @DisplayName("an outcome the audit log does not know is a programming error, found at once")
    void badOutcome() {
        assertThatThrownBy(() -> client.record("a.b", "a", null, "maybe", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outbox.all()).isEmpty();
    }

    @Test
    @DisplayName("switched off, nothing is stored or sent")
    void disabled() {
        client = new AuditClient(outbox, relay, json, properties(false));

        UUID id = client.record("a.b", "a", null, "succeeded", Map.of());

        assertThat(id).isNull();
        assertThat(outbox.all()).isEmpty();
        verify(relay, never()).wake();
    }

    @Test
    @DisplayName("the relay is nudged only after the surrounding transaction commits")
    void nudgeAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        client.record("a.b", "a", null, "succeeded", Map.of());

        // Stored with the change, but nothing is sent before the change is certain.
        assertThat(outbox.all()).hasSize(1);
        verify(relay, never()).wake();

        List<TransactionSynchronization> hooks = TransactionSynchronizationManager.getSynchronizations();
        hooks.forEach(TransactionSynchronization::afterCommit);

        verify(relay).wake();
    }

    @Test
    @DisplayName("a rolled-back transaction never nudges the relay")
    void rollbackDoesNotNudge() {
        TransactionSynchronizationManager.initSynchronization();

        client.record("a.b", "a", null, "succeeded", Map.of());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(hook -> hook.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(relay, never()).wake();
    }

    @Test
    @DisplayName("outside a transaction the relay is nudged at once")
    void nudgeWithoutTransaction() {
        client.record("a.b", "a", null, "succeeded", Map.of());

        verify(relay).wake();
    }

    @Test
    @DisplayName("a value under a key that names a password, token, secret or key is replaced, a reference is kept")
    void secretsAreScrubbed() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("apiKey", "sk-live-123");
        nested.put("ref", "openai");
        Map<String, Object> detail = new HashMap<>();
        detail.put("newPassword", "hunter2hunter2");
        detail.put("Authorization", "Bearer abc");
        detail.put("refreshToken", "r");
        detail.put("secret", "s");
        detail.put("credentialRef", "openai");
        detail.put("kind", "llm");
        detail.put("tokenId", "t-1");
        detail.put("nested", nested);

        client.record("credential.store", "credential", "openai", "succeeded", detail);

        String stored = outbox.all().getFirst().detailJson();
        assertThat(stored)
                .doesNotContain("hunter2hunter2")
                .doesNotContain("Bearer abc")
                .doesNotContain("sk-live-123")
                .doesNotContain("\"r\"");
        assertThat(stored).contains("\"credentialRef\":\"openai\"").contains("\"kind\":\"llm\"").contains("\"tokenId\":\"t-1\"");
        assertThat(stored).contains("\"newPassword\":\"[redacted]\"").contains("\"ref\":\"openai\"");
    }

    @Test
    @DisplayName("fields are cut to what analytics-service accepts, so one long value cannot poison the queue")
    void fieldsAreCut() {
        String long300 = "x".repeat(300);

        client.record("a".repeat(300), "t".repeat(300), long300, "succeeded", Map.of());

        AuditOutbox.Event stored = outbox.all().getFirst();
        assertThat(stored.action()).hasSize(120);
        assertThat(stored.resourceType()).hasSize(80);
        assertThat(stored.resourceId()).hasSize(200);
    }

    @Test
    @DisplayName("a detail too large to keep is replaced by a note that it was, and the event is still recorded")
    void hugeDetail() {
        client.record("a.b", "a", null, "succeeded", Map.of("blob", "x".repeat(100_000)));

        assertThat(outbox.all().getFirst().detailJson()).startsWith("{\"truncated\":true");
    }

    @Test
    @DisplayName("a detail that refers to itself does not lose the event or overflow the stack")
    void selfReferringDetail() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        cyclic.put("list", List.of(Map.of("inner", "kept")));

        client.record("a.b", "a", null, "succeeded", cyclic);

        assertThat(outbox.all()).hasSize(1);
        assertThat(outbox.all().getFirst().detailJson()).contains("nested too deeply").contains("kept");
    }

    @Test
    @DisplayName("secrets inside a list of objects are replaced too")
    void secretsInLists() {
        client.record("a.b", "a", null, "succeeded", Map.of("items", List.of(Map.of("password", "p", "name", "n"))));

        assertThat(outbox.all().getFirst().detailJson()).doesNotContain("\"p\"").contains("\"name\":\"n\"");
    }
}
