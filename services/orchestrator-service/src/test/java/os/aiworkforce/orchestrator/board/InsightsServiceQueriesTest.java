// @find: tests for insights service queries, board, goals, tasks, runs, spend, cost per goal and waste, approvals, questions, failure reasons, InsightsServiceQueriesTest, InsightsServiceQueries
// @what: Tests for InsightsServiceQueries in the orchestrator board package (21 test methods).
// @flow: Exercises InsightsServiceQueries
package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.board.InsightsService.AgentRow;
import os.aiworkforce.orchestrator.board.InsightsService.Insights;
import os.aiworkforce.orchestrator.service.JdbcRuntimeConfigStore;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Every insight query against the real schema, over rows with known figures: Flyway to the latest
 * migration, then the service read through a fixed clock.
 *
 * <p>The unit tests of {@link InsightsServiceTest} cannot see a query Postgres will not run, a
 * column that is not there or a figure that counts the wrong rows, so this does. Opt-in, because
 * it needs a database: run with {@code AIWOS_DATABASE_TESTS=true}, against a container or a
 * scratch database ({@link DatabaseForTests}). The tables are emptied before each test, so never
 * point it at a database that holds anything.
 *
 * <p>The seed is one workspace with a quiet week: two agents that have done work, one that has
 * not, runs that completed, failed, were cancelled, were abandoned and are still going, one on the
 * offline sandbox and one on a real model the catalogue has no price for, approvals and questions
 * with known waits, and ratings. A second workspace has rows too, and none of them may show.
 */
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class InsightsServiceQueriesTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID EMPTY = UUID.fromString("00000000-0000-7000-8000-0000000000c3");
    private static final UUID ALPHA = UUID.fromString("00000000-0000-7000-8000-00000000a001");
    private static final UUID BETA = UUID.fromString("00000000-0000-7000-8000-00000000a002");
    private static final UUID GAMMA = UUID.fromString("00000000-0000-7000-8000-00000000a003");
    private static final UUID PERSON = UUID.fromString("00000000-0000-7000-8000-00000000f001");

    /** Wednesday the 15th at noon: a 7-day window is 9 October at midnight to now. */
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static DriverManagerDataSource dataSource;

    private JdbcTemplate jdbc;
    private InsightsService service;

    @BeforeAll
    static void database() {
        DatabaseForTests.Connection connection = DatabaseForTests.connection();
        dataSource = new DriverManagerDataSource(connection.url(), connection.user(), connection.password());
        // The service's SQL names no schema, as in the running service, whose connections start in it.
        dataSource.setSchema("orchestrator");
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("orchestrator")
                .defaultSchema("orchestrator")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of(
                "chat_message_feedback",
                "chat_messages",
                "conversations",
                "run_questions",
                "approvals",
                "llm_usage",
                "runs",
                "tasks",
                "goals",
                "agents",
                "runtime_settings")) {
            jdbc.update("delete from " + table);
        }
        service = new InsightsService(jdbc, new JdbcRuntimeConfigStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));
        seedWorkspace();
        seedOtherWorkspace();
    }

    // ---- Seed ------------------------------------------------------------------------------

    private static Timestamp at(String monthDayTime) {
        return Timestamp.from(Instant.parse("2026-" + monthDayTime + ":00Z"));
    }

    private void agent(UUID org, UUID id, String name) {
        jdbc.update(
                "insert into agents (id, org_id, key, name) values (?, ?, ?, ?)", id, org, name.toLowerCase(), name);
    }

    private UUID goal(UUID org, String source, String status, String completedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into goals (id, org_id, title, source, status, completed_at) values (?, ?, 'goal', ?, ?, ?)",
                id,
                org,
                source,
                status,
                completedAt == null ? null : at(completedAt));
        return id;
    }

    private UUID task(UUID org, UUID goal, UUID agent, String status, String completedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into tasks (id, org_id, goal_id, agent_id, title, instruction, status, completed_at)"
                        + " values (?, ?, ?, ?, 'task', 'do it', ?, ?)",
                id,
                org,
                goal,
                agent,
                status,
                completedAt == null ? null : at(completedAt));
        return id;
    }

    private UUID run(
            UUID org, UUID task, UUID agent, String status, String startedAt, String cost, int tokens, String why) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into runs (id, org_id, task_id, agent_id, agent_version_id, status, started_at,"
                        + " total_prompt_tokens, total_completion_tokens, total_cost, failure_reason)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                id,
                org,
                task,
                agent,
                UUID.randomUUID(),
                status,
                at(startedAt),
                tokens,
                new BigDecimal(cost),
                why);
        return id;
    }

    private void usage(UUID org, UUID agent, UUID run, String provider, String outcome, String cost, String when) {
        jdbc.update(
                "insert into llm_usage (id, org_id, agent_id, run_id, provider_id, model_id, outcome, cost, occurred_at)"
                        + " values (?, ?, ?, ?, ?, 'm', ?, ?, ?)",
                UUID.randomUUID(),
                org,
                agent,
                run,
                provider,
                outcome,
                new BigDecimal(cost),
                at(when));
    }

    private void approval(UUID run, UUID agent, String status, String requested, String decided) {
        jdbc.update(
                "insert into approvals (id, org_id, run_id, agent_id, action_class, summary, status, requested_at,"
                        + " decided_at, expires_at) values (?, ?, ?, ?, 'external_write', 'send it', ?, ?, ?, ?)",
                UUID.randomUUID(),
                ORG,
                run,
                agent,
                status,
                at(requested),
                decided == null ? null : at(decided),
                at("10-30T00:00"));
    }

    private void question(UUID run, UUID agent, String status, String created, String answered) {
        jdbc.update(
                "insert into run_questions (id, org_id, run_id, agent_id, tool_call_id, questions, status, created_at,"
                        + " answered_at, expires_at) values (?, ?, ?, ?, 'call', '[]'::jsonb, ?, ?, ?, ?)",
                UUID.randomUUID(),
                ORG,
                run,
                agent,
                status,
                at(created),
                answered == null ? null : at(answered),
                at("10-30T00:00"));
    }

    private UUID answerBy(UUID conversation, int position, UUID agent) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into chat_messages (id, org_id, conversation_id, position, author_kind, agent_id, kind)"
                        + " values (?, ?, ?, ?, 'agent', ?, 'answer')",
                id,
                ORG,
                conversation,
                position,
                agent);
        return id;
    }

    private void rating(UUID conversation, UUID message, UUID agent, int rating, String when) {
        jdbc.update(
                "insert into chat_message_feedback (id, org_id, conversation_id, message_id, user_id, agent_id, rating,"
                        + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                ORG,
                conversation,
                message,
                PERSON,
                agent,
                rating,
                at(when),
                at(when));
    }

    private void setting(UUID org, String key, String value) {
        jdbc.update("insert into runtime_settings (key, org_id, value, updated_at) values (?, ?, ?, now())", key, org, value);
    }

    private void seedWorkspace() {
        agent(ORG, ALPHA, "Alpha");
        agent(ORG, BETA, "Beta");
        agent(ORG, GAMMA, "Gamma");

        // Goals and the tasks and runs under them, in the week to 15 October.
        UUID g1 = goal(ORG, "chat", "completed", "10-14T10:00");
        UUID t1 = task(ORG, g1, ALPHA, "completed", "10-14T10:00");
        UUID r1 = run(ORG, t1, ALPHA, "completed", "10-14T09:50", "0.30", 100, null);
        usage(ORG, ALPHA, r1, "groq", "SUCCEEDED", "0.30", "10-14T09:51");

        // A retry: the first run failed, the second completed. One goal, one task, two runs.
        UUID g2 = goal(ORG, "chat", "completed", "10-14T15:00");
        UUID t2 = task(ORG, g2, ALPHA, "completed", "10-14T15:00");
        UUID r2a = run(ORG, t2, ALPHA, "failed", "10-14T14:00", "0.10", 100, "Provider timed out");
        UUID r2b = run(ORG, t2, ALPHA, "completed", "10-14T14:30", "0.20", 100, null);
        usage(ORG, ALPHA, r2a, "groq", "FAILED", "0.10", "10-14T14:01");
        usage(ORG, ALPHA, r2b, "groq", "SUCCEEDED", "0.20", "10-14T14:31");

        // Answered by the offline sandbox alone: free, and never counted as value.
        UUID g3 = goal(ORG, "schedule", "completed", "10-12T08:00");
        UUID t3 = task(ORG, g3, BETA, "completed", "10-12T08:00");
        UUID r3 = run(ORG, t3, BETA, "completed", "10-12T07:50", "0", 15, null);
        usage(ORG, BETA, r3, "sandbox", "SUCCEEDED", "0", "10-12T07:51");

        UUID g4 = goal(ORG, "manual", "failed", "10-12T09:00");
        UUID t4 = task(ORG, g4, BETA, "failed", "10-12T09:00");
        UUID r4 = run(ORG, t4, BETA, "failed", "10-12T08:30", "0.05", 100, "Provider timed out");
        usage(ORG, BETA, r4, "groq", "FAILED", "0.05", "10-12T08:31");

        UUID g5 = goal(ORG, "manual", "cancelled", "10-11T09:00");
        UUID t5 = task(ORG, g5, ALPHA, "cancelled", "10-11T09:00");
        UUID r5 = run(ORG, t5, ALPHA, "cancelled", "10-11T08:50", "0.02", 100, null);
        usage(ORG, ALPHA, r5, "groq", "SUCCEEDED", "0.02", "10-11T08:51");

        // A real model the catalogue has no price for: tokens were used and the cost is nil.
        UUID g8 = goal(ORG, "chat", "completed", "10-13T10:00");
        UUID t8 = task(ORG, g8, BETA, "completed", "10-13T10:00");
        UUID r8 = run(ORG, t8, BETA, "completed", "10-13T10:00", "0", 100, null);
        usage(ORG, BETA, r8, "groq", "SUCCEEDED", "0", "10-13T10:01");

        // Runs with no task: still going, two that completed, one abandoned.
        UUID r7 = run(ORG, null, ALPHA, "running", "10-15T09:00", "0", 0, null);
        UUID r9 = run(ORG, null, ALPHA, "completed", "10-13T11:00", "0.10", 100, null);
        UUID r10 = run(ORG, null, ALPHA, "completed", "10-13T12:00", "0.10", 100, null);
        usage(ORG, ALPHA, r9, "groq", "SUCCEEDED", "0.10", "10-13T11:01");
        usage(ORG, ALPHA, r10, "groq", "SUCCEEDED", "0.10", "10-13T12:01");
        run(ORG, null, BETA, "abandoned", "10-10T10:00", "0", 0, "Worker lost");
        // An embedding: spend with no run.
        usage(ORG, null, null, "openrouter", "SUCCEEDED", "0.01", "10-13T08:00");

        // The week before: one completed goal and its run.
        UUID g6 = goal(ORG, "chat", "completed", "10-04T09:00");
        UUID t6 = task(ORG, g6, ALPHA, "completed", "10-04T09:00");
        UUID r6 = run(ORG, t6, ALPHA, "completed", "10-04T09:00", "0.40", 100, null);
        usage(ORG, ALPHA, r6, "groq", "SUCCEEDED", "0.40", "10-04T09:01");

        // Before both windows.
        goal(ORG, "chat", "completed", "10-01T09:00");

        // Approvals: waits of 10 minutes, an hour and 2 minutes; one expired, one still open.
        approval(r1, ALPHA, "approved", "10-12T10:00", "10-12T10:10");
        approval(r9, ALPHA, "approved", "10-13T10:00", "10-13T11:00");
        approval(r10, ALPHA, "rejected", "10-13T12:00", "10-13T12:02");
        approval(r4, BETA, "expired", "10-10T10:00", "10-11T10:00");
        approval(r7, ALPHA, "pending", "10-15T09:00", null);

        // Questions: two answered after 5 and 10 minutes, one open.
        question(r9, ALPHA, "answered", "10-13T10:00", "10-13T10:05");
        question(r10, ALPHA, "answered", "10-13T11:00", "10-13T11:10");
        question(r7, ALPHA, "pending", "10-14T09:00", null);

        // Ratings: Alpha one up and one down, Beta one up.
        UUID conversation = UUID.randomUUID();
        jdbc.update("insert into conversations (id, org_id) values (?, ?)", conversation, ORG);
        rating(conversation, answerBy(conversation, 1, ALPHA), ALPHA, 1, "10-14T10:00");
        rating(conversation, answerBy(conversation, 2, ALPHA), ALPHA, -1, "10-14T11:00");
        rating(conversation, answerBy(conversation, 3, BETA), BETA, 1, "10-13T10:00");
    }

    /** Rows that must never reach ORG's figures. */
    private void seedOtherWorkspace() {
        UUID agent = UUID.randomUUID();
        agent(OTHER, agent, "Intruder");
        UUID g = goal(OTHER, "chat", "completed", "10-14T10:00");
        UUID t = task(OTHER, g, agent, "completed", "10-14T10:00");
        UUID r = run(OTHER, t, agent, "completed", "10-14T09:50", "9.00", 100, null);
        usage(OTHER, agent, r, "groq", "SUCCEEDED", "9.00", "10-14T09:51");
        run(OTHER, null, agent, "failed", "10-14T09:55", "1.00", 100, "Their own failure");
    }

    private Insights week() {
        return service.insights(ORG, "7d");
    }

    private AgentRow row(InsightsService.AgentInsights agents, UUID id) {
        return agents.agents().stream()
                .filter(candidate -> candidate.agentId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    // ---- Work done -------------------------------------------------------------------------

    // @find: test goals, insights service queries
    @Test
    @DisplayName("goals are counted on the day they finished, by outcome and by where they came from")
    void goals() {
        Insights insights = week();

        assertThat(insights.goals().completed()).isEqualTo(4);
        assertThat(insights.goals().failed()).isEqualTo(1);
        // A goal a person stopped is counted apart: neither done nor failed.
        assertThat(insights.goals().cancelled()).isEqualTo(1);
        assertThat(insights.goals().bySource())
                .extracting(InsightsService.SourceCount::source, InsightsService.SourceCount::completed, InsightsService.SourceCount::failed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chat", 3L, 0L),
                        org.assertj.core.groups.Tuple.tuple("manual", 0L, 1L),
                        org.assertj.core.groups.Tuple.tuple("schedule", 1L, 0L));
        // Seven day buckets, oldest first, the quiet ones as zeros.
        assertThat(insights.goals().byDay())
                .extracting(InsightsService.DayCount::day)
                .containsExactly(
                        "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15");
        Map<String, long[]> byDay = new java.util.HashMap<>();
        insights.goals().byDay().forEach(day -> byDay.put(day.day(), new long[] {day.completed(), day.failed()}));
        assertThat(byDay.get("2026-10-12")).containsExactly(1, 1);
        assertThat(byDay.get("2026-10-13")).containsExactly(1, 0);
        assertThat(byDay.get("2026-10-14")).containsExactly(2, 0);
        assertThat(byDay.get("2026-10-09")).containsExactly(0, 0);
    }

    // @find: test tasks, insights service queries
    @Test
    @DisplayName("task success counts finished tasks, and 'without retry' counts those that needed one run")
    void tasks() {
        InsightsService.TaskFigures tasks = week().tasks();

        assertThat(tasks.completed()).isEqualTo(4);
        assertThat(tasks.failed()).isEqualTo(1);
        assertThat(tasks.successRate()).isEqualByComparingTo("0.8");
        assertThat(tasks.completedWithoutRetry()).isEqualTo(3);
        assertThat(tasks.withoutRetryRate()).isEqualByComparingTo("0.75");
    }

    // @find: test runs, insights service queries
    @Test
    @DisplayName("runs are counted by how they ended, with the ones still going apart and unpriced ones named")
    void runs() {
        InsightsService.RunFigures runs = week().runs();

        assertThat(runs.total()).isEqualTo(11);
        assertThat(runs.finished()).isEqualTo(10);
        assertThat(runs.active()).isEqualTo(1);
        assertThat(runs.byStatus())
                .containsEntry("completed", 6L)
                .containsEntry("failed", 2L)
                .containsEntry("cancelled", 1L)
                .containsEntry("abandoned", 1L);
        assertThat(runs.unpriced()).isEqualTo(1);
        assertThat(runs.sandboxOnly()).isEqualTo(1);
    }

    // ---- Spend -----------------------------------------------------------------------------

    // @find: test spend, insights service queries
    @Test
    @DisplayName("spend is summed from the usage table by day, failed attempts and embeddings included")
    void spend() {
        InsightsService.SpendFigures spend = week().spend();

        assertThat(spend.total()).isEqualByComparingTo("0.88");
        assertThat(spend.byDay()).hasSize(7);
        Map<String, BigDecimal> byDay = new java.util.HashMap<>();
        spend.byDay().forEach(day -> byDay.put(day.day(), day.cost()));
        assertThat(byDay.get("2026-10-14")).isEqualByComparingTo("0.60");
        assertThat(byDay.get("2026-10-13")).isEqualByComparingTo("0.21");
        assertThat(byDay.get("2026-10-12")).isEqualByComparingTo("0.05");
        assertThat(byDay.get("2026-10-11")).isEqualByComparingTo("0.02");
        assertThat(byDay.get("2026-10-15")).isEqualByComparingTo("0");
        assertThat(spend.basis()).isEqualTo(InsightsService.BASIS);
    }

    // @find: test cost per goal and waste, insights service queries
    @Test
    @DisplayName("cost per goal leaves out sandbox and unpriced goals and says how many; waste is what failed runs cost")
    void costPerGoalAndWaste() {
        InsightsService.SpendFigures spend = week().spend();

        assertThat(spend.costedGoals()).isEqualTo(2);
        assertThat(spend.sandboxGoals()).isEqualTo(1);
        assertThat(spend.unpricedGoals()).isEqualTo(1);
        // Goal 1 cost 0.30 and goal 2, with its retry, 0.10 + 0.20.
        assertThat(spend.costPerCompletedGoal()).isEqualByComparingTo("0.30");
        // The failed, the cancelled and the abandoned: 0.10 + 0.05 + 0.02 + 0.
        assertThat(spend.failedOrCancelledSpend()).isEqualByComparingTo("0.17");
        assertThat(spend.unpricedRuns()).isEqualTo(1);
    }

    // ---- People ----------------------------------------------------------------------------

    // @find: test approvals, insights service queries
    @Test
    @DisplayName("approvals are counted by outcome, and the wait is the time to a person's decision, not to an expiry")
    void approvals() {
        InsightsService.ApprovalFigures approvals = week().approvals();

        assertThat(approvals.raised()).isEqualTo(5);
        assertThat(approvals.approved()).isEqualTo(2);
        assertThat(approvals.rejected()).isEqualTo(1);
        assertThat(approvals.expired()).isEqualTo(1);
        assertThat(approvals.pending()).isEqualTo(1);
        // Waits of 120, 600 and 3600 seconds: the middle one, and 600 + 0.8 * 3000 at the 90th.
        assertThat(approvals.medianDecisionSeconds()).isEqualTo(600);
        assertThat(approvals.p90DecisionSeconds()).isEqualTo(3000);
    }

    // @find: test questions, insights service queries
    @Test
    @DisplayName("questions are counted per 100 runs, with the median time a person took to answer")
    void questions() {
        InsightsService.QuestionFigures questions = week().questions();

        assertThat(questions.asked()).isEqualTo(3);
        assertThat(questions.answered()).isEqualTo(2);
        assertThat(questions.per100Runs()).isEqualByComparingTo("27.3");
        assertThat(questions.medianAnswerSeconds()).isEqualTo(450);
    }

    // @find: test failure reasons, insights service queries
    @Test
    @DisplayName("the top failure reasons are the ones that happened most, failed and abandoned runs alike")
    void failureReasons() {
        assertThat(week().failureReasons())
                .extracting(InsightsService.FailureReason::reason, InsightsService.FailureReason::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Provider timed out", 2L),
                        org.assertj.core.groups.Tuple.tuple("Worker lost", 1L));
    }

    // ---- Against the week before -----------------------------------------------------------

    // @find: test deltas, insights service queries
    @Test
    @DisplayName("each figure is compared with the same length of time before, and a change from nothing has no percentage")
    void deltas() {
        Map<String, InsightsService.Delta> deltas = week().deltas();

        InsightsService.Delta goals = deltas.get("goalsCompleted");
        assertThat(goals.current()).isEqualByComparingTo("4");
        assertThat(goals.previous()).isEqualByComparingTo("1");
        assertThat(goals.change()).isEqualByComparingTo("3");
        assertThat(goals.changePercent()).isEqualByComparingTo("300.0");

        InsightsService.Delta spend = deltas.get("spend");
        assertThat(spend.previous()).isEqualByComparingTo("0.40");
        assertThat(spend.change()).isEqualByComparingTo("0.48");
        assertThat(spend.changePercent()).isEqualByComparingTo("120.0");

        InsightsService.Delta failed = deltas.get("goalsFailed");
        assertThat(failed.previous()).isEqualByComparingTo("0");
        assertThat(failed.change()).isEqualByComparingTo("1");
        assertThat(failed.changePercent()).isNull();

        // Nobody decided an approval in the week before: there is nothing to compare the wait with.
        assertThat(deltas.get("medianApprovalSeconds").previous()).isNull();
        assertThat(deltas.get("medianApprovalSeconds").change()).isNull();
    }

    // @find: test windows, insights service queries
    @Test
    @DisplayName("the window is the days asked for and the one before is as long and ends the same time of day")
    void windows() {
        Insights month = service.insights(ORG, "30d");
        assertThat(month.window()).isEqualTo("30d");
        assertThat(month.from()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(month.goals().byDay()).hasSize(30);

        Insights week = week();
        assertThat(week.from()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(week.to()).isEqualTo(NOW);
        assertThat(week.previousFrom()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(week.previousTo()).isEqualTo(Instant.parse("2026-10-08T12:00:00Z"));

        assertThatThrownBy(() -> service.insights(ORG, "14d"))
                .isInstanceOfSatisfying(
                        ApiException.class, refused -> assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ---- Estimated value -------------------------------------------------------------------

    // @find: test no value without inputs, insights service queries
    @Test
    @DisplayName("with no inputs the estimate is absent, not zero")
    void noValueWithoutInputs() {
        assertThat(week().value()).isNull();
        AgentRow alpha = row(service.agents(ORG, "7d"), ALPHA);
        assertThat(alpha.minutesPerTask()).isNull();
        assertThat(alpha.hoursReturned()).isNull();
    }

    // @find: test value from inputs, insights service queries
    @Test
    @DisplayName("minutes per task and an hourly rate give hours, what a person would have cost and the net, sandbox work left out")
    void valueFromInputs() {
        setting(ORG, "value.hourlyRate", "60");
        setting(ORG, "value.minutesPerTask." + ALPHA, "30");

        InsightsService.ValueFigures value = week().value();

        assertThat(value.label()).isEqualTo("Estimate from your inputs (current values)");
        // Alpha finished two tasks. Beta's sandbox task is never counted, and its other task has no estimate.
        assertThat(value.completedTasks()).isEqualTo(2);
        assertThat(value.hoursReturned()).isEqualByComparingTo("1.00");
        assertThat(value.humanEquivalentCost()).isEqualByComparingTo("60.00");
        // Alpha's runs cost 0.82 in the week.
        assertThat(value.netValue()).isEqualByComparingTo("59.18");
        assertThat(value.agentsEstimated()).isEqualTo(1);
        assertThat(value.agentsNotEstimated()).isEqualTo(1);
        assertThat(week().deltas().get("hoursReturned").previous()).isEqualByComparingTo("0.50");
    }

    // @find: test hours without rate, insights service queries
    @Test
    @DisplayName("minutes without a rate give the hours and leave what they are worth absent")
    void hoursWithoutRate() {
        setting(ORG, "value.minutesPerTask." + ALPHA, "30");

        InsightsService.ValueFigures value = week().value();

        assertThat(value.hoursReturned()).isEqualByComparingTo("1.00");
        assertThat(value.humanEquivalentCost()).isNull();
        assertThat(value.netValue()).isNull();
    }

    // @find: test malformed inputs, insights service queries
    @Test
    @DisplayName("inputs that are not numbers or ids are ignored rather than failing the page")
    void malformedInputs() {
        setting(ORG, "value.hourlyRate", "plenty");
        setting(ORG, "value.minutesPerTask.not-an-id", "30");
        setting(ORG, "value.minutesPerTask." + BETA, "0");

        assertThat(week().value()).isNull();
    }

    // ---- Per agent -------------------------------------------------------------------------

    // @find: test agent success needs five runs, insights service queries
    @Test
    @DisplayName("an agent with five finished runs has a success rate and one with fewer has none")
    void agentSuccessNeedsFiveRuns() {
        InsightsService.AgentInsights agents = service.agents(ORG, "7d");

        assertThat(agents.agents()).extracting(AgentRow::name).containsExactly("Alpha", "Beta", "Gamma");

        AgentRow alpha = row(agents, ALPHA);
        assertThat(alpha.runs()).isEqualTo(7);
        assertThat(alpha.finishedRuns()).isEqualTo(6);
        assertThat(alpha.completed()).isEqualTo(4);
        assertThat(alpha.failed()).isEqualTo(1);
        assertThat(alpha.cancelled()).isEqualTo(1);
        assertThat(alpha.enoughRuns()).isTrue();
        assertThat(alpha.successRate()).isEqualByComparingTo("0.6667");
        assertThat(alpha.totalCost()).isEqualByComparingTo("0.82");
        assertThat(alpha.avgCostPerCompleted()).isEqualByComparingTo("0.175");
        assertThat(alpha.rejectedApprovals()).isEqualTo(1);
        assertThat(alpha.lastActive()).isEqualTo(Instant.parse("2026-10-15T09:00:00Z"));

        AgentRow beta = row(agents, BETA);
        assertThat(beta.finishedRuns()).isEqualTo(4);
        assertThat(beta.enoughRuns()).isFalse();
        assertThat(beta.successRate()).isNull();
    }

    // @find: test agent unpriced and sandbox, insights service queries
    @Test
    @DisplayName("unpriced and sandbox runs are named on the agent, never shown as free")
    void agentUnpricedAndSandbox() {
        AgentRow beta = row(service.agents(ORG, "7d"), BETA);

        assertThat(beta.unpricedRuns()).isEqualTo(1);
        assertThat(beta.sandboxRuns()).isEqualTo(1);
        // No completed run of Beta's has a price, so there is no average to give.
        assertThat(beta.avgCostPerCompleted()).isNull();
    }

    // @find: test agent satisfaction, insights service queries
    @Test
    @DisplayName("satisfaction is thumbs up over ratings, from the ratings given in the window")
    void agentSatisfaction() {
        InsightsService.AgentInsights agents = service.agents(ORG, "7d");

        AgentRow alpha = row(agents, ALPHA);
        assertThat(alpha.ratings()).isEqualTo(2);
        assertThat(alpha.thumbsDown()).isEqualTo(1);
        assertThat(alpha.satisfactionRate()).isEqualByComparingTo("0.5");
        assertThat(row(agents, BETA).satisfactionRate()).isEqualByComparingTo("1");
        assertThat(row(agents, GAMMA).satisfactionRate()).isNull();
    }

    // @find: test quiet agent, insights service queries
    @Test
    @DisplayName("an agent with no runs shows as quiet, with nothing to rate and no last activity")
    void quietAgent() {
        AgentRow gamma = row(service.agents(ORG, "7d"), GAMMA);

        assertThat(gamma.runs()).isZero();
        assertThat(gamma.successRate()).isNull();
        assertThat(gamma.lastActive()).isNull();
        assertThat(gamma.ratings()).isZero();
    }

    // @find: test agent hours, insights service queries
    @Test
    @DisplayName("hours returned per agent need that agent's own estimate, and say 'not estimated' as absent")
    void agentHours() {
        setting(ORG, "value.minutesPerTask." + ALPHA, "30");

        InsightsService.AgentInsights agents = service.agents(ORG, "7d");

        assertThat(row(agents, ALPHA).minutesPerTask()).isEqualTo(30);
        assertThat(row(agents, ALPHA).hoursReturned()).isEqualByComparingTo("1.00");
        assertThat(row(agents, BETA).minutesPerTask()).isNull();
        assertThat(row(agents, BETA).hoursReturned()).isNull();
    }

    // ---- Isolation and emptiness -----------------------------------------------------------

    // @find: test workspace isolation, insights service queries
    @Test
    @DisplayName("another workspace's rows never reach the figures, and its own show only its own")
    void workspaceIsolation() {
        Insights other = service.insights(OTHER, "7d");

        assertThat(other.goals().completed()).isEqualTo(1);
        assertThat(other.spend().total()).isEqualByComparingTo("9.00");
        assertThat(other.runs().total()).isEqualTo(2);
        assertThat(service.agents(OTHER, "7d").agents()).extracting(AgentRow::name).containsExactly("Intruder");
        // And none of the other's money is in this workspace's.
        assertThat(week().spend().total()).isEqualByComparingTo("0.88");
        assertThat(week().failureReasons()).extracting(InsightsService.FailureReason::reason)
                .doesNotContain("Their own failure");
    }

    // @find: test empty workspace, insights service queries
    @Test
    @DisplayName("a workspace with nothing in it has zeros and absent rates, not errors or invented figures")
    void emptyWorkspace() {
        Insights insights = service.insights(EMPTY, "30d");

        assertThat(insights.goals().completed()).isZero();
        assertThat(insights.tasks().successRate()).isNull();
        assertThat(insights.runs().total()).isZero();
        assertThat(insights.spend().total()).isEqualByComparingTo("0");
        assertThat(insights.spend().costPerCompletedGoal()).isNull();
        assertThat(insights.approvals().medianDecisionSeconds()).isNull();
        assertThat(insights.questions().per100Runs()).isNull();
        assertThat(insights.failureReasons()).isEmpty();
        assertThat(insights.value()).isNull();
        assertThat(service.agents(EMPTY, "30d").agents()).isEmpty();
    }
}
