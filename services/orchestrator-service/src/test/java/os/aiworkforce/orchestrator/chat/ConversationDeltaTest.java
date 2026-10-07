package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.boot.autoconfigure.web.format.DateTimeFormatters;
import org.springframework.boot.autoconfigure.web.format.WebConversionService;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.ConversationMarks;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Reading a conversation as what changed since the reader last looked: only the messages after a
 * position, and the goals and questions that moved, so an open thread's poll does not carry the
 * whole window every few seconds.
 */
class ConversationDeltaTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant SINCE = Instant.parse("2026-10-04T01:00:00Z");

    private Conversations conversations;
    private ChatMessages messages;
    private Goals goals;
    private Tasks tasks;
    private Runs runs;
    private QuestionService questions;
    private ConversationQueries queries;
    private ChatController controller;
    private Conversation conversation;
    private Actor actor;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        questions = mock(QuestionService.class);
        queries = new ConversationQueries(
                conversations, mock(ConversationMarks.class), messages, goals, tasks, runs, questions);
        controller = new ChatController(conversations, queries, null, null);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        conversation.setTitle("Quarterly numbers");
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));

        actor = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("chat:use"), 0L);
        RequestContext.setActor(actor);

        lenient().when(goals.activeByConversation(any(), anyCollection())).thenReturn(List.of());
        lenient().when(tasks.waitingApprovalByConversation(any(), anyCollection())).thenReturn(List.of());
        lenient().when(questions.pendingByConversation(any(), anyCollection())).thenReturn(List.of());
        lenient()
                .when(goals.findByOrgIdAndConversationIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(goals.findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(goals.findAllById(any())).thenReturn(List.of());
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of());
        lenient().when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of());
        lenient().when(questions.forConversation(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(questions.views(any(), any())).thenAnswer(call -> List.of());
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private ChatMessage messageAt(int position) {
        return messageAt(position, null);
    }

    private ChatMessage messageAt(int position, UUID goalId) {
        return ChatMessage.of(
                ORG,
                conversation.getId(),
                position,
                "user",
                null,
                null,
                "text",
                "message " + position,
                Map.of(),
                goalId);
    }

    /** The newest {@code count} messages of a thread that reaches position {@code newest}, newest first. */
    private void threadUpTo(int newest, int count) {
        List<ChatMessage> descending = new ArrayList<>();
        for (int position = newest; position > newest - count && position >= 0; position--) {
            descending.add(messageAt(position));
        }
        when(messages.findByConversationIdOrderByPositionDesc(eq(conversation.getId()), any()))
                .thenReturn(descending);
    }

    private Goal goal(String status, UUID conversationId) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("A goal");
        goal.setStatus(status);
        goal.setConversationId(conversationId);
        ReflectionTestUtils.setField(goal, "createdAt", Instant.now());
        return goal;
    }

    private RunQuestion question(String status, UUID goalId, Instant updatedAt) {
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setStatus(status);
        question.setGoalId(goalId);
        ReflectionTestUtils.setField(question, "updatedAt", updatedAt);
        return question;
    }

    @Test
    @DisplayName("an incremental read returns only the messages after the position the reader holds")
    void returnsOnlyMessagesAfterThePosition() {
        threadUpTo(12, 51);

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 10, SINCE);

        assertThat(delta.messages())
                .extracting(ConversationQueries.ChatMessageView::position)
                .containsExactly(11, 12);
        assertThat(delta.hasEarlier()).isFalse();
    }

    @Test
    @DisplayName("an incremental read asks for at most a small fixed number of messages, never the whole window")
    void asksForASmallNumberOfMessages() {
        threadUpTo(12, 13);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);

        queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        verify(messages).findByConversationIdOrderByPositionDesc(eq(conversation.getId()), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(51);
        verify(messages, never()).findByConversationIdAndPositionGreaterThanOrderByPosition(any(), anyInt());
    }

    @Test
    @DisplayName("nothing new comes back as no messages, not as an error")
    void nothingNewIsEmpty() {
        threadUpTo(12, 51);

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        assertThat(delta.messages()).isEmpty();
        assertThat(delta.conversation().id()).isEqualTo(conversation.getId());
    }

    @Test
    @DisplayName("a reader holding nothing yet (position -1) gets the whole short thread")
    void aReaderHoldingNothingGetsTheWholeShortThread() {
        threadUpTo(2, 51);

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, -1, SINCE);

        assertThat(delta.messages())
                .extracting(ConversationQueries.ChatMessageView::position)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("more new messages than fit in a small answer return the whole window instead, with its own hasEarlier")
    void tooManyNewMessagesFallBackToTheWholeWindow() {
        List<ChatMessage> descending = new ArrayList<>();
        for (int position = 400; position >= 0; position--) {
            descending.add(messageAt(position));
        }
        // The small read sees 51 rows all newer than the position held; the full read then sees 201.
        when(messages.findByConversationIdOrderByPositionDesc(eq(conversation.getId()), any()))
                .thenAnswer(call -> descending.subList(0, ((Pageable) call.getArgument(1)).getPageSize()));

        ConversationQueries.ConversationDetail result =
                queries.detail(ORG, actor, conversation.getId(), 200, 10, SINCE);

        assertThat(result.messages()).hasSize(200);
        assertThat(result.messages().getFirst().position()).isEqualTo(201);
        assertThat(result.hasEarlier()).isTrue();
    }

    @Test
    @DisplayName("without a since time there is nothing to compare goals and questions against, so it is the whole window")
    void withoutSinceItIsTheWholeWindow() {
        threadUpTo(400, 201);

        ConversationQueries.ConversationDetail result =
                queries.detail(ORG, actor, conversation.getId(), 200, 10, null);

        assertThat(result.messages()).hasSize(200);
        assertThat(result.hasEarlier()).isTrue();
        verify(goals, never()).findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    @DisplayName("every response says what time the server read it, for the reader to ask about next")
    void everyResponseCarriesTheServersClock() {
        threadUpTo(12, 51);
        Instant before = Instant.now();

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);
        ConversationQueries.ConversationDetail whole = queries.detail(ORG, actor, conversation.getId(), 200);

        assertThat(delta.generatedAt()).isBetween(before, Instant.now());
        assertThat(whole.generatedAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("open goals are sent every time, with their tasks and what their runs cost")
    void openGoalsAreSentEveryTime() {
        threadUpTo(12, 51);
        Goal open = goal("running", conversation.getId());
        when(goals.findByOrgIdAndConversationIdAndStatusIn(eq(ORG), eq(conversation.getId()), any()))
                .thenReturn(List.of(open));
        when(goals.findAllById(any())).thenReturn(List.of(open));
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(open.getId());
        task.setStatus("running");
        when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of(task));
        Run first = new Run();
        first.setId(UUID.randomUUID());
        first.setTaskId(task.getId());
        first.setStatus("failed");
        first.setTotalCost(new java.math.BigDecimal("0.30"));
        Run second = new Run();
        second.setId(UUID.randomUUID());
        second.setTaskId(task.getId());
        second.setStatus("running");
        second.setTotalCost(new java.math.BigDecimal("0.10"));
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(second, first));

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        assertThat(delta.goals()).hasSize(1);
        assertThat(delta.goals().getFirst().tasks().getFirst().cost()).isEqualByComparingTo("0.40");
        assertThat(delta.goals().getFirst().tasks().getFirst().attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a goal that finished and has not changed since is not sent again, but one that changed is")
    void finishedGoalsAreSentOnlyOnceTheyChange() {
        threadUpTo(12, 51);
        Goal changed = goal("completed", conversation.getId());
        when(goals.findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(eq(ORG), eq(conversation.getId()), any()))
                .thenReturn(List.of(changed));
        when(goals.findAllById(any())).thenAnswer(call -> {
            Iterable<UUID> ids = call.getArgument(0);
            List<Goal> found = new ArrayList<>();
            ids.forEach(id -> {
                if (id.equals(changed.getId())) {
                    found.add(changed);
                }
            });
            return found;
        });

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        assertThat(delta.goals()).extracting(g -> g.id()).containsExactly(changed.getId());
        ArgumentCaptor<Instant> cut = ArgumentCaptor.forClass(Instant.class);
        verify(goals)
                .findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(
                        eq(ORG), eq(conversation.getId()), cut.capture());
        // A few seconds before the moment asked about, so a row that commits just after the read
        // that missed it is still caught by the next one.
        assertThat(cut.getValue()).isBefore(SINCE);
        assertThat(cut.getValue()).isAfter(SINCE.minus(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("the goal a new message refers to is sent even if it has not changed")
    void aNewMessagesGoalIsSent() {
        Goal referred = goal("completed", conversation.getId());
        List<ChatMessage> descending = List.of(messageAt(11, referred.getId()), messageAt(10));
        when(messages.findByConversationIdOrderByPositionDesc(eq(conversation.getId()), any()))
                .thenReturn(descending);
        when(goals.findAllById(any())).thenReturn(List.of(referred));

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 10, SINCE);

        assertThat(delta.goals()).extracting(g -> g.id()).containsExactly(referred.getId());
    }

    @Test
    @DisplayName("a goal from another workspace is never sent")
    void goalsAreKeptToTheWorkspace() {
        threadUpTo(12, 51);
        Goal foreign = goal("running", conversation.getId());
        foreign.setOrgId(UUID.randomUUID());
        when(goals.findByOrgIdAndConversationIdAndStatusIn(any(), any(), any())).thenReturn(List.of(foreign));
        when(goals.findAllById(any())).thenReturn(List.of(foreign));

        ConversationQueries.ConversationDetail delta =
                queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        assertThat(delta.goals()).isEmpty();
    }

    @Test
    @DisplayName("questions still open, changed since, or belonging to a goal that is sent come back; a settled old one does not")
    void questionsAreFilteredToWhatChanged() {
        threadUpTo(12, 51);
        Goal open = goal("running", conversation.getId());
        when(goals.findByOrgIdAndConversationIdAndStatusIn(any(), any(), any())).thenReturn(List.of(open));
        when(goals.findAllById(any())).thenReturn(List.of(open));

        RunQuestion pending = question("pending", null, SINCE.minus(1, ChronoUnit.DAYS));
        RunQuestion changed = question("answered", null, SINCE.plusSeconds(3));
        RunQuestion ofSentGoal = question("answered", open.getId(), SINCE.minus(1, ChronoUnit.DAYS));
        RunQuestion settled = question("answered", UUID.randomUUID(), SINCE.minus(1, ChronoUnit.DAYS));
        when(questions.forConversation(eq(ORG), eq(conversation.getId()), anyInt()))
                .thenReturn(List.of(settled, pending, changed, ofSentGoal));

        queries.detail(ORG, actor, conversation.getId(), 200, 12, SINCE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RunQuestion>> sent = ArgumentCaptor.forClass(List.class);
        verify(questions).views(sent.capture(), eq(actor));
        assertThat(sent.getValue()).containsExactlyInAnyOrder(pending, changed, ofSentGoal);
    }

    @Test
    @DisplayName("a conversation that is not in this workspace is a 404, whole or incremental")
    void anotherWorkspacesConversationIsNotFound() {
        UUID elsewhere = UUID.randomUUID();
        when(conversations.findByIdAndOrgId(elsewhere, ORG)).thenReturn(Optional.empty());

        for (Integer after : new Integer[] {null, 5}) {
            assertThatThrownBy(
                            () -> queries.detail(ORG, actor, elsewhere, 200, after, after == null ? null : SINCE))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Test
    @DisplayName("the endpoint passes after and since through, and a position below -1 reads as -1")
    void theEndpointPassesAfterAndSince() {
        threadUpTo(2, 51);

        ConversationQueries.ConversationDetail delta = controller.get(conversation.getId(), 200, -50, SINCE);

        // -50 was read as -1: the whole three-message thread is newer than it.
        assertThat(delta.messages()).hasSize(3);
        assertThat(delta.hasEarlier()).isFalse();
    }

    @Test
    @DisplayName("the endpoint without after and since is the whole window, as before")
    void theEndpointWithoutParametersIsTheWholeWindow() {
        threadUpTo(2, 201);

        ConversationQueries.ConversationDetail whole = controller.get(conversation.getId(), 200, null, null);

        assertThat(whole.messages()).hasSize(3);
        verify(goals, never()).findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    @DisplayName("since is read from the ISO time the server itself sent as generatedAt, to the microsecond")
    void sinceBindsFromTheTimeTheServerSent() {
        WebConversionService conversion = new WebConversionService(new DateTimeFormatters());
        Instant generatedAt = Instant.parse("2026-10-04T17:04:39.516297Z");

        assertThat(conversion.convert(generatedAt.toString(), Instant.class)).isEqualTo(generatedAt);
        assertThat(conversion.convert("2026-10-04T01:00:00Z", Instant.class)).isEqualTo(SINCE);
    }
}
