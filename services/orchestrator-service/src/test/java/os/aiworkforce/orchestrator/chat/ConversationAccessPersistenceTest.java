// @find: tests for conversation access sql, hidden goal ids, hidden task ids, hidden conversation ids, deleted private conversation stays hidden, ConversationAccessPersistenceTest, ConversationAccess
// @what: Runs ConversationAccess's hiding queries against the real schema: private work is hidden from outsiders, and work from a deleted private conversation from everyone but readers of all.
// @flow: Exercises ConversationAccess against the database DatabaseForTests provides; opt-in with AIWOS_DATABASE_TESTS=true
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.repository.ConversationParticipants;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.rbac.Permission;

/**
 * ConversationAccess's hiding queries against the real schema. The unit tests mock the database,
 * so they cannot see a query Postgres will not run or a rule that misses a deleted conversation.
 * Opt-in, because it needs a database: run with {@code AIWOS_DATABASE_TESTS=true} (see {@link
 * DatabaseForTests}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class ConversationAccessPersistenceTest {

    /* A fresh workspace per test: other database tests write into the same shared test database. */
    private final UUID ORG = UUID.randomUUID();
    private static final UUID CREATOR = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ADDED = UUID.fromString("00000000-0000-7000-8000-0000000000a2");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-7000-8000-0000000000a3");
    private static final UUID OWNER = UUID.fromString("00000000-0000-7000-8000-0000000000a4");

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
    private JdbcTemplate jdbc;

    private ConversationAccess access;

    @BeforeEach
    void setUp() {
        access = new ConversationAccess(
                mock(Conversations.class), mock(ConversationParticipants.class), jdbc, mock(AuditClient.class));
    }

    private Actor person(UUID id, String... permissions) {
        return Actor.user(id.toString(), ORG.toString(), "role", Set.of(permissions), 0L);
    }

    /** A conversation started by {@link #CREATOR}; a private one has {@link #ADDED} added to it. */
    private UUID conversation(String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into conversations (id, org_id, title, visibility, created_by) values (?, ?, 'Chat', ?, ?)",
                id, ORG, visibility, CREATOR.toString());
        if ("private".equals(visibility)) {
            jdbc.update(
                    "insert into conversation_participants (conversation_id, user_id, added_by) values (?, ?, ?)",
                    id, ADDED.toString(), CREATOR.toString());
        }
        return id;
    }

    private UUID goal(UUID conversationId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into goals (id, org_id, title, status, source, conversation_id, requested_by)
                values (?, ?, 'Work', 'completed', 'chat', ?, ?)
                """,
                id, ORG, conversationId, CREATOR);
        return id;
    }

    private UUID task(UUID goalId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into tasks (id, org_id, goal_id, title, instruction, status, position, attempt)
                values (?, ?, ?, 'Step', 'Do it', 'completed', 0, 1)
                """,
                id, ORG, goalId);
        return id;
    }

    /** A question shown in a conversation, with no goal (as a direct run's question can be). */
    private void question(UUID conversationId) {
        UUID agentId = UUID.randomUUID();
        jdbc.update("insert into agents (id, org_id, key, name) values (?, ?, ?, 'Ada')", agentId, ORG, "ada-" + agentId);
        UUID runId = UUID.randomUUID();
        jdbc.update(
                "insert into runs (id, org_id, agent_id, agent_version_id, status) values (?, ?, ?, ?, 'completed')",
                runId, ORG, agentId, UUID.randomUUID());
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into run_questions (id, org_id, run_id, agent_id, tool_call_id, questions, status,
                                           requested_by, conversation_id, expires_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), 'expired', ?, ?, now())
                """,
                id, ORG, runId, agentId, "q-" + id, "[{\"question\":\"Which?\"}]", CREATOR, conversationId);
    }

    /** What ConversationAdmin.delete leaves of a private conversation: its work, still holding its id. */
    private void deleteKeepingItsWork(UUID conversationId) {
        jdbc.update("delete from conversations where id = ?", conversationId);
    }

    // @find: test deleted private conversation work stays hidden, conversation access persistence
    @Test
    @DisplayName("work from a deleted private conversation is hidden from everyone but readers of all, its own people included")
    void workFromADeletedPrivateConversationStaysHidden() {
        UUID secret = conversation("private");
        UUID deleted = conversation("private");
        UUID open = conversation("workspace");
        UUID secretGoal = goal(secret);
        UUID deletedGoal = goal(deleted);
        UUID openGoal = goal(open);
        UUID plainGoal = goal(null);
        UUID secretTask = task(secretGoal);
        UUID deletedTask = task(deletedGoal);
        task(openGoal);
        task(plainGoal);
        UUID questionOnly = conversation("private");
        question(questionOnly);
        deleteKeepingItsWork(deleted);
        deleteKeepingItsWork(questionOnly);

        Actor outsider = person(OUTSIDER);
        assertThat(access.hiddenGoalIds(ORG, outsider)).containsExactlyInAnyOrder(secretGoal, deletedGoal);
        assertThat(access.hiddenTaskIds(ORG, outsider)).containsExactlyInAnyOrder(secretTask, deletedTask);
        assertThat(access.hiddenConversationIds(ORG, outsider))
                .containsExactlyInAnyOrder(secret, deleted, questionOnly);

        // The people of a deleted conversation lose sight of its work too: nothing says who they were.
        for (UUID insider : Set.of(CREATOR, ADDED)) {
            Actor actor = person(insider);
            assertThat(access.hiddenGoalIds(ORG, actor)).containsExactly(deletedGoal);
            assertThat(access.hiddenTaskIds(ORG, actor)).containsExactly(deletedTask);
            assertThat(access.hiddenConversationIds(ORG, actor)).containsExactlyInAnyOrder(deleted, questionOnly);
        }

        Actor owner = person(OWNER, Permission.Codes.CHAT_READ_ALL);
        assertThat(access.hiddenGoalIds(ORG, owner)).isEmpty();
        assertThat(access.hiddenTaskIds(ORG, owner)).isEmpty();
        assertThat(access.hiddenConversationIds(ORG, owner)).isEmpty();
    }
}
