// @find: tests for chat messages earlier turns, repository, only turns newest first, window counts turns not cards, other conversations are not read, ChatMessagesEarlierTurnsTest, ChatMessagesEarlierTurns
// @what: Tests for ChatMessagesEarlierTurns in the orchestrator repository package (3 test methods).
// @flow: Exercises ChatMessagesEarlierTurns
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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

import os.aiworkforce.orchestrator.domain.ChatMessage;

/**
 * The query that fetches the turns an agent is given as context, against the real schema: only what
 * people wrote and what agents answered, newest first, before a position, in a window of a given
 * size - so routing receipts and progress cards between them never use up the window.
 *
 * <p>Opt-in, because it needs Docker: run with {@code AIWOS_DATABASE_TESTS=true} (and, where the
 * Ryuk image is not available, {@code TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class ChatMessagesEarlierTurnsTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000c1");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=orchestrator");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ChatMessages messages;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID conversation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into orchestrator.conversations (id, org_id) values (?, ?)", id, ORG);
        return id;
    }

    private void add(UUID conversationId, int position, String authorKind, String kind, String content) {
        UUID agent = "answer".equals(kind) ? UUID.randomUUID() : null;
        UUID author = "user".equals(authorKind) ? UUID.randomUUID() : null;
        messages.saveAndFlush(
                ChatMessage.of(ORG, conversationId, position, authorKind, author, agent, kind, content, Map.of(), null));
    }

    // @find: test only turns newest first, chat messages earlier turns
    @Test
    @DisplayName("returns only people's messages and agents' answers, newest first, before the given position")
    void onlyTurnsNewestFirst() {
        UUID id = conversation();
        add(id, 0, "user", "text", "first request");
        add(id, 1, "coordinator", "routing", "routed");
        add(id, 2, "coordinator", "progress", "working");
        add(id, 3, "agent", "answer", "first answer");
        add(id, 4, "system", "text", "a notice written as text by the platform");
        add(id, 5, "coordinator", "documents", "passages");
        add(id, 6, "coordinator", "error", "went wrong");
        add(id, 7, "user", "text", "second request");
        add(id, 8, "user", "text", "the message being answered now");

        List<ChatMessage> turns = messages.findEarlierTurns(id, 8, PageRequest.of(0, 30));

        assertThat(turns)
                .extracting(ChatMessage::getContent)
                .containsExactly("second request", "first answer", "first request");
    }

    // @find: test window counts turns not cards, chat messages earlier turns
    @Test
    @DisplayName("a window of a given size holds that many turns, however many cards sit between them")
    void windowCountsTurnsNotCards() {
        UUID id = conversation();
        int position = 0;
        for (int turn = 0; turn < 10; turn++) {
            add(id, position++, "user", "text", "request " + turn);
            add(id, position++, "coordinator", "routing", "routed " + turn);
            add(id, position++, "coordinator", "progress", "working " + turn);
            add(id, position++, "agent", "answer", "answer " + turn);
        }

        List<ChatMessage> turns = messages.findEarlierTurns(id, position, PageRequest.of(0, 6));

        assertThat(turns)
                .extracting(ChatMessage::getContent)
                .containsExactly("answer 9", "request 9", "answer 8", "request 8", "answer 7", "request 7");
    }

    // @find: test other conversations are not read, chat messages earlier turns
    @Test
    @DisplayName("reads nothing from another conversation")
    void otherConversationsAreNotRead() {
        UUID mine = conversation();
        UUID theirs = conversation();
        add(mine, 0, "user", "text", "mine");
        add(theirs, 0, "user", "text", "theirs");

        assertThat(messages.findEarlierTurns(mine, 5, PageRequest.of(0, 30)))
                .extracting(ChatMessage::getContent)
                .containsExactly("mine");
    }
}
