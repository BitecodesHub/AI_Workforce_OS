package os.aiworkforce.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.analytics.TestDatabase;
import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.repository.AuditEvents;

/**
 * The audit chain against the real schema: Flyway through V2, Hibernate's validation, the advisory
 * lock, the trigger and the indexes - none of which a unit test can see.
 *
 * <p>Runs outside a test transaction, because the point is what real, separate transactions do to
 * each other. Opt-in, because it needs a database: see {@link TestDatabase}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditAppender.class, AuditChain.class, AuditSearch.class, AuditVerification.class})
@EnabledIf("os.aiworkforce.analytics.TestDatabase#available")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditChainPersistenceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    private AuditAppender appender;

    @Autowired
    private AuditSearch search;

    @Autowired
    private AuditVerification verification;

    @Autowired
    private AuditChain chain;

    @Autowired
    private AuditEvents events;

    @Autowired
    private JdbcTemplate jdbc;

    private static UUID newOrg() {
        return UUID.randomUUID();
    }

    private AuditAppender.Appended append(UUID org, String action, Map<String, Object> detail) {
        return append(org, "user-1", action, "succeeded", detail, null, null);
    }

    private AuditAppender.Appended append(
            UUID org,
            String actorId,
            String action,
            String outcome,
            Map<String, Object> detail,
            UUID eventId,
            Instant occurredAt) {
        return appender.append(new AuditAppender.Command(
                eventId,
                org,
                actorId,
                "USER",
                "boss-1",
                action,
                action.substring(0, action.indexOf('.')),
                "resource-" + actorId,
                outcome,
                detail,
                "req-1",
                occurredAt));
    }

    private int count(String where, Object... args) {
        Integer n = jdbc.queryForObject("select count(*) from audit_events where " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("the migration applied, and an append returns the sequence the database assigned")
    void appendReturnsSequence() {
        UUID org = newOrg();

        AuditAppender.Appended first = append(org, "member.role_change", Map.of("to", "owner"));
        AuditAppender.Appended second = append(org, "member.remove", Map.of());

        assertThat(first.sequence()).isPositive();
        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(first.duplicate()).isFalse();
        Long stored = jdbc.queryForObject(
                "select sequence from audit_events where id = ?", Long.class, second.id());
        assertThat(stored).isEqualTo(second.sequence());
    }

    @Test
    @DisplayName("each entry chains onto the one before it in its own workspace, and not onto another's")
    void chainsPerWorkspace() {
        UUID a = newOrg();
        UUID b = newOrg();

        AuditAppender.Appended a1 = append(a, "member.role_change", Map.of());
        AuditAppender.Appended b1 = append(b, "member.role_change", Map.of());
        AuditAppender.Appended a2 = append(a, "member.remove", Map.of());

        assertThat(previousHashOf(a1)).isNull();
        assertThat(previousHashOf(b1)).isNull();
        assertThat(previousHashOf(a2)).isEqualTo(a1.entryHash());
        assertThat(jdbc.queryForObject("select chain_key from audit_events where id = ?", String.class, a2.id()))
                .isEqualTo(a.toString());
        assertThat(verification.verifyWorkspace(a).verified()).isTrue();
        assertThat(verification.verifyWorkspace(a).checked()).isEqualTo(2);
        assertThat(verification.verifyWorkspace(b).checked()).isEqualTo(1);
    }

    @Test
    @DisplayName("entries with no workspace form the platform chain, which no workspace reads")
    void platformChain() {
        append(null, "auth.sign_in", Map.of("reason", "unknown address"));
        AuditAppender.Appended second = append(null, "auth.sign_in", Map.of());

        assertThat(jdbc.queryForObject("select chain_key from audit_events where id = ?", String.class, second.id()))
                .isEqualTo("platform");
        assertThat(verification.verifyChain("platform").verified()).isTrue();
        assertThat(search.find(newOrg(), AuditFilter.NONE, null, 0, 50)).isEmpty();
    }

    @Test
    @DisplayName("rows read back from the database verify: microseconds, nested detail and numbers survive")
    void readBackVerifies() {
        UUID org = newOrg();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("zeta", Map.of("b", List.of(1, 2.50, Map.of("y", true, "x", "é\u001e\"")), "a", 1.0E10));
        nested.put("alpha", 7);
        nested.put("none", null);

        // More precision than a TIMESTAMPTZ keeps, which is what a Linux clock produces.
        Instant nanos = Instant.parse("2026-10-06T01:02:03.123456789Z");
        append(org, "user-1", "role.update", "succeeded", nested, UUID.randomUUID(), nanos);
        append(org, "user-1", "role.update", "failed", Map.of("n", 1L), UUID.randomUUID(), Instant.parse("2026-10-06T01:02:04Z"));

        AuditChain.Verification result = verification.verifyWorkspace(org);

        assertThat(result.verified()).as(result.reason()).isTrue();
        assertThat(result.checked()).isEqualTo(2);
        AuditEvent stored = events.findFirstByChainKeyOrderBySequenceDesc(org.toString()).orElseThrow();
        assertThat(stored.getOccurredAt()).isEqualTo(Instant.parse("2026-10-06T01:02:04Z"));
        AuditEvent first = events
                .findByChainKeyAndSequenceGreaterThanOrderBySequenceAsc(
                        org.toString(), 0, PageRequest.of(0, 1))
                .getFirst();
        assertThat(first.getOccurredAt()).isEqualTo(nanos.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("an entry that claims to be from the far future is stamped with its arrival, a past one keeps its time")
    void occurredAtPolicy() {
        UUID org = newOrg();
        Instant before = Instant.now().minusSeconds(1);
        Instant past = Instant.parse("2025-01-01T00:00:00Z");

        append(org, "user-1", "role.create", "succeeded", Map.of(), UUID.randomUUID(), past);
        append(org, "user-1", "role.create", "succeeded", Map.of(), UUID.randomUUID(), Instant.now().plusSeconds(3600));

        List<AuditEvent> found = search.find(org, AuditFilter.NONE, null, 0, 10);
        assertThat(found.get(1).getOccurredAt()).isEqualTo(past);
        assertThat(found.get(0).getOccurredAt()).isAfter(before).isBefore(Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("concurrent appends from several threads produce one unbroken chain")
    void concurrentAppends() throws Exception {
        UUID org = newOrg();
        int threads = 8;
        int each = 12;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int worker = t;
                work.add(() -> {
                    for (int i = 0; i < each; i++) {
                        append(org, "worker-" + worker, "role.update", "succeeded", Map.of("i", i), UUID.randomUUID(), null);
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(work, 120, TimeUnit.SECONDS)) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }

        AuditChain.Verification result = verification.verifyWorkspace(org);
        assertThat(result.verified()).as(result.reason()).isTrue();
        assertThat(result.checked()).isEqualTo(threads * each);
        // No two entries share a predecessor: the chain did not fork.
        assertThat(count("chain_key = ? and previous_hash is not null", org.toString())).isEqualTo(threads * each - 1);
        assertThat(jdbc.queryForObject(
                        "select count(distinct previous_hash) from audit_events where chain_key = ?",
                        Integer.class,
                        org.toString()))
                .isEqualTo(threads * each - 1);
        assertThat(count("chain_key = ? and previous_hash is null", org.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("appends to different workspaces at once each keep their own unbroken chain")
    void concurrentWorkspaces() throws Exception {
        List<UUID> orgs = List.of(newOrg(), newOrg(), newOrg());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                UUID org = orgs.get(t % 3);
                work.add(() -> {
                    for (int i = 0; i < 8; i++) {
                        append(org, "member.remove", Map.of("i", i));
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(work, 120, TimeUnit.SECONDS)) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }

        for (UUID org : orgs) {
            AuditChain.Verification result = verification.verifyWorkspace(org);
            assertThat(result.verified()).as(result.reason()).isTrue();
            assertThat(result.checked()).isEqualTo(16);
        }
    }

    @Test
    @DisplayName("a repeated event id is idempotent: the second append returns the first entry")
    void repeatedEventIdIsIdempotent() throws Exception {
        UUID org = newOrg();
        UUID eventId = UUID.randomUUID();

        AuditAppender.Appended first = append(org, "user-1", "credential.store", "succeeded", Map.of("ref", "openai"), eventId, null);
        AuditAppender.Appended again = append(org, "user-1", "credential.store", "succeeded", Map.of("ref", "openai"), eventId, null);

        assertThat(again.duplicate()).isTrue();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.sequence()).isEqualTo(first.sequence());
        assertThat(again.entryHash()).isEqualTo(first.entryHash());
        assertThat(count("event_uuid = ?", eventId)).isEqualTo(1);

        // Two deliveries of one event racing each other, as when a sender retries a slow call.
        UUID racing = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<AuditAppender.Appended>> work = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                work.add(() -> append(org, "user-1", "credential.delete", "succeeded", Map.of(), racing, null));
            }
            List<Long> sequences = new ArrayList<>();
            for (Future<AuditAppender.Appended> done : pool.invokeAll(work, 60, TimeUnit.SECONDS)) {
                sequences.add(done.get().sequence());
            }
            assertThat(sequences).containsOnly(sequences.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("event_uuid = ?", racing)).isEqualTo(1);
        assertThat(verification.verifyWorkspace(org).verified()).isTrue();
    }

    @Test
    @DisplayName("the trigger blocks UPDATE, DELETE and TRUNCATE, for the owner of the table too")
    void triggerBlocksChanges() {
        UUID org = newOrg();
        AuditAppender.Appended entry = append(org, "member.remove", Map.of());

        assertThatThrownBy(() -> jdbc.update("update audit_events set outcome = 'failed' where id = ?", entry.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_events where id = ?", entry.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(count("id = ?", entry.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select outcome from audit_events where id = ?", String.class, entry.id()))
                .isEqualTo("succeeded");
    }

    @Test
    @DisplayName("somebody who disables the trigger and edits a row is found by the check, at that row")
    void tamperingIsFound() {
        UUID org = newOrg();
        append(org, "member.role_change", Map.of("to", "viewer"));
        AuditAppender.Appended target = append(org, "member.role_change", Map.of("to", "viewer"));
        append(org, "member.remove", Map.of());
        assertThat(verification.verifyWorkspace(org).verified()).isTrue();

        jdbc.execute("alter table audit_events disable trigger audit_events_no_change");
        try {
            // Fields the original hash did not cover, as well as one it did.
            jdbc.update("update audit_events set actor_kind = 'SYSTEM' where id = ?", target.id());
            AuditChain.Verification result = verification.verifyWorkspace(org);
            assertThat(result.verified()).isFalse();
            assertThat(result.firstBrokenSequence()).isEqualTo(target.sequence());

            jdbc.update("update audit_events set actor_kind = 'USER' where id = ?", target.id());
            jdbc.update("update audit_events set on_behalf_of = 'somebody-else' where id = ?", target.id());
            assertThat(verification.verifyWorkspace(org).firstBrokenSequence()).isEqualTo(target.sequence());

            jdbc.update("update audit_events set on_behalf_of = 'boss-1' where id = ?", target.id());
            assertThat(verification.verifyWorkspace(org).verified()).isTrue();

            jdbc.update("delete from audit_events where id = ?", target.id());
            AuditChain.Verification deleted = verification.verifyWorkspace(org);
            assertThat(deleted.verified()).isFalse();
        } finally {
            jdbc.execute("alter table audit_events enable trigger audit_events_no_change");
        }
    }

    @Test
    @DisplayName("the database refuses a second entry linked to the same predecessor, and a second start of a chain")
    void forksAreRefused() {
        UUID org = newOrg();
        AuditAppender.Appended first = append(org, "member.remove", Map.of());
        append(org, "member.remove", Map.of());

        // A row that names the first entry as its predecessor again: a fork.
        assertThatThrownBy(() -> insertRaw(org, first.entryHash(), 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A second row that claims to begin the chain.
        assertThatThrownBy(() -> insertRaw(org, null, 2)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(verification.verifyWorkspace(org).verified()).isTrue();
    }

    @Test
    @DisplayName("an outcome of locked is accepted, and anything else unknown is refused by the table")
    void outcomes() {
        UUID org = newOrg();

        assertThat(append(org, "user-1", "auth.sign_in", "locked", Map.of(), null, null).sequence()).isPositive();
        assertThatThrownBy(() -> insertRaw(org, "x", 2, "maybe")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("version 1 rows still verify, and the first newer entry chains onto the workspace's last old one")
    void legacyRowsStillVerify() throws Exception {
        UUID org = newOrg();
        String first = insertLegacy(org, null, "run.complete", Map.of("status", "completed"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 2);
        nested.put("b", 1);
        String second = insertLegacy(org, first, "run.fail", Map.of("failureReason", "nested", "n", nested));
        // A fork written before appends were serialised: names the same predecessor as the one above.
        insertLegacy(org, first, "run.complete", Map.of("status", "completed"));

        AuditAppender.Appended fresh = append(org, "member.role_change", Map.of("to", "owner"));

        assertThat(previousHashOf(fresh)).isNotNull();
        AuditChain.Verification result = verification.verifyWorkspace(org);
        assertThat(result.verified()).as(result.reason()).isTrue();
        assertThat(result.checked()).isEqualTo(4);
        assertThat(second).isNotEqualTo(first);

        AuditChain.Verification all = verification.verifyLegacy();
        assertThat(all.verified()).as(all.reason()).isTrue();
        assertThat(all.checked()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the filters narrow the log by actor, who it was for, action, resource, outcome and time")
    void filters() {
        UUID org = newOrg();
        UUID other = newOrg();
        Instant day1 = Instant.parse("2026-03-01T10:00:00Z");
        Instant day2 = Instant.parse("2026-03-02T10:00:00Z");
        Instant day3 = Instant.parse("2026-03-03T10:00:00Z");

        append(org, "alice", "member.role_change", "succeeded", Map.of(), UUID.randomUUID(), day1);
        append(org, "bob", "member.remove", "succeeded", Map.of(), UUID.randomUUID(), day2);
        append(org, "alice", "auth.sign_in", "failed", Map.of(), UUID.randomUUID(), day2);
        append(org, "carol", "auth.sign_in", "locked", Map.of(), UUID.randomUUID(), day3);
        append(other, "alice", "member.role_change", "succeeded", Map.of(), UUID.randomUUID(), day1);

        assertThat(actors(org, new AuditFilter(null, null, List.of(), null, null, null, null, null)))
                .containsExactly("carol", "alice", "bob", "alice");
        assertThat(actors(org, new AuditFilter("alice", null, List.of(), null, null, null, null, null)))
                .containsExactly("alice", "alice");
        assertThat(actors(org, new AuditFilter(null, "boss-1", List.of(), null, null, null, null, null))).hasSize(4);
        assertThat(actors(org, new AuditFilter(null, "nobody", List.of(), null, null, null, null, null))).isEmpty();
        assertThat(actors(org, new AuditFilter(null, null, List.of("member.remove"), null, null, null, null, null)))
                .containsExactly("bob");
        assertThat(actors(org, new AuditFilter(null, null, List.of("member.remove", "auth.sign_in"), null, null, null, null, null)))
                .containsExactly("carol", "alice", "bob");
        assertThat(actors(org, new AuditFilter(null, null, List.of(), "auth", null, null, null, null)))
                .containsExactly("carol", "alice");
        assertThat(actors(org, new AuditFilter(null, null, List.of(), null, "resource-bob", null, null, null)))
                .containsExactly("bob");
        assertThat(actors(org, new AuditFilter(null, null, List.of(), null, null, "locked", null, null)))
                .containsExactly("carol");
        assertThat(actors(org, new AuditFilter(null, null, List.of(), null, null, null, day2, day3)))
                .containsExactly("alice", "bob");
        assertThat(actors(org, new AuditFilter("alice", null, List.of("auth.sign_in"), null, null, "failed", day1, day3)))
                .containsExactly("alice");
        // Another workspace's entries are never in the answer, whatever the filter says.
        assertThat(search.find(other, AuditFilter.NONE, null, 0, 50)).hasSize(1);
    }

    @Test
    @DisplayName("paging by cursor and by offset walks the log newest first without repeating")
    void paging() {
        UUID org = newOrg();
        List<Long> sequences = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sequences.add(append(org, "member.remove", Map.of("i", i)).sequence());
        }

        List<AuditEvent> firstPage = search.find(org, AuditFilter.NONE, null, 0, 3);
        List<AuditEvent> byCursor = search.find(org, AuditFilter.NONE, firstPage.getLast().getSequence(), 0, 3);
        List<AuditEvent> byOffset = search.find(org, AuditFilter.NONE, null, 3, 3);

        assertThat(firstPage).extracting(AuditEvent::getSequence).containsExactly(sequences.get(6), sequences.get(5), sequences.get(4));
        assertThat(byCursor).extracting(AuditEvent::getSequence).containsExactly(sequences.get(3), sequences.get(2), sequences.get(1));
        assertThat(byOffset).extracting(AuditEvent::getSequence).isEqualTo(byCursor.stream().map(AuditEvent::getSequence).toList());
    }

    /* ---- helpers ------------------------------------------------------------------------------ */

    private List<String> actors(UUID org, AuditFilter filter) {
        return search.find(org, filter, null, 0, 50).stream().map(AuditEvent::getActorId).toList();
    }

    private String previousHashOf(AuditAppender.Appended entry) {
        return jdbc.queryForObject("select previous_hash from audit_events where id = ?", String.class, entry.id());
    }

    private void insertRaw(UUID org, String previousHash, int version) {
        insertRaw(org, previousHash, version, "succeeded");
    }

    private void insertRaw(UUID org, String previousHash, int version, String outcome) {
        jdbc.update(
                """
                insert into audit_events (id, org_id, actor_id, actor_kind, action, resource_type, outcome,
                                          previous_hash, entry_hash, hash_version, chain_key)
                values (?, ?, 'x', 'USER', 'a.b', 'a', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                org,
                outcome,
                previousHash,
                "raw-" + UUID.randomUUID(),
                version,
                org.toString());
    }

    /** A row exactly as the original code wrote it, for a workspace, with the original hash. */
    private String insertLegacy(UUID org, String previousHash, String action, Map<String, Object> detail)
            throws Exception {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Version 1 hashed the platform's sorted top level, and whatever order the nested maps had.
        String detailText = new ObjectMapper().writeValueAsString(new TreeMap<>(detail));
        String hash = chain.hash(previousHash, "agent-1", action, "run", "run-1", "succeeded", at.toString(), detailText);
        jdbc.update(
                """
                insert into audit_events (id, org_id, actor_id, actor_kind, on_behalf_of, action, resource_type,
                                          resource_id, outcome, detail, occurred_at, previous_hash, entry_hash,
                                          hash_version, chain_key)
                values (?, ?, 'agent-1', 'AGENT', 'boss-1', ?, 'run', 'run-1', 'succeeded', ?::jsonb, ?, ?, ?, 1, ?)
                """,
                UUID.randomUUID(),
                org,
                action,
                new ObjectMapper().writeValueAsString(detail),
                java.sql.Timestamp.from(at),
                previousHash,
                hash,
                org.toString());
        return hash;
    }
}
