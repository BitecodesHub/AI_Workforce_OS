package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.MessageFeedback;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.MessageFeedbacks;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Rating an answer: who may, which messages, what the server decides for itself, and what is
 * recorded. The unique index and the upsert are read against the real schema in
 * MessageFeedbacksPersistenceTest.
 */
class FeedbackControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private MessageFeedbacks feedbacks;
    private Runs runs;
    private AuditClient audit;
    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        feedbacks = mock(MessageFeedbacks.class);
        runs = mock(Runs.class);
        audit = mock(AuditClient.class);
        controller = new FeedbackController(conversations, messages, feedbacks, runs, audit);

        RequestContext.setActor(Actor.user(PERSON.toString(), ORG.toString(), "role", Set.of("chat:use"), 0L));
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION);
        conversation.setOrgId(ORG);
        when(conversations.findByIdAndOrgId(CONVERSATION, ORG)).thenReturn(Optional.of(conversation));
        answer("answer", "agent", AGENT, Map.of("runId", RUN.toString()));
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(RUN);
        when(runs.findByIdAndOrgId(RUN, ORG)).thenReturn(Optional.of(run));
        when(feedbacks.findByOrgIdAndMessageIdAndUserId(ORG, MESSAGE, PERSON))
                .thenAnswer(call -> Optional.of(stored(MessageFeedback.POSITIVE, null)));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private void answer(String kind, String authorKind, UUID agentId, Map<String, Object> detail) {
        ChatMessage message = ChatMessage.of(ORG, CONVERSATION, 2, authorKind, null, agentId, kind, "text", detail, null);
        when(messages.findByIdAndConversationId(MESSAGE, CONVERSATION)).thenReturn(Optional.of(message));
    }

    private static MessageFeedback stored(short rating, String reason) {
        MessageFeedback feedback = new MessageFeedback();
        feedback.setOrgId(ORG);
        feedback.setConversationId(CONVERSATION);
        feedback.setMessageId(MESSAGE);
        feedback.setUserId(PERSON);
        feedback.setRating(rating);
        feedback.setReason(reason);
        feedback.setUpdatedAt(Instant.parse("2026-10-15T10:00:00Z"));
        return feedback;
    }

    private FeedbackController.FeedbackView rate(Integer rating, String reason) {
        return controller.rate(CONVERSATION, MESSAGE, new FeedbackController.RateRequest(rating, reason));
    }

    // ---- Who may ---------------------------------------------------------------------------

    @Test
    @DisplayName("rating and withdrawing need chat:use, reading a run's ratings needs run:read")
    void permissions() throws Exception {
        assertThat(permission("rate", UUID.class, UUID.class, FeedbackController.RateRequest.class))
                .containsExactly(Permission.Codes.CHAT_USE);
        assertThat(permission("withdraw", UUID.class, UUID.class)).containsExactly(Permission.Codes.CHAT_USE);
        assertThat(permission("inConversation", UUID.class)).containsExactly(Permission.Codes.CHAT_USE);
        assertThat(permission("onRun", UUID.class)).containsExactly(Permission.Codes.RUN_READ);
    }

    private static String[] permission(String name, Class<?>... parameters) throws Exception {
        return FeedbackController.class.getMethod(name, parameters).getAnnotation(RequiresPermission.class).value();
    }

    @Test
    @DisplayName("only a signed-in person's own opinion is a rating: an agent or a machine key cannot give one")
    void onlyAPersonRates() {
        for (Actor.Kind kind : List.of(Actor.Kind.AGENT, Actor.Kind.API_KEY, Actor.Kind.SYSTEM)) {
            RequestContext.setActor(new Actor(
                    "someone", kind, ORG.toString(), null, Set.of("chat:use"), 0L, null, null, null, Map.of()));

            ApiException refused = catchThrowableOfType(() -> rate(1, null), ApiException.class);

            assertThat(refused.code()).as(kind.name()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(feedbacks);
    }

    // ---- Which messages --------------------------------------------------------------------

    @Test
    @DisplayName("a conversation of another workspace is not found, so its existence is not confirmed")
    void otherWorkspacesConversation() {
        RequestContext.setActor(Actor.user(PERSON.toString(), OTHER_ORG.toString(), "role", Set.of("chat:use"), 0L));

        ApiException refused = catchThrowableOfType(() -> rate(1, null), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(feedbacks, audit);
    }

    @Test
    @DisplayName("a message that is not in that conversation is not found")
    void messageOfAnotherConversation() {
        when(messages.findByIdAndConversationId(MESSAGE, CONVERSATION)).thenReturn(Optional.empty());

        ApiException refused = catchThrowableOfType(() -> rate(1, null), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(feedbacks);
    }

    @Test
    @DisplayName("a message row of another workspace is not found even when the ids line up")
    void messageOfAnotherWorkspace() {
        ChatMessage foreign = ChatMessage.of(
                OTHER_ORG, CONVERSATION, 2, "agent", null, AGENT, "answer", "text", Map.of(), null);
        when(messages.findByIdAndConversationId(MESSAGE, CONVERSATION)).thenReturn(Optional.of(foreign));

        ApiException refused = catchThrowableOfType(() -> rate(1, null), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("only an agent's answer can be rated: a question, a notice or a person's own message cannot")
    void onlyAnswers() {
        for (String[] kinds : new String[][] {{"question", "agent"}, {"notice", "system"}, {"text", "user"}, {"answer", "user"}}) {
            answer(kinds[0], kinds[1], AGENT, Map.of());

            ApiException refused = catchThrowableOfType(() -> rate(1, null), ApiException.class);

            assertThat(refused.code()).as(kinds[0] + " by " + kinds[1]).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        verify(feedbacks, never()).upsert(any(), any(), any(), any(), any(), any(), any(), anyShort(), any());
    }

    private static short anyShort() {
        return org.mockito.ArgumentMatchers.anyShort();
    }

    // ---- What is stored --------------------------------------------------------------------

    @Test
    @DisplayName("the agent and the run come from the stored answer, and the vote is the caller's own")
    void serverResolvesAgentAndRun() {
        FeedbackController.FeedbackView view = rate(-1, "  It missed the second half.  ");

        verify(feedbacks)
                .upsert(
                        any(UUID.class),
                        eq(ORG),
                        eq(CONVERSATION),
                        eq(MESSAGE),
                        eq(PERSON),
                        eq(AGENT),
                        eq(RUN),
                        eq((short) -1),
                        eq("It missed the second half."));
        assertThat(view.messageId()).isEqualTo(MESSAGE);
    }

    @Test
    @DisplayName("a run named on the message that is not this workspace's is not carried onto the rating")
    void foreignRunIsDropped() {
        UUID foreignRun = UUID.randomUUID();
        answer("answer", "agent", AGENT, Map.of("runId", foreignRun.toString()));
        when(runs.findByIdAndOrgId(foreignRun, ORG)).thenReturn(Optional.empty());

        rate(1, null);

        verify(feedbacks)
                .upsert(any(), eq(ORG), eq(CONVERSATION), eq(MESSAGE), eq(PERSON), eq(AGENT), eq(null), eq((short) 1), eq(null));
    }

    @Test
    @DisplayName("an answer whose message has no agent column falls back to the agent in its detail")
    void agentFromDetail() {
        answer("answer", "agent", null, Map.of("agentId", AGENT.toString(), "runId", RUN.toString()));

        rate(1, null);

        verify(feedbacks)
                .upsert(any(), eq(ORG), eq(CONVERSATION), eq(MESSAGE), eq(PERSON), eq(AGENT), eq(RUN), eq((short) 1), eq(null));
    }

    @Test
    @DisplayName("a rating is 1 or -1 and nothing else")
    void ratingValues() {
        for (Integer bad : new Integer[] {0, 2, -2, 5, null}) {
            ApiException refused = catchThrowableOfType(() -> rate(bad, null), ApiException.class);
            assertThat(refused.code()).as(String.valueOf(bad)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        verifyNoInteractions(feedbacks);
    }

    @Test
    @DisplayName("a blank reason is no reason, and one over 500 characters is refused")
    void reasonRules() {
        rate(1, "   ");
        verify(feedbacks).upsert(any(), any(), any(), any(), any(), any(), any(), eq((short) 1), eq(null));

        ApiException refused = catchThrowableOfType(() -> rate(-1, "x".repeat(501)), ApiException.class);
        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        rate(-1, "x".repeat(500));
        verify(feedbacks).upsert(any(), any(), any(), any(), any(), any(), any(), eq((short) -1), eq("x".repeat(500)));
    }

    // ---- Audit -----------------------------------------------------------------------------

    @Test
    @DisplayName("a rating is audited as chat.answer.rated, naming the verdict, the agent and the run but never the reason")
    void audited() {
        rate(-1, "wrong");

        ArgumentCaptor<Map<String, Object>> detail = mapCaptor();
        verify(audit)
                .record(
                        eq(ORG),
                        any(Actor.class),
                        eq("chat.answer.rated"),
                        eq("chat_message"),
                        eq(MESSAGE.toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("outcome", "negative")
                .containsEntry("agentId", AGENT.toString())
                .containsEntry("runId", RUN.toString())
                .containsEntry("hasReason", true)
                .doesNotContainValue("wrong");

        rate(1, null);
        ArgumentCaptor<Map<String, Object>> second = mapCaptor();
        verify(audit, org.mockito.Mockito.times(2))
                .record(any(), any(), any(), any(), any(), any(), second.capture());
        assertThat(second.getValue()).containsEntry("outcome", "positive").containsEntry("hasReason", false);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    @DisplayName("a refused rating is not audited")
    void refusedIsNotAudited() {
        catchThrowableOfType(() -> rate(3, null), ApiException.class);

        verifyNoInteractions(audit);
    }

    // ---- Withdrawing and reading -----------------------------------------------------------

    @Test
    @DisplayName("withdrawing removes only the caller's own vote, and withdrawing a vote that is not there is not an error")
    void withdraw() {
        when(feedbacks.withdraw(ORG, MESSAGE, PERSON)).thenReturn(0);

        controller.withdraw(CONVERSATION, MESSAGE);

        verify(feedbacks).withdraw(ORG, MESSAGE, PERSON);
    }

    @Test
    @DisplayName("withdrawing from another workspace's conversation finds nothing")
    void withdrawOtherWorkspace() {
        RequestContext.setActor(Actor.user(PERSON.toString(), OTHER_ORG.toString(), "role", Set.of("chat:use"), 0L));

        ApiException refused = catchThrowableOfType(() -> controller.withdraw(CONVERSATION, MESSAGE), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(feedbacks, never()).withdraw(any(), any(), any());
    }

    @Test
    @DisplayName("a run's ratings come with their reasons, newest first, for a run of this workspace only")
    void ratingsOnARun() {
        MessageFeedback down = stored(MessageFeedback.NEGATIVE, "wrong");
        when(feedbacks.findByOrgIdAndRunIdOrderByUpdatedAtDesc(ORG, RUN)).thenReturn(List.of(down));

        FeedbackController.RunFeedback onRun = controller.onRun(RUN);

        assertThat(onRun.ratings()).hasSize(1);
        assertThat(onRun.ratings().get(0).rating()).isEqualTo(-1);
        assertThat(onRun.ratings().get(0).reason()).isEqualTo("wrong");

        UUID foreign = UUID.randomUUID();
        when(runs.findByIdAndOrgId(foreign, ORG)).thenReturn(Optional.empty());
        ApiException refused = catchThrowableOfType(() -> controller.onRun(foreign), ApiException.class);
        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("the ratings a person has given in a conversation are theirs alone")
    void ratingsInAConversation() {
        when(feedbacks.findByOrgIdAndConversationIdAndUserId(ORG, CONVERSATION, PERSON))
                .thenReturn(List.of(stored(MessageFeedback.POSITIVE, null)));

        FeedbackController.ConversationFeedback mine = controller.inConversation(CONVERSATION);

        assertThat(mine.ratings()).extracting(FeedbackController.FeedbackView::rating).containsExactly(1);
        verify(feedbacks).findByOrgIdAndConversationIdAndUserId(ORG, CONVERSATION, PERSON);
    }
}
