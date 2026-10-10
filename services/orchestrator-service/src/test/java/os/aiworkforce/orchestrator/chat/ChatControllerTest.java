// @find: tests for chat controller, chat, stop of goal from another conversation is404, retry of goal from another conversation is404, detail clamps limit and reports has earlier, earlier messages require the orgs conversation, earlier messages are ascending, list rejects unknown scope, ChatControllerTest, ChatController
// @what: Tests for ChatController in the orchestrator chat package (6 test methods).
// @flow: Exercises ChatController
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.ConversationMarks;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

class ChatControllerTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private CoordinatorService coordinator;
    private ChatController controller;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        Goals goals = mock(Goals.class);
        Tasks tasks = mock(Tasks.class);
        Runs runs = mock(Runs.class);
        ConversationMarks marks = mock(ConversationMarks.class);
        QuestionService questions = mock(QuestionService.class);
        GoalService goalService = mock(GoalService.class);
        AuditClient audit = mock(AuditClient.class);
        coordinator = mock(CoordinatorService.class);

        ConversationQueries queries =
                new ConversationQueries(conversations, marks, messages, goals, tasks, runs, questions);
        EntityManager entityManager = mock(EntityManager.class);
        ChatAppender appender = new ChatAppender(conversations, messages, entityManager);
        ConversationAdmin admin =
                new ConversationAdmin(conversations, marks, goals, goalService, questions, appender, queries, audit);
        controller = new ChatController(conversations, queries, admin, coordinator);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        lenient()
                .when(conversations.findByIdAndOrgId(conversation.getId(), ORG))
                .thenReturn(Optional.of(conversation));
        lenient()
                .when(questions.forConversation(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        lenient().when(questions.views(any(), any())).thenReturn(List.of());
        lenient()
                .when(goals.findByOrgIdAndConversationIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());

        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("chat:use"), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static ChatMessage messageAt(UUID conversationId, int position) {
        return ChatMessage.of(
                conversationId,
                conversationId,
                position,
                "user",
                null,
                null,
                "text",
                "message " + position,
                java.util.Map.of(),
                null);
    }

    // @find: test stop of goal from another conversation is404, chat controller
    @Test
    @DisplayName("stopping a goal from another conversation surfaces the coordinator's 404")
    void stopOfGoalFromAnotherConversationIs404() {
        UUID goalId = UUID.randomUUID();
        when(coordinator.stopGoal(any(), any(), any())).thenThrow(ApiException.notFound("goal", goalId));

        assertThatThrownBy(() -> controller.stopGoal(conversation.getId(), goalId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // @find: test retry of goal from another conversation is404, chat controller
    @Test
    @DisplayName("retrying a goal from another conversation surfaces the coordinator's 404")
    void retryOfGoalFromAnotherConversationIs404() {
        UUID goalId = UUID.randomUUID();
        when(coordinator.retryGoal(any(), any(), any())).thenThrow(ApiException.notFound("goal", goalId));

        assertThatThrownBy(() -> controller.retryGoal(conversation.getId(), goalId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // @find: test detail clamps limit and reports has earlier, chat controller
    @Test
    @DisplayName("a limit below the minimum is clamped to 20, and one extra row reports hasEarlier")
    void detailClampsLimitAndReportsHasEarlier() {
        List<ChatMessage> rows = new java.util.ArrayList<>();
        for (int i = 20; i >= 0; i--) {
            rows.add(messageAt(conversation.getId(), i));
        }
        when(messages.findByConversationIdOrderByPositionDesc(eq(conversation.getId()), any()))
                .thenReturn(rows);

        ConversationQueries.ConversationDetail detail = controller.get(conversation.getId(), 5);

        assertThat(detail.hasEarlier()).isTrue();
        assertThat(detail.messages()).hasSize(20);
    }

    // @find: test earlier messages require the orgs conversation, chat controller
    @Test
    @DisplayName("earlier messages require the conversation to belong to this org, and reads no messages otherwise")
    void earlierMessagesRequireTheOrgsConversation() {
        UUID otherId = UUID.randomUUID();
        when(conversations.findByIdAndOrgId(otherId, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.earlierMessages(otherId, 10, 50))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(messages);
    }

    // @find: test earlier messages are ascending, chat controller
    @Test
    @DisplayName("an older page of messages is returned oldest first")
    void earlierMessagesAreAscending() {
        List<ChatMessage> descending = List.of(
                messageAt(conversation.getId(), 5),
                messageAt(conversation.getId(), 4),
                messageAt(conversation.getId(), 3));
        when(messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(
                        eq(conversation.getId()), eq(6), any()))
                .thenReturn(descending);

        ConversationQueries.MessagesPage page = controller.earlierMessages(conversation.getId(), 6, 50);

        assertThat(page.messages())
                .extracting(ConversationQueries.ChatMessageView::position)
                .containsExactly(3, 4, 5);
    }

    // @find: test list rejects unknown scope, chat controller
    @Test
    @DisplayName("an unknown scope is rejected")
    void listRejectsUnknownScope() {
        assertThatThrownBy(() -> controller.list("", "bogus", 0, 50))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
