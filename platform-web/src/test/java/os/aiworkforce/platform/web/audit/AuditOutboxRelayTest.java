package os.aiworkforce.platform.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** The relay keeps an event until analytics-service has it, and says so when it cannot. */
class AuditOutboxRelayTest {

    private InMemoryAuditOutbox outbox;
    private final List<UUID> sent = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private AuditOutboxRelay relay;
    private ListAppender<ILoggingEvent> logs;
    private Logger relayLogger;

    @BeforeEach
    void setUp() {
        outbox = new InMemoryAuditOutbox();
        relay = new AuditOutboxRelay(
                outbox,
                event -> {
                    sent.add(event.id());
                    if (!failures.isEmpty()) {
                        throw failures.removeFirst();
                    }
                },
                AuditClientTest.properties(true));
        relayLogger = (Logger) LoggerFactory.getLogger(AuditOutboxRelay.class);
        relayLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        relayLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logs);
        relay.shutdown();
    }

    private AuditOutbox.Event event(String action, Instant at) {
        return new AuditOutbox.Event(
                UUID.randomUUID(), null, "user-1", "USER", null, action, "thing", null, "succeeded", "{}", "req", at, 0);
    }

    private AuditOutbox.Event queue(String action, int secondsAfterStart) {
        AuditOutbox.Event event = event(action, outbox.now.plusSeconds(secondsAfterStart));
        outbox.add(event);
        return event;
    }

    private static WebClientResponseException http(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("due events are sent oldest first and removed once accepted")
    void deliversInOrder() {
        AuditOutbox.Event later = queue("b", 2);
        AuditOutbox.Event first = queue("a", 1);

        int delivered = relay.deliverDue();

        assertThat(delivered).isEqualTo(2);
        assertThat(sent).containsExactly(first.id(), later.id());
        assertThat(outbox.all()).isEmpty();
    }

    @Test
    @DisplayName("a failed delivery is kept and retried after a growing wait, with the same event id every time")
    void retriesWithBackoff() {
        AuditOutbox.Event event = queue("role.update", 0);
        failures.add(new RuntimeException(new ConnectException("refused")));
        failures.add(new RuntimeException(new ConnectException("refused")));

        assertThat(relay.deliverDue()).isZero();
        assertThat(outbox.all().getFirst().attempts()).isEqualTo(1);
        assertThat(outbox.nextAttemptAt(event.id())).isEqualTo(outbox.now.plusSeconds(10));
        assertThat(outbox.lastError(event.id())).contains("refused");

        // Not due yet: nothing is sent.
        outbox.advance(Duration.ofSeconds(9));
        assertThat(relay.deliverDue()).isZero();
        assertThat(sent).hasSize(1);

        outbox.advance(Duration.ofSeconds(1));
        assertThat(relay.deliverDue()).isZero();
        assertThat(outbox.all().getFirst().attempts()).isEqualTo(2);
        assertThat(outbox.nextAttemptAt(event.id())).isEqualTo(outbox.now.plusSeconds(20));

        outbox.advance(Duration.ofSeconds(20));
        assertThat(relay.deliverDue()).isEqualTo(1);

        assertThat(outbox.all()).isEmpty();
        assertThat(sent).containsExactly(event.id(), event.id(), event.id());
    }

    @Test
    @DisplayName("the wait stops growing at the longest allowed")
    void backoffIsCapped() {
        AuditOutbox.Event event = queue("a", 0);
        for (int i = 0; i < 6; i++) {
            failures.add(new RuntimeException("down"));
        }

        Duration last = Duration.ZERO;
        for (int i = 0; i < 6; i++) {
            relay.deliverDue();
            last = Duration.between(outbox.now, outbox.nextAttemptAt(event.id()));
            outbox.advance(last);
        }

        assertThat(last).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("after its last attempt an event is reported at WARN, kept, and tried again slowly")
    void parksAfterTheMaximum() {
        AuditOutbox.Event event = queue("credential.store", 0);
        for (int i = 0; i < 25; i++) {
            failures.add(new RuntimeException("analytics is down"));
        }

        for (int i = 0; i < 19; i++) {
            relay.deliverDue();
            outbox.advance(Duration.ofSeconds(30));
        }
        // Nineteen failures: still inside the attempts allowed, so nothing has been reported yet.
        assertThat(warnings()).isEmpty();

        relay.deliverDue();

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst()).contains(event.id().toString()).contains("credential.store").contains("20");
        assertThat(outbox.all()).hasSize(1);
        assertThat(outbox.nextAttemptAt(event.id())).isEqualTo(outbox.now.plus(Duration.ofHours(1)));

        // Never dropped: an hour later it is tried again, and when analytics is back it is delivered.
        failures.clear();
        outbox.advance(Duration.ofHours(1));
        assertThat(relay.deliverDue()).isEqualTo(1);
        assertThat(outbox.all()).isEmpty();
    }

    @Test
    @DisplayName("when analytics-service is down the rest of the pass is handed back untried")
    void destinationDownStopsThePass() {
        AuditOutbox.Event first = queue("a", 1);
        AuditOutbox.Event second = queue("b", 2);
        AuditOutbox.Event third = queue("c", 3);
        failures.add(new RuntimeException(new ConnectException("refused")));

        relay.deliverDue();

        assertThat(sent).containsExactly(first.id());
        List<AuditOutbox.Event> left = outbox.all();
        assertThat(left).extracting(AuditOutbox.Event::attempts).containsExactlyInAnyOrder(1, 0, 0);
        // The two it did not try are due again at once; the one that failed waits.
        assertThat(outbox.nextAttemptAt(second.id())).isEqualTo(outbox.now);
        assertThat(outbox.nextAttemptAt(third.id())).isEqualTo(outbox.now);
        assertThat(outbox.nextAttemptAt(first.id())).isAfter(outbox.now);
    }

    @Test
    @DisplayName("an event analytics-service refuses does not hold up the ones behind it")
    void refusedEventDoesNotBlockOthers() {
        AuditOutbox.Event bad = queue("a", 1);
        AuditOutbox.Event good = queue("b", 2);
        failures.add(http(HttpStatus.UNPROCESSABLE_ENTITY));

        int delivered = relay.deliverDue();

        assertThat(delivered).isEqualTo(1);
        assertThat(sent).containsExactly(bad.id(), good.id());
        assertThat(outbox.all()).extracting(AuditOutbox.Event::id).containsExactly(bad.id());
    }

    @Test
    @DisplayName("a server error or a refused token is the destination failing, not the event")
    void serverErrorStopsThePass() {
        queue("a", 1);
        AuditOutbox.Event second = queue("b", 2);
        failures.add(http(HttpStatus.BAD_GATEWAY));

        relay.deliverDue();

        assertThat(sent).hasSize(1);
        assertThat(outbox.nextAttemptAt(second.id())).isEqualTo(outbox.now);
    }

    @Test
    @DisplayName("switched off, the relay sends nothing and keeps everything")
    void disabled() {
        AuditOutboxRelay off = new AuditOutboxRelay(outbox, event -> sent.add(event.id()), AuditClientTest.properties(false));
        queue("a", 0);

        assertThat(off.deliverDue()).isZero();

        assertThat(sent).isEmpty();
        assertThat(outbox.all()).hasSize(1);
        off.shutdown();
    }

    @Test
    @DisplayName("an unreadable queue is reported and the next pass looks again")
    void unreadableQueue() {
        AuditOutbox broken = new InMemoryAuditOutbox() {
            @Override
            public synchronized List<Event> claimDue(int limit, Duration lease) {
                throw new IllegalStateException("database is down");
            }
        };
        AuditOutboxRelay relayOnBroken = new AuditOutboxRelay(broken, event -> {}, AuditClientTest.properties(true));

        assertThat(relayOnBroken.deliverDue()).isZero();
        assertThat(warnings()).anyMatch(message -> message.contains("could not be read"));
        relayOnBroken.shutdown();
    }

    @Test
    @DisplayName("a nudge sends what is waiting without waiting for the timer")
    void nudge() throws Exception {
        AuditOutbox.Event event = queue("a", 0);
        AtomicInteger calls = new AtomicInteger();
        AuditOutboxRelay nudged = new AuditOutboxRelay(
                outbox,
                e -> {
                    calls.incrementAndGet();
                    outbox.delivered(e.id());
                },
                AuditClientTest.properties(true));

        nudged.wake();
        nudged.wake();
        long deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(calls.get()).isEqualTo(1);
        assertThat(event.id()).isNotNull();
        nudged.shutdown();
    }
}
