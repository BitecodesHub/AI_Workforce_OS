package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.chat.ChatAttachments;

/**
 * The attachments table against the real schema (V15): every read is held to its workspace, a
 * message's files are bound once, the files a goal was given are found by it, deleting a
 * conversation deletes its files, and unsent drafts are swept.
 *
 * <p>Opt-in ({@code AIWOS_DATABASE_TESTS=true}), against Docker or the throwaway database
 * {@code AIWOS_TEST_DATABASE_URL} names; see {@link DatabaseForTests}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
@Import(ChatAttachments.class)
class ChatAttachmentsPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a7");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b7");

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
    private ChatAttachments attachments;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID conversation(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into conversations (id, org_id) values (?, ?)", id, org);
        return id;
    }

    private UUID message(UUID org, UUID conversation, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into chat_messages (id, org_id, conversation_id, position, author_kind, kind)"
                        + " values (?, ?, ?, ?, 'user', 'text')",
                id,
                org,
                conversation,
                position);
        return id;
    }

    private UUID attach(UUID org, UUID conversation, String name) {
        UUID id = UUID.randomUUID();
        attachments.insert(new ChatAttachments.NewAttachment(
                id, org, conversation, "alice", name, "application/pdf", "pdf", new byte[] {37, 80, 68, 70}, "hash",
                "ready", "Text of " + name, 1, null, null));
        return id;
    }

    @Test
    @DisplayName("a file is read only within its own workspace, and its bytes come back exactly")
    void workspaceIsolation() {
        UUID id = attach(ORG, null, "q3.pdf");

        assertThat(attachments.find(ORG, id)).get().satisfies(row -> {
            assertThat(row.name()).isEqualTo("q3.pdf");
            assertThat(row.size()).isEqualTo(4);
            assertThat(row.text()).isNull();
        });
        assertThat(attachments.content(ORG, id)).get().isEqualTo(new byte[] {37, 80, 68, 70});
        assertThat(attachments.find(OTHER_ORG, id)).isEmpty();
        assertThat(attachments.content(OTHER_ORG, id)).isEmpty();
        assertThat(attachments.findAll(OTHER_ORG, List.of(id))).isEmpty();
        assertThat(attachments.delete(OTHER_ORG, id)).isZero();
    }

    @Test
    @DisplayName("drafts bind to the message that sends them once, then reach its goal and follow a reroute")
    void bindAndLink() {
        UUID conversation = conversation(ORG);
        UUID a = attach(ORG, null, "a.pdf");
        UUID b = attach(ORG, conversation, "b.pdf");
        UUID sent = message(ORG, conversation, 0);

        assertThat(attachments.bindToMessage(ORG, conversation, sent, List.of(a, b))).isEqualTo(2);
        assertThat(attachments.bindToMessage(ORG, conversation, message(ORG, conversation, 1), List.of(a))).isZero();
        assertThat(attachments.find(ORG, a)).get().satisfies(row -> {
            assertThat(row.conversationId()).isEqualTo(conversation);
            assertThat(row.messageId()).isEqualTo(sent);
        });

        UUID goal = UUID.randomUUID();
        assertThat(attachments.linkGoal(ORG, sent, goal)).isEqualTo(2);
        assertThat(attachments.forGoal(ORG, goal)).extracting(ChatAttachments.Row::text)
                .containsExactlyInAnyOrder("Text of a.pdf", "Text of b.pdf");
        assertThat(attachments.forGoal(OTHER_ORG, goal)).isEmpty();

        UUID rerouted = UUID.randomUUID();
        assertThat(attachments.moveGoal(ORG, goal, rerouted)).isEqualTo(2);
        assertThat(attachments.withText(ORG, List.of(a))).singleElement().satisfies(row -> {
            assertThat(row.goalId()).isEqualTo(rerouted);
            assertThat(row.text()).isEqualTo("Text of a.pdf");
        });
    }

    @Test
    @DisplayName("a choice made later links the files of the person's message just above it")
    void linkLatestUserMessage() {
        UUID conversation = conversation(ORG);
        UUID a = attach(ORG, null, "a.pdf");
        UUID sent = message(ORG, conversation, 4);
        attachments.bindToMessage(ORG, conversation, sent, List.of(a));
        UUID goal = UUID.randomUUID();

        assertThat(attachments.linkLatestUserMessage(ORG, conversation, 5, goal)).isEqualTo(1);
        assertThat(attachments.find(ORG, a)).get().extracting(ChatAttachments.Row::goalId).isEqualTo(goal);
    }

    @Test
    @DisplayName("deleting a conversation deletes its files; unsent drafts are swept by age")
    void deletion() {
        UUID conversation = conversation(ORG);
        UUID inThread = attach(ORG, conversation, "kept-with-thread.pdf");
        UUID draft = attach(ORG, null, "draft.pdf");
        jdbc.update("update chat_attachments set created_at = now() - interval '2 days' where id = ?", draft);

        jdbc.update("delete from conversations where id = ?", conversation);
        assertThat(attachments.find(ORG, inThread)).isEmpty();

        assertThat(attachments.deleteUnsentBefore(Instant.now().minusSeconds(86_400))).isGreaterThanOrEqualTo(1);
        assertThat(attachments.find(ORG, draft)).isEmpty();
    }
}
