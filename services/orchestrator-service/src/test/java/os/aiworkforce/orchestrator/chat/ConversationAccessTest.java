// @find: tests for conversation access, chat, the creator reads it without any audit entry, another employee is told it does not exist, a participant reads it, a workspace conversation is read by everyone, read all reads it and the read is audited, only the creator or aread all holder changes who can read, a holder of read all hides nothing, ConversationAccessTest, ConversationAccess
// @what: Tests for ConversationAccess in the orchestrator chat package (7 test methods).
// @flow: Exercises ConversationAccess
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ConversationParticipants;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;

/** Who may read a conversation: the creator, the people added, everyone for a workspace one, and owners with chat:read_all. */
class ConversationAccessTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private Conversations conversations;
    private ConversationParticipants participants;
    private AuditClient audit;
    private ConversationAccess access;
    private Conversation privateThread;

    private static Actor user(UUID id, String... permissions) {
        return Actor.user(id.toString(), ORG.toString(), "role", Set.of(permissions), 0L);
    }

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        participants = mock(ConversationParticipants.class);
        audit = mock(AuditClient.class);
        access = new ConversationAccess(conversations, participants, mock(JdbcTemplate.class), audit);
        privateThread = new Conversation();
        privateThread.setId(UUID.randomUUID());
        privateThread.setOrgId(ORG);
        org.springframework.test.util.ReflectionTestUtils.setField(privateThread, "createdBy", ALICE.toString());
        privateThread.setVisibility("private");
        when(conversations.findByIdAndOrgId(privateThread.getId(), ORG)).thenReturn(Optional.of(privateThread));
    }

    // @find: test the creator reads it without any audit entry, conversation access
    @Test
    void theCreatorReadsItWithoutAnyAuditEntry() {
        Conversation found = access.requireForRead(ORG, user(ALICE, "chat:use"), privateThread.getId());
        assertThat(found).isSameAs(privateThread);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    // @find: test another employee is told it does not exist, conversation access
    @Test
    void anotherEmployeeIsToldItDoesNotExist() {
        assertThatThrownBy(() -> access.require(ORG, user(BOB, "chat:use"), privateThread.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not exist");
    }

    // @find: test a participant reads it, conversation access
    @Test
    void aParticipantReadsIt() {
        when(participants.existsByConversationIdAndUserId(privateThread.getId(), BOB.toString()))
                .thenReturn(true);
        assertThat(access.require(ORG, user(BOB, "chat:use"), privateThread.getId())).isSameAs(privateThread);
    }

    // @find: test a workspace conversation is read by everyone, conversation access
    @Test
    void aWorkspaceConversationIsReadByEveryone() {
        privateThread.setVisibility("workspace");
        assertThat(access.require(ORG, user(BOB, "chat:use"), privateThread.getId())).isSameAs(privateThread);
    }

    // @find: test read all reads it and the read is audited, conversation access
    @Test
    void readAllReadsItAndTheReadIsAudited() {
        access.requireForRead(ORG, user(OWNER, "chat:use", Permission.Codes.CHAT_READ_ALL), privateThread.getId());
        verify(audit)
                .record(eq(ORG), any(Actor.class), eq("chat.read_private"), eq("conversation"), any(), eq("succeeded"), any());
    }

    // @find: test only the creator or aread all holder changes who can read, conversation access
    @Test
    void onlyTheCreatorOrAReadAllHolderChangesWhoCanRead() {
        when(participants.existsByConversationIdAndUserId(privateThread.getId(), BOB.toString()))
                .thenReturn(true);
        assertThatThrownBy(() -> access.requireOwner(ORG, user(BOB, "chat:use"), privateThread.getId()))
                .isInstanceOf(ApiException.class);
        assertThat(access.requireOwner(ORG, user(ALICE, "chat:use"), privateThread.getId()))
                .isSameAs(privateThread);
    }

    // @find: test a holder of read all hides nothing, conversation access
    @Test
    void aHolderOfReadAllHidesNothing() {
        Actor owner = user(OWNER, Permission.Codes.CHAT_READ_ALL);
        assertThat(access.hiddenGoalIds(ORG, owner)).isEmpty();
        assertThat(access.hiddenTaskIds(ORG, owner)).isEmpty();
        assertThat(access.hiddenConversationIds(ORG, owner)).isEmpty();
    }
}
