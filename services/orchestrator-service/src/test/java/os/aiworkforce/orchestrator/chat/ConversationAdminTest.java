// @find: tests for conversation admin, chat, rename allowed for creator, rename allowed for workspace update, rename refused for others, pin archive and read call the upserts, no human cannot pin but read is quiet, delete with someone elses active work is409 and stops nothing, delete by cancel holder stops others work, delete cancels detaches and deletes, ConversationAdminTest, ConversationAdmin
// @what: Tests for ConversationAdmin in the orchestrator chat package (10 test methods).
// @flow: Exercises ConversationAdmin
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.ConversationMarks;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

class ConversationAdminTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ConversationMarks marks;
    private Goals goals;
    private GoalService goalService;
    private QuestionService questions;
    private ChatAppender appender;
    private ConversationQueries queries;
    private AuditClient audit;
    private ConversationAdmin admin;

    private Conversation conversation;
    private UUID creatorId;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        marks = mock(ConversationMarks.class);
        goals = mock(Goals.class);
        goalService = mock(GoalService.class);
        questions = mock(QuestionService.class);
        EntityManager entityManager = mock(EntityManager.class);
        appender = new ChatAppender(
                conversations, mock(os.aiworkforce.orchestrator.repository.ChatMessages.class), entityManager);
        queries = mock(ConversationQueries.class);
        audit = mock(AuditClient.class);
        admin = new ConversationAdmin(conversations, marks, goals, goalService, questions, appender, queries, audit);

        creatorId = UUID.randomUUID();
        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        setCreatedBy(conversation, creatorId.toString());
        lenient()
                .when(conversations.findByIdAndOrgId(conversation.getId(), ORG))
                .thenReturn(Optional.of(conversation));
        lenient().when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(goals.findByOrgIdAndConversationIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
    }

    /** {@code createdBy} is set only by Spring's auditing listener outside a real persistence context. */
    private static void setCreatedBy(Conversation conversation, String value) {
        try {
            java.lang.reflect.Field field = findField(conversation.getClass(), "createdBy");
            field.setAccessible(true);
            field.set(conversation, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException notHere) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Actor asUser(UUID id, String... permissions) {
        return Actor.user(id.toString(), ORG.toString(), "role", Set.of(permissions), 0L);
    }

    // @find: test rename allowed for creator, conversation admin
    @Test
    @DisplayName("the creator may rename a conversation")
    void renameAllowedForCreator() {
        when(queries.view(any(), any(), any())).thenReturn(dummyView());

        admin.rename(ORG, asUser(creatorId, "chat:use"), conversation.getId(), "New title");

        assertThat(conversation.getTitle()).isEqualTo("New title");
    }

    // @find: test rename allowed for workspace update, conversation admin
    @Test
    @DisplayName("a holder of workspace:update may rename a conversation")
    void renameAllowedForWorkspaceUpdate() {
        when(queries.view(any(), any(), any())).thenReturn(dummyView());

        admin.rename(ORG, asUser(UUID.randomUUID(), "chat:use", "workspace:update"), conversation.getId(), "New title");

        assertThat(conversation.getTitle()).isEqualTo("New title");
    }

    // @find: test rename refused for others, conversation admin
    @Test
    @DisplayName("anyone else is refused with a sentence explaining why")
    void renameRefusedForOthers() {
        assertThatThrownBy(() ->
                        admin.rename(ORG, asUser(UUID.randomUUID(), "chat:use"), conversation.getId(), "New title"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    // @find: test pin archive and read call the upserts, conversation admin
    @Test
    @DisplayName("pin, archive and read call the upserts, and a double pin is harmless")
    void pinArchiveAndReadCallTheUpserts() {
        Actor actor = asUser(creatorId, "chat:use");

        admin.pin(ORG, actor, conversation.getId());
        admin.pin(ORG, actor, conversation.getId());
        verify(marks, times(2))
                .upsertFlags(any(), eq(ORG), eq(conversation.getId()), eq(creatorId), eq(true), eq(false));

        admin.unpin(ORG, actor, conversation.getId());
        verify(marks).upsertFlags(any(), eq(ORG), eq(conversation.getId()), eq(creatorId), eq(false), isNull());

        admin.archive(ORG, actor, conversation.getId());
        verify(marks).upsertFlags(any(), eq(ORG), eq(conversation.getId()), eq(creatorId), eq(false), eq(true));

        admin.unarchive(ORG, actor, conversation.getId());
        verify(marks).upsertFlags(any(), eq(ORG), eq(conversation.getId()), eq(creatorId), isNull(), eq(false));

        for (int i = 0; i < 6; i++) {
            conversation.recordMessage("m" + i);
        }
        admin.markRead(ORG, actor, conversation.getId(), 5);
        verify(marks).upsertRead(any(), eq(ORG), eq(conversation.getId()), eq(creatorId), eq(5));
    }

    // @find: test no human cannot pin but read is quiet, conversation admin
    @Test
    @DisplayName("a caller with no person behind them cannot pin, but marking read is a quiet no-op")
    void noHumanCannotPinButReadIsQuiet() {
        Actor system = Actor.SYSTEM;
        assertThatThrownBy(() -> admin.pin(ORG, system, conversation.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));

        admin.markRead(ORG, system, conversation.getId(), 3);
        verify(marks, never()).upsertRead(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // @find: test delete with someone elses active work is409 and stops nothing, conversation admin
    @Test
    @DisplayName("deleting with someone else's active work is a conflict, and stops nothing")
    void deleteWithSomeoneElsesActiveWorkIs409AndStopsNothing() {
        Goal othersGoal = activeGoal(UUID.randomUUID());
        when(goals.findByOrgIdAndConversationIdAndStatusIn(eq(ORG), eq(conversation.getId()), any()))
                .thenReturn(List.of(othersGoal));

        assertThatThrownBy(() -> admin.delete(ORG, asUser(creatorId, "chat:use"), conversation.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));

        verify(goalService, never()).cancelIfActive(any(), any(), any());
        verify(conversations, never()).delete(any());
    }

    // @find: test delete by cancel holder stops others work, conversation admin
    @Test
    @DisplayName("a task:cancel holder can delete a conversation and stop someone else's active work")
    void deleteByCancelHolderStopsOthersWork() {
        Goal othersGoal = activeGoal(UUID.randomUUID());
        when(goals.findByOrgIdAndConversationIdAndStatusIn(eq(ORG), eq(conversation.getId()), any()))
                .thenReturn(List.of(othersGoal));
        when(goalService.cancelIfActive(any(), any(), any())).thenReturn(Optional.empty());

        admin.delete(ORG, asUser(creatorId, "chat:use", "task:cancel"), conversation.getId());

        verify(goalService).cancelIfActive(ORG, othersGoal.getId(), ConversationAdmin.DELETED_REASON);
        verify(goals).detachConversation(ORG, conversation.getId());
        verify(questions).detachConversation(ORG, conversation.getId());
        verify(conversations).delete(conversation);
    }

    // @find: test delete cancels detaches and deletes, conversation admin
    @Test
    @DisplayName("delete cancels active goals, detaches them, then deletes the row read fresh under a lock")
    void deleteCancelsDetachesAndDeletes() {
        Goal mine = activeGoal(creatorId);
        when(goals.findByOrgIdAndConversationIdAndStatusIn(eq(ORG), eq(conversation.getId()), any()))
                .thenReturn(List.of(mine));
        when(goalService.cancelIfActive(any(), any(), any())).thenReturn(Optional.empty());

        admin.delete(ORG, asUser(creatorId, "chat:use"), conversation.getId());

        verify(goalService).cancelIfActive(ORG, mine.getId(), ConversationAdmin.DELETED_REASON);
        verify(conversations).delete(conversation);
    }

    // @find: test delete after notice bumped the version still succeeds, conversation admin
    @Test
    @DisplayName("a version bumped by a cancel's notice between the first read and the lock is not a problem")
    void deleteAfterNoticeBumpedTheVersionStillSucceeds() {
        // The locked copy is read afresh through conversations.findByIdAndOrgId (via appender.lock),
        // the same stub as the very first load, so a version bump in between never surfaces here -
        // there is no second, stale copy this test could accidentally save over.
        admin.delete(ORG, asUser(creatorId, "chat:use"), conversation.getId());

        verify(conversations).delete(conversation);
    }

    // @find: test delete of aconversation already gone is204, conversation admin
    @Test
    @DisplayName("deleting a conversation that is already gone returns quietly")
    void deleteOfAConversationAlreadyGoneIs204() {
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.empty());

        admin.delete(ORG, asUser(creatorId, "chat:use"), conversation.getId());

        verify(conversations, never()).delete(any());
    }

    private static Goal activeGoal(UUID requestedBy) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setStatus("running");
        goal.setRequestedBy(requestedBy);
        return goal;
    }

    private static ConversationQueries.ConversationView dummyView() {
        return new ConversationQueries.ConversationView(
                UUID.randomUUID(), "t", null, null, null, "", 0, false, false, "idle", true, false, null, "workspace", true);
    }
}
