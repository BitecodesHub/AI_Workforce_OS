// @find: tests for message feedbacks persistence, repository, first vote, second vote replaces the first, one vote per person, unique index, rating check, reason check, withdraw, ratings on arun, MessageFeedbacksPersistenceTest, MessageFeedbacksPersistence
// @what: Tests for MessageFeedbacksPersistence in the orchestrator repository package (9 test methods).
// @flow: Exercises MessageFeedbacksPersistence
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.domain.MessageFeedback;

/**
 * Ratings against the real schema: the unique index that allows one vote per person per answer,
 * the upsert that turns a second vote into a change, the CHECKs on the rating and the reason, and
 * the cascade when a conversation goes.
 *
 * <p>The controller test stubs these repositories, so it cannot see an index Postgres enforces or
 * a native upsert it will not run. Opt-in, because it needs a database: run with {@code
 * AIWOS_DATABASE_TESTS=true} (see {@link DatabaseForTests}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class MessageFeedbacksPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-00000000a001");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-00000000d001");
    private static final UUID ANNA = UUID.fromString("00000000-0000-7000-8000-00000000f001");
    private static final UUID BEN = UUID.fromString("00000000-0000-7000-8000-00000000f002");

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
    private MessageFeedbacks feedbacks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entities;

    private UUID conversation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into conversations (id, org_id) values (?, ?)", id, ORG);
        return id;
    }

    private UUID answer(UUID conversation, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into chat_messages (id, org_id, conversation_id, position, author_kind, agent_id, kind)"
                        + " values (?, ?, ?, ?, 'agent', ?, 'answer')",
                id,
                ORG,
                conversation,
                position,
                AGENT);
        return id;
    }

    private void vote(UUID conversation, UUID message, UUID person, int rating, String reason) {
        feedbacks.upsert(
                UUID.randomUUID(), ORG, conversation, message, person, AGENT, RUN, (short) rating, reason);
        feedbacks.flush();
        entities.clear();
    }

    private long rows(UUID message) {
        return jdbc.queryForObject("select count(*) from chat_message_feedback where message_id = ?", Long.class, message);
    }

    // @find: test first vote, message feedbacks persistence
    @Test
    @DisplayName("a first vote is stored with the agent and run it was given about")
    void firstVote() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);

        vote(conversation, message, ANNA, -1, "wrong");

        MessageFeedback stored = feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, ANNA).orElseThrow();
        assertThat(stored.getRating()).isEqualTo(MessageFeedback.NEGATIVE);
        assertThat(stored.getReason()).isEqualTo("wrong");
        assertThat(stored.getAgentId()).isEqualTo(AGENT);
        assertThat(stored.getRunId()).isEqualTo(RUN);
        assertThat(stored.getConversationId()).isEqualTo(conversation);
    }

    // @find: test second vote replaces the first, message feedbacks persistence
    @Test
    @DisplayName("a second vote by the same person on the same answer changes the first and leaves one row")
    void secondVoteReplacesTheFirst() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);
        vote(conversation, message, ANNA, -1, "wrong");
        MessageFeedback first = feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, ANNA).orElseThrow();
        Instant createdAt = first.getCreatedAt();
        entities.clear();

        vote(conversation, message, ANNA, 1, null);

        assertThat(rows(message)).isEqualTo(1);
        MessageFeedback changed = feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, ANNA).orElseThrow();
        assertThat(changed.getId()).isEqualTo(first.getId());
        assertThat(changed.getRating()).isEqualTo(MessageFeedback.POSITIVE);
        // Changing a mind clears the old reason: it was about the old opinion.
        assertThat(changed.getReason()).isNull();
        assertThat(changed.getCreatedAt()).isEqualTo(createdAt);
        assertThat(changed.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }

    // @find: test one vote per person, message feedbacks persistence
    @Test
    @DisplayName("two people may each rate the same answer, and each sees only their own in the conversation")
    void oneVotePerPerson() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);

        vote(conversation, message, ANNA, 1, null);
        vote(conversation, message, BEN, -1, "incomplete");

        assertThat(rows(message)).isEqualTo(2);
        List<MessageFeedback> annas = feedbacks.findByOrgIdAndConversationIdAndUserId(ORG, conversation, ANNA);
        assertThat(annas).extracting(MessageFeedback::getRating).containsExactly(MessageFeedback.POSITIVE);
        assertThat(feedbacks.findByOrgIdAndConversationIdAndUserId(OTHER_ORG, conversation, ANNA)).isEmpty();
    }

    // @find: test unique index, message feedbacks persistence
    @Test
    @DisplayName("the unique index refuses a second row for the same person and answer, even when inserted by hand")
    void uniqueIndex() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);
        vote(conversation, message, ANNA, 1, null);

        assertThatThrownBy(() -> jdbc.update(
                        "insert into chat_message_feedback (id, org_id, conversation_id, message_id, user_id, rating)"
                                + " values (?, ?, ?, ?, ?, 1)",
                        UUID.randomUUID(),
                        ORG,
                        conversation,
                        message,
                        ANNA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // @find: test rating check, message feedbacks persistence
    @Test
    @DisplayName("a rating other than 1 or -1 is refused by the table itself")
    void ratingCheck() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);

        assertThatThrownBy(() -> vote(conversation, message, ANNA, 0, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // @find: test reason check, message feedbacks persistence
    @Test
    @DisplayName("a reason over 500 characters is refused by the table itself, and exactly 500 is kept")
    void reasonCheck() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);
        vote(conversation, message, ANNA, -1, "x".repeat(500));
        assertThat(feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, ANNA).orElseThrow().getReason())
                .hasSize(500);

        assertThatThrownBy(() -> vote(conversation, message, BEN, -1, "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // @find: test withdraw, message feedbacks persistence
    @Test
    @DisplayName("withdrawing removes the person's own vote only")
    void withdraw() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);
        vote(conversation, message, ANNA, 1, null);
        vote(conversation, message, BEN, -1, null);

        assertThat(feedbacks.withdraw(ORG, message, ANNA)).isEqualTo(1);
        assertThat(feedbacks.withdraw(ORG, message, ANNA)).isZero();
        assertThat(feedbacks.withdraw(OTHER_ORG, message, BEN)).isZero();

        entities.clear();
        assertThat(feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, ANNA)).isEmpty();
        assertThat(feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, message, BEN)).isPresent();
    }

    // @find: test ratings on arun, message feedbacks persistence
    @Test
    @DisplayName("a run's ratings are found newest first, for that workspace's run only")
    void ratingsOnARun() {
        UUID conversation = conversation();
        UUID first = answer(conversation, 1);
        UUID second = answer(conversation, 2);
        vote(conversation, first, ANNA, 1, null);
        vote(conversation, second, ANNA, -1, "wrong");

        List<MessageFeedback> onRun = feedbacks.findByOrgIdAndRunIdOrderByUpdatedAtDesc(ORG, RUN);

        assertThat(onRun).hasSize(2);
        assertThat(onRun.get(0).getUpdatedAt()).isAfterOrEqualTo(onRun.get(1).getUpdatedAt());
        assertThat(feedbacks.findByOrgIdAndRunIdOrderByUpdatedAtDesc(OTHER_ORG, RUN)).isEmpty();
    }

    // @find: test cascades, message feedbacks persistence
    @Test
    @DisplayName("deleting a conversation takes its ratings with it")
    void cascades() {
        UUID conversation = conversation();
        UUID message = answer(conversation, 1);
        vote(conversation, message, ANNA, 1, "great");

        jdbc.update("delete from conversations where id = ?", conversation);

        assertThat(rows(message)).isZero();
    }
}
