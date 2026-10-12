// @find: tests for push subscriptions persistence, push_subscriptions SQL, upsert by workspace and endpoint, owner-scoped delete, failure counter, VAPID key row, PushSubscriptionsPersistenceTest
// @what: Opt-in database tests for PushSubscriptionStore's SQL and the V29 tables: the upsert on the workspace and endpoint (takeover only with the same keys), owner-scoped reads and deletes, paging, counters and trimming.
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.push.PushSubscriptionStore;
import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;

/**
 * The push tables against the real schema (V29).
 *
 * <p>Opt-in ({@code AIWOS_DATABASE_TESTS=true}), against Docker or the throwaway database
 * {@code AIWOS_TEST_DATABASE_URL} names; see {@link DatabaseForTests}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
@Import(PushSubscriptionStore.class)
class PushSubscriptionsPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a9");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b9");
    private static final UUID MAYA = UUID.fromString("00000000-0000-7000-8000-00000000001a");
    private static final UUID PRIYA = UUID.fromString("00000000-0000-7000-8000-00000000001b");
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/";

    private static DatabaseForTests.Connection connection;

    @BeforeAll
    static void database() {
        connection = DatabaseForTests.connection();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> connection.urlInSchema());
        registry.add("spring.datasource.username", () -> connection.user());
        registry.add("spring.datasource.password", () -> connection.password());
    }

    @Autowired
    private PushSubscriptionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void empty() {
        jdbc.update("delete from push_subscriptions");
    }

    private static final UUID SESSION = UUID.fromString("00000000-0000-7000-8000-00000000c0de");
    private static final UUID LATER_SESSION = UUID.fromString("00000000-0000-7000-8000-00000000c0df");

    private PushSubscription save(UUID orgId, UUID userId, String endpoint, String key, String auth) {
        return store.upsert(orgId, userId, SESSION, endpoint, key, auth, "Chrome on Android").orElseThrow();
    }

    @Test
    @DisplayName("the same browser again updates its one row; another person takes it over only with the same keys")
    void upsertOnTheWorkspaceAndEndpoint() {
        PushSubscription first = save(ORG, MAYA, FCM + "a", "key-1", "auth-1");
        store.recordFailure(first.id());

        PushSubscription again =
                store.upsert(ORG, MAYA, LATER_SESSION, FCM + "a", "key-2", "auth-2", "Chrome on Android").orElseThrow();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.p256dh()).isEqualTo("key-2");
        assertThat(again.sessionId()).isEqualTo(LATER_SESSION);
        assertThat(again.failureCount()).isZero();

        // Somebody who only knows the endpoint changes nothing.
        assertThat(store.upsert(ORG, PRIYA, SESSION, FCM + "a", "key-3", "auth-3", "")).isEmpty();
        assertThat(store.findByOrgAndEndpoint(ORG, FCM + "a")).hasValueSatisfying(row -> assertThat(row.userId()).isEqualTo(MAYA));

        // The next person on the same browser presents its keys, and takes it over.
        PushSubscription taken = store.upsert(ORG, PRIYA, SESSION, FCM + "a", "key-2", "auth-2", "").orElseThrow();
        assertThat(taken.id()).isEqualTo(first.id());
        assertThat(store.listFor(ORG, MAYA)).isEmpty();
        assertThat(store.listFor(ORG, PRIYA)).singleElement().satisfies(row -> assertThat(row.auth()).isEqualTo("auth-2"));
    }

    @Test
    @DisplayName("the same browser in two workspaces has a row in each, and neither moves")
    void oneRowPerWorkspace() {
        PushSubscription inA = save(ORG, MAYA, FCM + "shared", "k", "a");
        PushSubscription inB = save(OTHER_ORG, MAYA, FCM + "shared", "k", "a");
        save(ORG, MAYA, FCM + "shared", "k", "a");

        assertThat(inB.id()).isNotEqualTo(inA.id());
        assertThat(store.listFor(ORG, MAYA)).extracting(PushSubscription::id).containsExactly(inA.id());
        assertThat(store.listFor(OTHER_ORG, MAYA)).extracting(PushSubscription::id).containsExactly(inB.id());
    }

    @Test
    @DisplayName("reads and removals a person causes reach only their own rows in their own workspace")
    void ownerScoped() {
        PushSubscription mine = save(ORG, MAYA, FCM + "mine", "k", "a");
        PushSubscription phone = save(ORG, MAYA, FCM + "phone", "k", "a");
        PushSubscription hers = save(ORG, PRIYA, FCM + "hers", "k", "a");
        PushSubscription elsewhere = save(OTHER_ORG, MAYA, FCM + "elsewhere", "k", "a");

        assertThat(store.listFor(ORG, MAYA)).extracting(PushSubscription::endpoint).containsExactlyInAnyOrder(FCM + "mine", FCM + "phone");
        assertThat(store.deleteOwned(ORG, MAYA, FCM + "hers")).isEmpty();
        assertThat(store.deleteOwned(ORG, MAYA, FCM + "elsewhere")).isEmpty();
        assertThat(store.deleteOwned(ORG, MAYA, FCM + "mine")).contains(mine.id());
        assertThat(store.deleteOwnedById(ORG, MAYA, hers.id())).isFalse();
        assertThat(store.deleteOwnedById(ORG, MAYA, elsewhere.id())).isFalse();
        assertThat(store.deleteOwnedById(ORG, MAYA, phone.id())).isTrue();
        assertThat(store.findByOrgAndEndpoint(ORG, FCM + "hers")).isPresent();
        assertThat(store.findByOrgAndEndpoint(OTHER_ORG, FCM + "elsewhere")).isPresent();
        assertThat(store.subscribedUsersAfter(ORG, null, 10)).containsExactly(PRIYA);
        assertThat(store.listForUsers(ORG, List.of(MAYA, PRIYA))).extracting(PushSubscription::userId).containsExactly(PRIYA);
        assertThat(store.listForUsers(ORG, List.of())).isEmpty();
        assertThat(store.deleteAll(List.of(hers.id(), elsewhere.id()))).isEqualTo(2);
        assertThat(store.deleteAll(List.of())).isZero();
    }

    @Test
    @DisplayName("subscribed people are read a page at a time, in id order, after the last one of the page before")
    void subscribersArePaged() {
        save(ORG, PRIYA, FCM + "p1", "k", "a");
        save(ORG, MAYA, FCM + "m1", "k", "a");
        save(ORG, MAYA, FCM + "m2", "k", "a");

        assertThat(store.subscribedUsersAfter(ORG, null, 1)).containsExactly(MAYA);
        assertThat(store.subscribedUsersAfter(ORG, MAYA, 1)).containsExactly(PRIYA);
        assertThat(store.subscribedUsersAfter(ORG, PRIYA, 1)).isEmpty();
    }

    @Test
    @DisplayName("failures count up, a success resets them, and trimming keeps the newest devices")
    void countersAndTrimming() {
        PushSubscription row = save(ORG, MAYA, FCM + "x", "k", "a");
        assertThat(store.recordFailure(row.id())).isEqualTo(1);
        assertThat(store.recordFailure(row.id())).isEqualTo(2);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.recordSuccess(row.id(), now);
        assertThat(store.listFor(ORG, MAYA)).singleElement().satisfies(saved -> {
            assertThat(saved.failureCount()).isZero();
            assertThat(saved.lastSuccessAt()).isEqualTo(now);
        });
        store.delete(row.id());
        assertThat(store.recordFailure(row.id())).isZero();

        for (int i = 0; i < 4; i++) {
            save(ORG, MAYA, FCM + "device-" + i, "k", "a");
            jdbc.update("update push_subscriptions set created_at = now() - make_interval(mins => ?) where endpoint = ?", 10 - i,
                    FCM + "device-" + i);
        }
        assertThat(store.trimOldest(ORG, MAYA, 2)).isEqualTo(2);
        assertThat(store.listFor(ORG, MAYA)).extracting(PushSubscription::endpoint)
                .containsExactly(FCM + "device-3", FCM + "device-2");
        assertThat(store.countFor(ORG, MAYA)).isEqualTo(2);
    }

    @Test
    @DisplayName("the VAPID table holds exactly one row, and a second insert does nothing")
    void oneVapidRow() {
        jdbc.update("delete from push_vapid_keys");
        String insert = "insert into push_vapid_keys (id, public_key, private_key_encrypted) values (1, ?, ?) on conflict (id) do nothing";
        assertThat(jdbc.update(insert, "pub-1", "enc-1")).isEqualTo(1);
        assertThat(jdbc.update(insert, "pub-2", "enc-2")).isZero();
        assertThat(jdbc.queryForObject("select public_key from push_vapid_keys where id = 1", String.class)).isEqualTo("pub-1");
        assertThatThrownBy(() -> jdbc.update("insert into push_vapid_keys (id, public_key, private_key_encrypted) values (2, 'p', 'e')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
