// @find: tests for approval queries persistence, repository, sweep returns only parked runs, pages the queue and the history, counts by decider, runtime settings store, ApprovalQueriesPersistenceTest, ApprovalQueriesPersistence
// @what: Tests for ApprovalQueriesPersistence in the orchestrator repository package (4 test methods).
// @flow: Exercises ApprovalQueriesPersistence
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.service.JdbcRuntimeConfigStore;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;

/**
 * The approval queries against the real schema: the resume sweep that starts from parked runs, the
 * paged queue and history, the counts, and the runtime-settings store's upserts.
 *
 * <p>Auditing is switched on, as it is in the running service: without it a row's {@code created_at} is
 * written as null and the table refuses it.
 *
 * <p>The sweep is HQL joining two entities with no association between them, and the store is plain
 * SQL with partial-index conflict targets; a mock proves neither. Opt-in, because it needs Docker:
 * run with {@code AIWOS_DATABASE_TESTS=true} (and, where the Ryuk image is not available, {@code
 * TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class ApprovalQueriesPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=orchestrator");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Approvals approvals;

    @Autowired
    private Runs runs;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    private Run run(String status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setAgentId(UUID.randomUUID());
        run.setAgentVersionId(UUID.randomUUID());
        run.setStatus(status);
        return runs.saveAndFlush(run);
    }

    private Approval approval(Run run, String status, Instant requestedAt, Instant decidedAt, Instant expiresAt) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(run.getOrgId());
        approval.setRunId(run.getId());
        approval.setAgentId(run.getAgentId());
        approval.setActionClass("OUTBOUND");
        approval.setTool("gmail.send_message");
        approval.setSummary("Send something outside the workspace using gmail.send_message");
        approval.setStatus(status);
        approval.setRequestedAt(requestedAt);
        approval.setDecidedAt(decidedAt);
        approval.setExpiresAt(expiresAt);
        return approvals.saveAndFlush(approval);
    }

    // @find: test sweep returns only parked runs, approval queries persistence
    @Test
    @DisplayName("the resume sweep returns an approved approval only while its run is still parked, and only its newest")
    void sweepReturnsOnlyParkedRuns() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minusSeconds(60);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

        Run parked = run("waiting_approval");
        Approval waitingToResume = approval(parked, "approved", now.minusSeconds(600), now.minusSeconds(300), tomorrow);

        // Already resumed: the run left waiting_approval, so its status is the record of it.
        Run resumed = run("running");
        approval(resumed, "approved", now.minusSeconds(600), now.minusSeconds(300), tomorrow);

        Run finished = run("completed");
        approval(finished, "approved", now.minusSeconds(600), now.minusSeconds(300), tomorrow);

        // Parked again on a newer request: the earlier grant must never resume it past the new one.
        Run parkedAgain = run("waiting_approval");
        approval(parkedAgain, "approved", now.minusSeconds(900), now.minusSeconds(800), tomorrow);
        approval(parkedAgain, "pending", now.minusSeconds(100), null, tomorrow);

        // Granted a moment ago: the approver's own resume is still on its way.
        Run justApproved = run("waiting_approval");
        approval(justApproved, "approved", now.minusSeconds(40), now.minusSeconds(10), tomorrow);

        // Parked, but the newest approval was rejected.
        Run rejected = run("waiting_approval");
        approval(rejected, "rejected", now.minusSeconds(600), now.minusSeconds(300), tomorrow);

        entities.clear();
        List<Approval> found = approvals.findApprovedAwaitingResume(cutoff, PageRequest.of(0, 50));

        assertThat(found).extracting(Approval::getId).containsExactly(waitingToResume.getId());

        // Once the grace period passes, the one granted a moment ago joins it, oldest decision first.
        List<Approval> later = approvals.findApprovedAwaitingResume(now.plusSeconds(60), PageRequest.of(0, 50));
        assertThat(later).extracting(Approval::getRunId).containsExactly(parked.getId(), justApproved.getId());
    }

    // @find: test pages the queue and the history, approval queries persistence
    @Test
    @DisplayName("the queue pages soonest-expiring first, the history newest decision first, per workspace and per agent")
    void pagesTheQueueAndTheHistory() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Run mine = run("waiting_approval");
        Approval late = approval(mine, "pending", now.minusSeconds(300), null, now.plusSeconds(7_200));
        Approval soon = approval(mine, "pending", now.minusSeconds(200), null, now.plusSeconds(600));
        Approval oldDecision = approval(mine, "approved", now.minusSeconds(900), now.minusSeconds(800), now.plusSeconds(60));
        Approval newDecision = approval(mine, "rejected", now.minusSeconds(700), now.minusSeconds(100), now.plusSeconds(60));
        Approval expired = approval(mine, "expired", now.minusSeconds(5_000), now.minusSeconds(400), now.minusSeconds(4_000));

        Run otherAgent = run("waiting_approval");
        Approval theirs = approval(otherAgent, "pending", now.minusSeconds(100), null, now.plusSeconds(60));

        Run elsewhere = new Run();
        elsewhere.setId(UUID.randomUUID());
        elsewhere.setOrgId(OTHER_ORG);
        elsewhere.setAgentId(UUID.randomUUID());
        elsewhere.setAgentVersionId(UUID.randomUUID());
        runs.saveAndFlush(elsewhere);
        approval(elsewhere, "pending", now.minusSeconds(100), null, now.plusSeconds(10));
        entities.clear();

        Sort queueOrder = Sort.by(Sort.Order.asc("expiresAt"), Sort.Order.asc("id"));
        assertThat(approvals.findByOrgIdAndStatusIn(ORG, List.of("pending"), PageRequest.of(0, 50, queueOrder)))
                .extracting(Approval::getId)
                .containsExactly(theirs.getId(), soon.getId(), late.getId());
        assertThat(approvals.findByOrgIdAndStatusIn(ORG, List.of("pending"), PageRequest.of(1, 2, queueOrder)))
                .extracting(Approval::getId)
                .containsExactly(late.getId());

        Sort historyOrder = Sort.by(Sort.Order.desc("decidedAt"), Sort.Order.desc("id"));
        assertThat(approvals.findByOrgIdAndStatusIn(
                        ORG, List.of("approved", "rejected", "expired", "cancelled"), PageRequest.of(0, 50, historyOrder)))
                .extracting(Approval::getId)
                .containsExactly(newDecision.getId(), expired.getId(), oldDecision.getId());

        assertThat(approvals.findByOrgIdAndStatusInAndAgentId(
                        ORG, List.of("pending"), mine.getAgentId(), PageRequest.of(0, 50, queueOrder)))
                .extracting(Approval::getId)
                .containsExactly(soon.getId(), late.getId());
        assertThat(approvals.findByOrgIdAndStatusInAndRunId(
                        ORG, List.of("pending"), otherAgent.getId(), PageRequest.of(0, 50, queueOrder)))
                .extracting(Approval::getId)
                .containsExactly(theirs.getId());
    }

    // @find: test counts by decider, approval queries persistence
    @Test
    @DisplayName("pending approvals are counted by who may decide them, without loading a payload")
    void countsByDecider() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID requester = UUID.randomUUID();
        Run first = run("waiting_approval");
        Approval a = approval(first, "pending", now, null, now.plusSeconds(60));
        a.setRequestedBy(requester);
        approvals.saveAndFlush(a);
        Approval b = approval(first, "pending", now, null, now.plusSeconds(60));
        b.setRequestedBy(requester);
        approvals.saveAndFlush(b);
        Approval c = approval(run("waiting_approval"), "pending", now, null, now.plusSeconds(60));
        c.setActionClass("DESTRUCTIVE");
        approvals.saveAndFlush(c);
        approval(run("waiting_approval"), "approved", now, now, now.plusSeconds(60));
        entities.clear();

        List<Object[]> rows = approvals.countPendingByDecider(ORG);

        assertThat(rows).hasSize(2);
        long total = rows.stream().mapToLong(row -> ((Number) row[3]).longValue()).sum();
        assertThat(total).isEqualTo(3);
        assertThat(rows)
                .anySatisfy(row -> {
                    assertThat(row[0]).isEqualTo("approval:decide");
                    assertThat(row[1]).isEqualTo(requester);
                    assertThat(row[2]).isEqualTo("OUTBOUND");
                    assertThat(((Number) row[3]).longValue()).isEqualTo(2);
                });
        assertThat(approvals.countPendingByDeciderForAgent(ORG, first.getAgentId())).hasSize(1);
    }

    // @find: test runtime settings store, approval queries persistence
    @Test
    @DisplayName("the runtime-settings store upserts a workspace value and the platform value separately, and clears one")
    void runtimeSettingsStore() {
        JdbcRuntimeConfigStore store = new JdbcRuntimeConfigStore(jdbc);

        assertThat(store.read("notifications.webhookUrl", ORG.toString())).isEmpty();
        assertThat(store.write("notifications.webhookUrl", ORG.toString(), "https://one.example/hook", "u1"))
                .isEmpty();
        // Twice, so the partial-index conflict target is exercised.
        assertThat(store.write("notifications.webhookUrl", ORG.toString(), "https://two.example/hook", "u2"))
                .contains("https://one.example/hook");
        store.write("notifications.webhookUrl", OTHER_ORG.toString(), "https://other.example/hook", "u3");
        store.write("notifications.webhookUrl", null, "https://platform.example/hook", "ops");
        store.write("notifications.webhookUrl", null, "https://platform2.example/hook", "ops");

        assertThat(store.read("notifications.webhookUrl", ORG.toString())).contains("https://two.example/hook");
        assertThat(store.read("notifications.webhookUrl", OTHER_ORG.toString())).contains("https://other.example/hook");
        assertThat(store.read("notifications.webhookUrl", null)).contains("https://platform2.example/hook");
        assertThat(store.readAll(ORG.toString()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.key()).isEqualTo("notifications.webhookUrl");
                    assertThat(row.updatedBy()).isEqualTo("u2");
                    assertThat(row.updatedAt()).isNotNull();
                });

        assertThat(store.clear("notifications.webhookUrl", ORG.toString(), "u1")).contains("https://two.example/hook");
        assertThat(store.read("notifications.webhookUrl", ORG.toString())).isEmpty();
        assertThat(store.read("notifications.webhookUrl", OTHER_ORG.toString())).isPresent();
        assertThat(store.read("notifications.webhookUrl", null)).isPresent();
    }
}
