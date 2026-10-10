// @find: tests for board queries persistence, board, migration, recent goals, filtered goals, conversation goals changed since, recent runs, purges processed events, BoardQueriesPersistenceTest, BoardQueriesPersistence
// @what: Tests for BoardQueriesPersistence in the orchestrator board package (6 test methods).
// @flow: Exercises BoardQueriesPersistence
package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ProcessedEvents;
import os.aiworkforce.orchestrator.repository.Runs;

/**
 * V11 and the board's list-returning queries against the real schema: Flyway V1 to V11, then
 * Hibernate's validation of every query, and the filters, ordering and purge run against rows.
 *
 * <p>The board and goal tests stub these repositories, so they cannot see a query Hibernate will
 * not parse or SQL Postgres will not run - and with {@code ddl-auto: validate} the first is the
 * orchestrator refusing to start. Opt-in, because it needs Docker: run with {@code
 * AIWOS_DATABASE_TESTS=true} (and, where the Ryuk image is not available, {@code
 * TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class BoardQueriesPersistenceTest {

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
    private Goals goals;

    @Autowired
    private Runs runs;

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID goal(UUID org, String status, String source, UUID scheduleId, UUID conversationId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into goals (id, org_id, title, status, source, schedule_id, conversation_id, created_at, updated_at)
                values (?, ?, 'A goal', ?, ?, ?, ?, ?, ?)
                """,
                id,
                org,
                status,
                source,
                scheduleId,
                conversationId,
                Timestamp.from(at),
                Timestamp.from(at));
        return id;
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    // @find: test migration, board queries persistence
    @Test
    @DisplayName("V11 adds the indexes the hot queries need, and leaves the one that already existed alone")
    void migration() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'orchestrator'", String.class);

        assertThat(indexes)
                .contains(
                        "runs_waiting_approval_idx",
                        "approvals_run_requested_idx",
                        "approvals_task_idx",
                        "run_questions_task_idx",
                        "run_questions_goal_idx",
                        "tasks_agent_idx",
                        "llm_usage_org_agent_time_idx",
                        "goals_conversation_idx",
                        // Already there since V1, so V11 does not repeat it.
                        "runs_org_started_idx");
        assertThat(jdbc.queryForObject(
                        "select count(*) from pg_indexes where schemaname = 'orchestrator' and tablename = 'runs'"
                                + " and indexdef like '%(org_id, started_at DESC)%'",
                        Long.class))
                .isEqualTo(1L);
    }

    // @find: test recent goals, board queries persistence
    @Test
    @DisplayName("the newest goals come back as a plain list, newest first, for one workspace only")
    void recentGoals() {
        UUID oldest = goal(ORG, "completed", "manual", null, null, minutesAgo(30));
        UUID newest = goal(ORG, "running", "chat", null, null, minutesAgo(1));
        UUID middle = goal(ORG, "failed", "manual", null, null, minutesAgo(10));
        goal(OTHER_ORG, "running", "manual", null, null, minutesAgo(0));

        List<Goal> found = goals.findRecent(ORG, PageRequest.of(0, 2));

        assertThat(found).extracting(Goal::getId).containsExactly(newest, middle);
        assertThat(goals.findRecent(ORG, PageRequest.of(1, 2))).extracting(Goal::getId).containsExactly(oldest);
    }

    // @find: test filtered goals, board queries persistence
    @Test
    @DisplayName("the goals list narrows by status, source and schedule, alone or together, and never crosses workspaces")
    void filteredGoals() {
        UUID schedule = UUID.randomUUID();
        UUID failedByChat = goal(ORG, "failed", "chat", null, null, minutesAgo(5));
        UUID failedBySchedule = goal(ORG, "failed", "schedule", schedule, null, minutesAgo(4));
        UUID doneBySchedule = goal(ORG, "completed", "schedule", schedule, null, minutesAgo(3));
        UUID failedByOtherSchedule = goal(ORG, "failed", "schedule", UUID.randomUUID(), null, minutesAgo(2));
        goal(OTHER_ORG, "failed", "schedule", schedule, null, minutesAgo(1));
        UUID none = UUID.randomUUID();
        PageRequest page = PageRequest.of(0, 50);

        assertThat(goals.findFiltered(ORG, "", "", true, none, page)).hasSize(4);
        assertThat(goals.findFiltered(ORG, "failed", "", true, none, page))
                .extracting(Goal::getId)
                .containsExactly(failedByOtherSchedule, failedBySchedule, failedByChat);
        assertThat(goals.findFiltered(ORG, "", "chat", true, none, page))
                .extracting(Goal::getId)
                .containsExactly(failedByChat);
        assertThat(goals.findFiltered(ORG, "", "", false, schedule, page))
                .extracting(Goal::getId)
                .containsExactly(doneBySchedule, failedBySchedule);
        assertThat(goals.findFiltered(ORG, "failed", "schedule", false, schedule, page))
                .extracting(Goal::getId)
                .containsExactly(failedBySchedule);
        assertThat(goals.findFiltered(ORG, "cancelled", "", true, none, page)).isEmpty();
    }

    // @find: test conversation goals changed since, board queries persistence
    @Test
    @DisplayName("a conversation's goals changed since a moment are found by the conversation, not by scanning the workspace")
    void conversationGoalsChangedSince() {
        UUID conversation = UUID.randomUUID();
        UUID recent = goal(ORG, "completed", "chat", null, conversation, minutesAgo(1));
        goal(ORG, "completed", "chat", null, conversation, minutesAgo(60));
        goal(ORG, "completed", "chat", null, UUID.randomUUID(), minutesAgo(1));
        goal(OTHER_ORG, "completed", "chat", null, conversation, minutesAgo(1));

        List<Goal> changed = goals.findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(
                ORG, conversation, Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(changed).extracting(Goal::getId).containsExactly(recent);
    }

    // @find: test recent runs, board queries persistence
    @Test
    @DisplayName("the newest runs come back as a plain list, newest first, for one workspace only")
    void recentRuns() {
        UUID older = run(ORG, minutesAgo(20));
        UUID newer = run(ORG, minutesAgo(2));
        run(OTHER_ORG, minutesAgo(1));

        List<Run> found = runs.findRecent(ORG, PageRequest.of(0, 10));

        assertThat(found).extracting(Run::getId).containsExactly(newer, older);
    }

    private UUID run(UUID org, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into runs (id, org_id, agent_id, agent_version_id, status, started_at)
                values (?, ?, ?, ?, 'completed', ?)
                """,
                id,
                org,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(startedAt));
        return id;
    }

    // @find: test purges processed events, board queries persistence
    @Test
    @DisplayName("the purge forgets only event ids older than the cut-off, and says how many")
    void purgesProcessedEvents() {
        Instant now = Instant.now();
        for (String[] event : new String[][] {{"old-1", "10"}, {"old-2", "8"}, {"fresh", "1"}}) {
            jdbc.update(
                    "insert into processed_events (event_id, topic, processed_at) values (?, 'topic', ?)",
                    event[0],
                    Timestamp.from(now.minus(Long.parseLong(event[1]), ChronoUnit.DAYS)));
        }

        int removed = processedEvents.deleteProcessedBefore(now.minus(7, ChronoUnit.DAYS));

        assertThat(removed).isEqualTo(2);
        assertThat(jdbc.queryForList("select event_id from processed_events", String.class))
                .containsExactly("fresh");
    }
}
