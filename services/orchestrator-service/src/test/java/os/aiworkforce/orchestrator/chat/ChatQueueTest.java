// @find: tests for chat queue, chat, second message is queued, queue starts in order, stop releases the queue, cancel and edit, others cannot change it, waiting decision and start now, expiry, retry while busy is refused, ChatQueueTest, ChatQueue
// @what: Tests for ChatQueue in the orchestrator chat package (10 test methods).
// @flow: Exercises ChatQueue
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.ChatQueuedMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.ChatQueuedMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * One answer at a time per conversation: messages sent while work is in progress wait in order,
 * survive in the queue table, can be edited or cancelled, start by themselves when the work ends,
 * and two pieces of work never run at once in one conversation - not even when many messages are
 * sent at the same moment.
 *
 * <p>The tables are in-memory lists behind the repository mocks, and the conversation's row lock is
 * a real lock taken by the appender's {@code refresh(..., PESSIMISTIC_WRITE)} and released when the
 * transaction commits or rolls back, so concurrent sends are serialised exactly as Postgres would.
 */
class ChatQueueTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private Agents agents;
    private Goals goals;
    private Tasks tasks;
    private GoalService goalService;
    private ChatQueuedMessages queueRows;
    private CoordinatorService coordinator;
    private ChatQueue queue;
    private ChatQueueRunner runner;

    private Conversation conversation;
    private Agent research;
    private UUID requesterId;

    private final List<Goal> goalRows = new CopyOnWriteArrayList<>();
    private final List<Task> taskRows = new CopyOnWriteArrayList<>();
    private final List<ChatQueuedMessage> queued = new CopyOnWriteArrayList<>();
    private final List<ChatMessage> thread = new CopyOnWriteArrayList<>();
    private final ReentrantLock rowLock = new ReentrantLock();
    private final AtomicInteger overlaps = new AtomicInteger();

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        agents = mock(Agents.class);
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        goalService = mock(GoalService.class);
        queueRows = mock(ChatQueuedMessages.class);
        ModelRouterPlanner planner = mock(ModelRouterPlanner.class);
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        WorkspaceZoneLookup zones = mock(WorkspaceZoneLookup.class);
        SchedulePreviewer previewer = mock(SchedulePreviewer.class);
        GeneralEmployee generalEmployee = mock(GeneralEmployee.class);
        EntityManager entityManager = mock(EntityManager.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        // The conversation row lock: taken by the appender's locking refresh, held to commit.
        doAnswer(call -> {
                    if (!rowLock.isHeldByCurrentThread()) {
                        rowLock.lock();
                    }
                    return null;
                })
                .when(entityManager)
                .refresh(any(), eq(LockModeType.PESSIMISTIC_WRITE));
        doAnswer(call -> {
                    releaseRowLock();
                    return null;
                })
                .when(transactions)
                .commit(any());
        doAnswer(call -> {
                    releaseRowLock();
                    return null;
                })
                .when(transactions)
                .rollback(any());

        ChatAppender appender = new ChatAppender(conversations, messages, entityManager);
        coordinator = new CoordinatorService(
                conversations,
                messages,
                agents,
                mock(AgentVersions.class),
                mock(ToolGrants.class),
                goals,
                goalService,
                planner,
                knowledge,
                zones,
                previewer,
                generalEmployee,
                appender,
                transactions);
        queue = new ChatQueue(queueRows, goals, tasks, conversations);
        @SuppressWarnings("unchecked")
        ObjectProvider<CoordinatorService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(coordinator);
        runner = new ChatQueueRunner(provider, queue);
        // Looks happen only when a test asks for them, so each step can be checked in turn.
        runner.useExecutor(task -> {});
        coordinator.useQueue(queue, queueRows, runner);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        lenient().when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
        lenient().when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messages.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            thread.add(m);
            return m;
        });
        lenient().when(messages.findEarlierTurns(any(), anyInt(), any())).thenReturn(List.of());
        lenient().when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(previewer.tryParse(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(planner.plan(eq(ORG), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(generalEmployee.activeIn(any())).thenReturn(Optional.empty());

        research = new Agent();
        research.setId(UUID.randomUUID());
        research.setKey("research");
        research.setName("Research");
        research.setCategory("research");
        research.setStatus("active");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research));
        lenient().when(agents.findByIdAndOrgId(research.getId(), ORG)).thenReturn(Optional.of(research));

        // Goals: an in-memory table; a new chat goal is running until a test finishes it.
        lenient().when(goalService.createGoal(eq(ORG), any(), eq(true))).thenAnswer(inv -> {
            GoalService.NewGoal spec = inv.getArgument(1);
            if (!activeGoals().isEmpty()) {
                overlaps.incrementAndGet();
            }
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID());
            goal.setOrgId(ORG);
            goal.setConversationId(spec.conversationId());
            goal.setStatus("running");
            goal.setDescription(spec.description());
            goalRows.add(goal);
            return goal;
        });
        lenient()
                .when(goals.findByOrgIdAndConversationIdAndStatusIn(eq(ORG), any(), anyCollection()))
                .thenAnswer(inv -> {
                    Collection<String> statuses = inv.getArgument(2);
                    return goalRows.stream()
                            .filter(g -> statuses.contains(g.getStatus()))
                            .toList();
                });
        lenient().when(goals.findByIdAndOrgId(any(), eq(ORG))).thenAnswer(inv -> goalRows.stream()
                .filter(g -> g.getId().equals(inv.getArgument(0)))
                .findFirst());
        lenient().when(tasks.findByGoalIdInOrderByPositionAsc(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return taskRows.stream().filter(t -> ids.contains(t.getGoalId())).toList();
        });
        lenient()
                .when(goalService.cancel(eq(ORG), any(UUID.class), anyString()))
                .thenAnswer(inv -> {
                    goalRows.stream()
                            .filter(g -> g.getId().equals(inv.getArgument(1)))
                            .forEach(g -> g.setStatus("cancelled"));
                    return new GoalService.CancelCounts(1, 1, 0, 0);
                });

        // The queue table.
        lenient().when(queueRows.save(any(ChatQueuedMessage.class))).thenAnswer(inv -> {
            ChatQueuedMessage row = inv.getArgument(0);
            if (!queued.contains(row)) {
                queued.add(row);
            }
            return row;
        });
        lenient().doAnswer(inv -> queued.remove(inv.<ChatQueuedMessage>getArgument(0)))
                .when(queueRows)
                .delete(any(ChatQueuedMessage.class));
        lenient().when(queueRows.findByConversationIdOrderByCreatedAtAsc(any())).thenAnswer(inv -> List.copyOf(queued));
        lenient()
                .when(queueRows.findByIdAndOrgIdAndConversationId(any(), eq(ORG), any()))
                .thenAnswer(inv -> queued.stream()
                        .filter(q -> q.getId().equals(inv.getArgument(0)))
                        .findFirst());

        requesterId = UUID.randomUUID();
        signIn(requesterId);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private void releaseRowLock() {
        while (rowLock.isHeldByCurrentThread()) {
            rowLock.unlock();
        }
    }

    private static void signIn(UUID userId) {
        RequestContext.setActor(
                Actor.user(userId.toString(), ORG.toString(), "role", Set.of("chat:use", "task:create", "task:cancel"), 0L));
    }

    private List<Goal> activeGoals() {
        return goalRows.stream()
                .filter(g -> ChatQueue.ACTIVE_GOAL_STATUSES.contains(g.getStatus()))
                .toList();
    }

    private CoordinatorService.SendOutcome send(String text) {
        return coordinator.send(ORG, conversation.getId(), text, null, null, "Bearer x");
    }

    private void finishActiveWork() {
        activeGoals().forEach(g -> g.setStatus("completed"));
    }

    private List<String> userTurns() {
        return thread.stream()
                .filter(m -> "user".equals(m.getAuthorKind()))
                .map(ChatMessage::getContent)
                .toList();
    }

    // @find: test second message is queued, chat queue
    @Test
    @DisplayName("a message sent while an answer is coming is queued, not answered alongside")
    void secondMessageIsQueued() {
        CoordinatorService.SendOutcome first = send("@Research first question");
        assertThat(first.queued()).isNull();
        assertThat(activeGoals()).hasSize(1);

        CoordinatorService.SendOutcome second = send("@Research second question");
        CoordinatorService.SendOutcome third = send("@Research third question");

        assertThat(second.messages()).isEmpty();
        assertThat(second.queued().getText()).isEqualTo("@Research second question");
        assertThat(third.queued()).isNotNull();
        assertThat(activeGoals()).hasSize(1);
        assertThat(userTurns()).containsExactly("@Research first question");
        // Kept in the queue table, which is what a reload or another tab reads.
        assertThat(queue.views(conversation.getId(), conversation, RequestContext.requireActor()))
                .extracting(ChatQueue.QueuedMessageView::text, ChatQueue.QueuedMessageView::order)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("@Research second question", 1),
                        org.assertj.core.groups.Tuple.tuple("@Research third question", 2));
        assertThat(queue.state(ORG, conversation)).isEqualTo(ChatQueue.Busy.WORKING);
    }

    // @find: test queue starts in order, chat queue
    @Test
    @DisplayName("queued messages start by themselves, in order, each only once the one before has finished")
    void queueStartsInOrder() {
        send("@Research one");
        send("@Research two");
        send("@Research three");

        // Nothing starts while the first is still working.
        runner.drainNow(ORG, conversation.getId());
        assertThat(activeGoals()).hasSize(1);
        assertThat(queued).hasSize(2);

        finishActiveWork();
        runner.drainNow(ORG, conversation.getId());
        assertThat(userTurns()).containsExactly("@Research one", "@Research two");
        assertThat(activeGoals()).hasSize(1);
        assertThat(queued).extracting(ChatQueuedMessage::getText).containsExactly("@Research three");
        // Started as the person who sent it.
        assertThat(thread.stream().filter(m -> "@Research two".equals(m.getContent())).findFirst().orElseThrow()
                        .getAuthorId())
                .isEqualTo(requesterId);

        finishActiveWork();
        runner.drainNow(ORG, conversation.getId());
        assertThat(userTurns()).containsExactly("@Research one", "@Research two", "@Research three");
        assertThat(queued).isEmpty();
        assertThat(overlaps.get()).isZero();
    }

    // @find: test stop releases the queue, chat queue
    @Test
    @DisplayName("stopping the active answer lets the queue proceed")
    void stopReleasesTheQueue() {
        send("@Research one");
        send("@Research two");

        activeGoals().forEach(g -> g.setStatus("cancelled"));
        runner.drainNow(ORG, conversation.getId());

        assertThat(userTurns()).containsExactly("@Research one", "@Research two");
    }

    // @find: test cancel and edit, chat queue
    @Test
    @DisplayName("a queued message can be cancelled, or edited before it starts")
    void cancelAndEdit() {
        send("@Research one");
        ChatQueuedMessage two = send("@Research two").queued();
        ChatQueuedMessage three = send("@Research three").queued();

        queue.cancel(ORG, conversation.getId(), two.getId(), RequestContext.requireActor());
        queue.edit(ORG, conversation.getId(), three.getId(), "@Research three, shorter", RequestContext.requireActor());

        finishActiveWork();
        runner.drainNow(ORG, conversation.getId());
        assertThat(userTurns()).containsExactly("@Research one", "@Research three, shorter");
    }

    // @find: test others cannot change it, chat queue
    @Test
    @DisplayName("only the sender (or the conversation's owner) may change a queued message")
    void othersCannotChangeIt() {
        send("@Research one");
        ChatQueuedMessage two = send("@Research two").queued();

        signIn(UUID.randomUUID());
        assertThatThrownBy(() -> queue.cancel(ORG, conversation.getId(), two.getId(), RequestContext.requireActor()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        assertThat(queue.views(conversation.getId(), conversation, RequestContext.requireActor()).getFirst().canManage())
                .isFalse();
    }

    // @find: test waiting decision and start now, chat queue
    @Test
    @DisplayName("work parked for a decision holds the queue; Start now anyway stops it and starts the message")
    void waitingDecisionAndStartNow() {
        send("@Research one");
        Goal parked = activeGoals().getFirst();
        Task task = new Task();
        task.setGoalId(parked.getId());
        task.setStatus("waiting_approval");
        taskRows.add(task);

        ChatQueuedMessage two = send("@Research two").queued();
        assertThat(queue.state(ORG, conversation)).isEqualTo(ChatQueue.Busy.WAITING_DECISION);
        runner.drainNow(ORG, conversation.getId());
        assertThat(queued).hasSize(1);

        CoordinatorService.SendOutcome started = coordinator.startQueuedNow(ORG, conversation.getId(), two.getId());

        assertThat(parked.getStatus()).isEqualTo("cancelled");
        assertThat(started.messages()).isNotEmpty();
        assertThat(userTurns()).containsExactly("@Research one", "@Research two");
        assertThat(activeGoals()).hasSize(1);
        assertThat(queued).isEmpty();
    }

    // @find: test expiry, chat queue
    @Test
    @DisplayName("a message that waited more than a day expires: shown, never started, not editable")
    void expiry() {
        send("@Research one");
        ChatQueuedMessage two = send("@Research two").queued();
        ReflectionTestUtils.setField(two, "createdAt", Instant.now().minus(ChatQueue.EXPIRY).minusSeconds(60));

        finishActiveWork();
        runner.drainNow(ORG, conversation.getId());

        assertThat(userTurns()).containsExactly("@Research one");
        assertThat(queue.views(conversation.getId(), conversation, RequestContext.requireActor()).getFirst().status())
                .isEqualTo("expired");
        assertThatThrownBy(() -> queue.edit(
                        ORG, conversation.getId(), two.getId(), "again", RequestContext.requireActor()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    // @find: test retry while busy is refused, chat queue
    @Test
    @DisplayName("retrying other work while an answer is in progress is refused with a plain 409")
    void retryWhileBusyIsRefused() {
        send("@Research one");
        Goal failed = new Goal();
        failed.setId(UUID.randomUUID());
        failed.setOrgId(ORG);
        failed.setConversationId(conversation.getId());
        failed.setStatus("failed");
        goalRows.add(failed);

        assertThatThrownBy(() -> coordinator.retryGoal(ORG, conversation.getId(), failed.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RESOURCE_IN_USE);
                    assertThat(e.getMessage()).isEqualTo(CoordinatorService.BUSY_MESSAGE);
                });
    }

    // @find: test concurrent sends serialise, chat queue
    @Test
    @DisplayName("many messages sent at the same moment start one piece of work; the rest queue; never two at once")
    void concurrentSendsSerialise() throws Exception {
        int senders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CoordinatorService.SendOutcome>> results = new ArrayList<>();
        Actor actor = RequestContext.requireActor();
        for (int i = 0; i < senders; i++) {
            String text = "@Research message " + i;
            results.add(pool.submit(() -> {
                RequestContext.setActor(actor);
                go.await();
                try {
                    return send(text);
                } finally {
                    RequestContext.clear();
                }
            }));
        }
        go.countDown();
        int started = 0;
        int waiting = 0;
        for (Future<CoordinatorService.SendOutcome> result : results) {
            CoordinatorService.SendOutcome outcome = result.get(30, TimeUnit.SECONDS);
            if (outcome.queued() == null) {
                started++;
            } else {
                waiting++;
            }
        }
        pool.shutdown();

        assertThat(started).isEqualTo(1);
        assertThat(waiting).isEqualTo(senders - 1);
        assertThat(activeGoals()).hasSize(1);

        // Draining twice at once, after each finish, still starts exactly one at a time.
        for (int round = 0; round < senders - 1; round++) {
            finishActiveWork();
            ExecutorService drains = Executors.newFixedThreadPool(2);
            Future<?> a = drains.submit(() -> runner.drainNow(ORG, conversation.getId()));
            Future<?> b = drains.submit(() -> runner.drainNow(ORG, conversation.getId()));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            drains.shutdown();
            assertThat(activeGoals()).hasSize(1);
        }
        assertThat(queued).isEmpty();
        assertThat(userTurns()).hasSize(senders);
        assertThat(overlaps.get()).isZero();
    }

    // @find: test actor round trips, chat queue
    @Test
    @DisplayName("the sender's authority is kept with a queued message and restored when it starts")
    void actorRoundTrips() {
        Actor actor = Actor.user("u-1", ORG.toString(), "manager", Set.of("chat:use", "task:create"), 7L);
        Map<String, Object> kept = ChatQueue.snapshot(actor);

        Actor back = ChatQueue.restore(kept);

        assertThat(back.id()).isEqualTo("u-1");
        assertThat(back.orgId()).isEqualTo(ORG.toString());
        assertThat(back.roleId()).isEqualTo("manager");
        assertThat(back.permissions()).containsExactlyInAnyOrder("chat:use", "task:create");
        assertThat(back.permissionVersion()).isEqualTo(7L);
        assertThat(ChatQueue.restore(Map.of())).isNull();
    }
}
