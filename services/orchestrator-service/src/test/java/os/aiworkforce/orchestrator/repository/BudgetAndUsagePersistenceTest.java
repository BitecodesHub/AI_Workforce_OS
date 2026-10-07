package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;

/**
 * The budget row and the spend queries against the real schema: Flyway to the latest version, then
 * Hibernate's validation of the slimmer {@code Budget}, the native upsert run twice, every report
 * query over rows with known figures, and the export's paging.
 *
 * <p>The controller and guard tests stub these repositories, so they cannot see a query Hibernate
 * will not parse or SQL Postgres will not run. Opt-in, because it needs Docker: run with
 * {@code AIWOS_DATABASE_TESTS=true} (and, where the Ryuk image is not available,
 * {@code TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class BudgetAndUsagePersistenceTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID AGENT_1 = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID AGENT_2 = UUID.fromString("00000000-0000-7000-8000-0000000000c2");
    private static final UUID RUN_1 = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    private static final UUID RUN_2 = UUID.fromString("00000000-0000-7000-8000-0000000000d2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=orchestrator");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Budgets budgets;

    @Autowired
    private Usage usage;

    @Autowired
    private ModelPolicies policies;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    private void attempt(
            UUID org, UUID agent, UUID run, String provider, String model, String outcome, String cost, Instant at) {
        jdbc.update(
                "insert into llm_usage (id, org_id, agent_id, run_id, provider_id, model_id, outcome,"
                        + " prompt_tokens, cached_tokens, completion_tokens, cost, duration_ms, occurred_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, 100, 10, 50, ?, 1200, ?)",
                UUID.randomUUID(),
                org,
                agent,
                run,
                provider,
                model,
                outcome,
                new BigDecimal(cost),
                java.sql.Timestamp.from(at));
    }

    /** Known rows: ORG_A on two UTC days, two agents, two runs, three outcomes; one row of ORG_B. */
    private void seed() {
        Instant day1 = Instant.parse("2026-10-01T23:30:00Z");
        Instant day2 = Instant.parse("2026-10-02T00:30:00Z");
        attempt(ORG_A, AGENT_1, RUN_1, "groq", "llama", "SUCCEEDED", "0.50", day1);
        attempt(ORG_A, AGENT_1, RUN_1, "groq", "llama", "FAILED", "0.10", day1.plusSeconds(60));
        attempt(ORG_A, AGENT_2, RUN_2, "openrouter", "qwen", "SUCCEEDED", "0.25", day2);
        attempt(ORG_A, AGENT_2, RUN_2, "openrouter", "qwen", "SKIPPED", "0", day2.plusSeconds(60));
        attempt(ORG_A, null, null, "openrouter", "embed", "SUCCEEDED", "0.01", day2.plusSeconds(120));
        attempt(ORG_B, AGENT_1, UUID.randomUUID(), "groq", "llama", "SUCCEEDED", "9.00", day2);
    }

    @Test
    @DisplayName("the budget row is created once however often it is asked for, with no caps and stop at a cap")
    void insertIfAbsent() {
        assertThat(budgets.insertIfAbsent(ORG_A)).isEqualTo(1);
        assertThat(budgets.insertIfAbsent(ORG_A)).isZero();

        entities.clear();
        Budget budget = budgets.findForOrg(ORG_A).orElseThrow();
        assertThat(budget.getMonthlyCap()).isNull();
        assertThat(budget.getPerRunCap()).isNull();
        assertThat(budget.getPerAgentDailyCap()).isNull();
        assertThat(budget.getOnExhausted()).isEqualTo("stop");
        assertThat(budgets.findForOrg(ORG_B)).isEmpty();
    }

    @Test
    @DisplayName("caps are saved and read back, and the CHECK on onExhausted is met by the two values the API takes")
    void capsRoundTrip() {
        budgets.insertIfAbsent(ORG_A);
        Budget budget = budgets.findForOrg(ORG_A).orElseThrow();
        budget.setMonthlyCap(new BigDecimal("25.5000"));
        budget.setPerRunCap(new BigDecimal("0.1000"));
        budget.setPerAgentDailyCap(new BigDecimal("2"));
        budget.setOnExhausted(Budget.ON_EXHAUSTED_SANDBOX);
        budgets.saveAndFlush(budget);

        entities.clear();
        Budget reloaded = budgets.findForOrg(ORG_A).orElseThrow();
        assertThat(reloaded.getMonthlyCap()).isEqualByComparingTo("25.5");
        assertThat(reloaded.getPerRunCap()).isEqualByComparingTo("0.1");
        assertThat(reloaded.getPerAgentDailyCap()).isEqualByComparingTo("2");
        assertThat(reloaded.degradesToSandbox()).isTrue();
    }

    @Test
    @DisplayName("spend is summed per workspace, per agent and per run, and another workspace's rows never count")
    void spendQueries() {
        seed();

        Instant monthStart = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(usage.spendSince(ORG_A, monthStart)).isEqualByComparingTo("0.86");
        assertThat(usage.spendSince(ORG_A, Instant.parse("2026-10-02T00:00:00Z"))).isEqualByComparingTo("0.26");
        assertThat(usage.spendSince(ORG_B, monthStart)).isEqualByComparingTo("9.00");
        assertThat(usage.spendSinceByAgent(ORG_A, AGENT_1, monthStart)).isEqualByComparingTo("0.60");
        assertThat(usage.spendSinceByAgent(ORG_A, AGENT_1, Instant.parse("2026-10-02T00:00:00Z")))
                .isEqualByComparingTo("0");
        assertThat(usage.costForRun(ORG_A, RUN_1)).isEqualByComparingTo("0.60");
        assertThat(usage.costForRun(ORG_A, RUN_2)).isEqualByComparingTo("0.25");
        assertThat(usage.spendSince(UUID.randomUUID(), monthStart)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the report by day cuts the day in UTC, and the window is half open")
    void reportByDay() {
        seed();

        List<Object[]> rows = usage.reportByDay(
                ORG_A, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("2026-10-01");
        assertThat((BigDecimal) rows.get(0)[4]).isEqualByComparingTo("0.60");
        assertThat(((Number) rows.get(0)[6]).longValue()).isEqualTo(2);
        assertThat(rows.get(1)[0]).isEqualTo("2026-10-02");
        assertThat((BigDecimal) rows.get(1)[4]).isEqualByComparingTo("0.26");
        // The skipped candidate is counted as skipped, and not as an attempt.
        assertThat(((Number) rows.get(1)[6]).longValue()).isEqualTo(2);
        assertThat(((Number) rows.get(1)[8]).longValue()).isEqualTo(1);

        // The end is exclusive: a window ending at the first row's instant leaves it out.
        assertThat(usage.reportByDay(
                        ORG_A, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T23:30:00Z")))
                .isEmpty();
    }

    @Test
    @DisplayName("each grouping sums tokens and cost, with failed-attempt cost and skipped counts")
    void reportGroupings() {
        seed();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        List<Object[]> byModel = usage.reportByModel(ORG_A, from, to);
        assertThat(byModel).extracting(row -> row[0]).containsExactly("groq/llama", "openrouter/qwen", "openrouter/embed");
        Object[] groq = byModel.get(0);
        assertThat(((Number) groq[1]).longValue()).isEqualTo(200);
        assertThat(((Number) groq[2]).longValue()).isEqualTo(20);
        assertThat(((Number) groq[3]).longValue()).isEqualTo(100);
        assertThat((BigDecimal) groq[4]).isEqualByComparingTo("0.60");
        assertThat((BigDecimal) groq[5]).isEqualByComparingTo("0.10");
        assertThat(((Number) groq[7]).longValue()).isEqualTo(1);

        assertThat(usage.reportByProvider(ORG_A, from, to)).extracting(row -> row[0]).containsExactly("groq", "openrouter");

        List<Object[]> byAgent = usage.reportByAgent(ORG_A, from, to);
        assertThat(byAgent).hasSize(3);
        assertThat(byAgent).anySatisfy(row -> assertThat(row[0]).isNull());
        assertThat(byAgent.get(0)[0]).isEqualTo(AGENT_1);

        List<Object[]> byOutcome = usage.reportByOutcome(ORG_A, from, to);
        assertThat(byOutcome).extracting(row -> row[0]).containsExactlyInAnyOrder("SUCCEEDED", "FAILED", "SKIPPED");
    }

    @Test
    @DisplayName("the export walks a window a page at a time, in order, without skipping or repeating a row")
    void exportPaging() {
        Instant start = Instant.parse("2026-10-05T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            // Two rows share an instant, which a page boundary must not split into a skip or a repeat.
            attempt(ORG_A, AGENT_1, RUN_1, "groq", "llama", "SUCCEEDED", "0.01", start.plusSeconds(i / 2));
        }
        attempt(ORG_B, AGENT_1, UUID.randomUUID(), "groq", "llama", "SUCCEEDED", "0.01", start);

        Instant from = start;
        Instant to = start.plusSeconds(3600);
        Instant afterTime = from.minusNanos(1);
        UUID afterId = new UUID(0L, 0L);
        List<UUID> seen = new ArrayList<>();
        while (true) {
            List<Object[]> page = usage.exportPage(ORG_A, from, to, afterTime, afterId, PageRequest.of(0, 2));
            page.forEach(row -> seen.add((UUID) row[13]));
            if (page.size() < 2) {
                break;
            }
            afterTime = (Instant) page.get(page.size() - 1)[0];
            afterId = (UUID) page.get(page.size() - 1)[13];
        }

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every policy in a workspace comes back in one query, with its candidates, and no other workspace's")
    void policiesByOrg() {
        jdbc.update("insert into agents (id, org_id, key, name) values (?, ?, 'a1', 'Priya')", AGENT_1, ORG_A);
        policy(ORG_A, null, "sandbox", "sandbox-1");
        policy(ORG_A, AGENT_1, "sandbox", "sandbox-1");
        policy(ORG_B, null, "sandbox", "sandbox-1");
        entities.flush();
        entities.clear();

        List<ModelPolicyEntity> found = policies.findByOrgId(ORG_A);

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(policy -> {
            assertThat(policy.getOrgId()).isEqualTo(ORG_A);
            assertThat(policy.getCandidates()).hasSize(1);
        });
        assertThat(found).extracting(ModelPolicyEntity::getAgentId).containsExactlyInAnyOrder(null, AGENT_1);
    }

    /** Written with SQL: the slice does not enable auditing, which the entity's timestamps rely on. */
    private void policy(UUID org, UUID agent, String provider, String model) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into model_policies (id, org_id, agent_id) values (?, ?, ?)", id, org, agent);
        jdbc.update(
                "insert into model_policy_candidates (policy_id, position, provider_id, model_id) values (?, 0, ?, ?)",
                id,
                provider,
                model);
    }
}
