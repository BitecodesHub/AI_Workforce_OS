// @find: tests for team room service, presence per state, idle paused thinking browsing using waiting private, four-eyes approval wording, feed ordering, cursor paging, settle window, settle after a backlog, private work left out, direct runs in the feed, hand-off wording, TeamRoomServiceTest, TeamRoomService
// @what: Tests for TeamRoomService: each presence state, private-work anonymising, who an approval waits on, a fixed number of reads, feed order and paging, late commits after a backlog, and events in words.
// @flow: Exercises TeamRoomService with a mocked TeamRoomStore (presence) and an in-memory one (feed)
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.CallRecord;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.StepFact;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.ActiveRun;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.FeedRow;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.PendingApproval;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.PendingQuestion;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.Viewer;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.AgentPresence;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.FeedEvent;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.FeedPage;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.TeamRoom;
import os.aiworkforce.platform.context.Actor;

/**
 * The team room as a viewer sees it. Presence: every state an agent can be shown in, work from a
 * private conversation shown only as "private work", and the same few reads however many agents
 * there are. The conversation: oldest first, newest page by default, every event exactly once when
 * paging back, a poll that re-reads the settle window but never moves backwards, private work left
 * out, and each kind of event put into the right words.
 */
class TeamRoomServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-7000-8000-0000000000f2");
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private Agents agents;
    private Tasks tasks;
    private ApprovalService approvals;
    private QuestionService questions;
    private ConversationAccess access;
    private Actor viewer;

    @BeforeEach
    void setUp() {
        agents = mock(Agents.class);
        tasks = mock(Tasks.class);
        approvals = mock(ApprovalService.class);
        questions = mock(QuestionService.class);
        access = mock(ConversationAccess.class);
        viewer = Actor.user(VIEWER.toString(), ORG.toString(), "role", Set.of("run:read"), 0L);
        when(access.canReadAll(viewer)).thenReturn(false);
    }

    private TeamRoomService service(TeamRoomStore store) {
        return service(store, NOW);
    }

    private TeamRoomService service(TeamRoomStore store, Instant now) {
        return new TeamRoomService(store, agents, tasks, approvals, questions, access, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The viewer as the store is told about them: a person who sees only their own private work. */
    private static final Viewer AS_VIEWER = Viewer.person(VIEWER.toString());

    private static Agent agent(String name, String status) {
        Agent agent = new Agent();
        agent.setId(UUID.nameUUIDFromBytes(name.getBytes()));
        agent.setOrgId(ORG);
        agent.setKey(name.toLowerCase());
        agent.setName(name);
        agent.setCategory("operations");
        agent.setStatus(status);
        agent.setDescription(name + " does useful work.");
        return agent;
    }

    private static ActiveRun run(UUID runId, Agent agent, String status, boolean hidden) {
        return run(runId, agent, status, hidden, null, null);
    }

    private static ActiveRun run(
            UUID runId, Agent agent, String status, boolean hidden, PendingApproval approval, PendingQuestion question) {
        return new ActiveRun(
                runId,
                agent.getId(),
                UUID.nameUUIDFromBytes(("task" + runId).getBytes()),
                status,
                NOW.minusSeconds(60),
                null,
                "Draft the weekly report",
                UUID.nameUUIDFromBytes(("goal" + runId).getBytes()),
                "Weekly report",
                REQUESTER,
                hidden,
                approval,
                question);
    }

    private static PendingApproval approval(String summary, UUID requestedBy) {
        return new PendingApproval(
                UUID.randomUUID(), summary, requestedBy, NOW.minusSeconds(30), "OUTBOUND", "approval:decide");
    }

    /** Matches the decision check built for one pending approval. */
    private static Approval isFor(PendingApproval pending) {
        return argThat(check -> check != null && pending.id().equals(check.getId()));
    }

    private static StepFact modelCall(UUID runId, CallRecord... calls) {
        return new StepFact(runId, "model_call", 3, NOW.minusSeconds(5), List.of(calls), null, null, null);
    }

    private static AgentPresence of(TeamRoom room, Agent agent) {
        return room.agents().stream()
                .filter(presence -> presence.id().equals(agent.getId()))
                .findFirst()
                .orElseThrow();
    }

    // ---- Presence ----------------------------------------------------------------------------

    @Nested
    @DisplayName("presence")
    class Presence {

        private TeamRoomStore store;

        @BeforeEach
        void stubs() {
            store = mock(TeamRoomStore.class);
            when(store.stepsSinceLastModelCall(anyCollection())).thenReturn(Map.of());
            when(store.lastPages(anyCollection())).thenReturn(Map.of());
            when(store.queuedByAgent(eq(ORG), any())).thenReturn(Map.of());
            when(store.activeRuns(eq(ORG), any())).thenReturn(List.of());
        }

        // @find: test idle paused and retired, team room presence
        @Test
        @DisplayName("an agent with nothing in progress is idle, or paused when it is paused; a retired agent is not in the room")
        void idlePausedAndRetired() {
            Agent ada = agent("Ada", "active");
            Agent ben = agent("Ben", "paused");
            Agent cy = agent("Cy", "retired");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada, ben, cy));
            when(store.queuedByAgent(eq(ORG), any())).thenReturn(Map.of(ada.getId(), 2));

            TeamRoom room = service(store).presence(ORG, viewer);

            assertThat(room.generatedAt()).isEqualTo(NOW);
            assertThat(room.agents()).extracting(AgentPresence::name).containsExactly("Ada", "Ben");
            AgentPresence idle = of(room, ada);
            assertThat(idle.state()).isEqualTo("idle");
            assertThat(idle.queued()).isEqualTo(2);
            assertThat(idle.description()).isEqualTo("Ada does useful work.");
            assertThat(idle.runId()).isNull();
            assertThat(of(room, ben).state()).isEqualTo("paused");
            // Nothing is waiting, so no decision or answer is checked, and no queue is ever read whole.
            verify(approvals, never()).canDecide(any(), any());
            verify(questions, never()).canAnswer(any(), any());
            verify(approvals, never()).pending(any());
            verify(questions, never()).pending(any(), anyInt());
        }

        // @find: test thinking browsing and using, team room presence
        @Test
        @DisplayName("running work is thinking, browsing a named site, or using a named connector, with its task and goal")
        void thinkingBrowsingAndUsing() {
            Agent ada = agent("Ada", "active");
            Agent ben = agent("Ben", "active");
            Agent cy = agent("Cy", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada, ben, cy));
            UUID thinking = UUID.randomUUID();
            UUID browsing = UUID.randomUUID();
            UUID using = UUID.randomUUID();
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(
                            run(thinking, ada, "running", false),
                            run(browsing, ben, "running", false),
                            run(using, cy, "running", false)));
            when(store.stepsSinceLastModelCall(anyCollection()))
                    .thenReturn(Map.of(
                            browsing,
                            List.of(modelCall(
                                    browsing, new CallRecord("c1", "browser.open_page", "{\"url\":\"https://www.bbc.co.uk/news\"}"))),
                            using,
                            List.of(modelCall(using, new CallRecord("c1", "slack.post_message", "{}")))));

            TeamRoom room = service(store).presence(ORG, viewer);

            AgentPresence a = of(room, ada);
            assertThat(a.state()).isEqualTo("thinking");
            assertThat(a.runId()).isEqualTo(thinking);
            assertThat(a.taskTitle()).isEqualTo("Draft the weekly report");
            assertThat(a.goalTitle()).isEqualTo("Weekly report");
            assertThat(a.since()).isEqualTo(NOW.minusSeconds(60));
            assertThat(a.activeRuns()).isEqualTo(1);

            AgentPresence b = of(room, ben);
            assertThat(b.state()).isEqualTo("browsing");
            assertThat(b.site()).isEqualTo("bbc.co.uk");
            assertThat(b.server()).isEqualTo("browser");
            assertThat(b.since()).isEqualTo(NOW.minusSeconds(5));

            AgentPresence c = of(room, cy);
            assertThat(c.state()).isEqualTo("using_tool");
            assertThat(c.server()).isEqualTo("slack");
            assertThat(c.tool()).isEqualTo("post_message");
            assertThat(c.waitingForYou()).isFalse();
        }

        // @find: test waiting for an approval, team room presence
        @Test
        @DisplayName("work parked on an approval is waiting, for you only when you may decide it now, read without its payload")
        void waitingForAnApproval() {
            Agent ada = agent("Ada", "active");
            Agent ben = agent("Ben", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada, ben));
            UUID mine = UUID.randomUUID();
            UUID theirs = UUID.randomUUID();
            PendingApproval forMe = approval("Send the offer letter to the candidate", REQUESTER);
            PendingApproval notForMe = approval("Post the release note in Slack", REQUESTER);
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(
                            run(mine, ada, "waiting_approval", false, forMe, null),
                            run(theirs, ben, "waiting_approval", false, notForMe, null)));
            when(approvals.canDecide(eq(viewer), isFor(forMe))).thenReturn(true);
            when(approvals.canDecide(eq(viewer), isFor(notForMe))).thenReturn(false);

            TeamRoom room = service(store).presence(ORG, viewer);

            AgentPresence a = of(room, ada);
            assertThat(a.state()).isEqualTo("waiting_approval");
            assertThat(a.waitingForYou()).isTrue();
            assertThat(a.approvalId()).isEqualTo(forMe.id());
            assertThat(a.waitingText()).isEqualTo("Send the offer letter to the candidate");
            assertThat(a.since()).isEqualTo(forMe.requestedAt());
            assertThat(of(room, ben).waitingForYou()).isFalse();
            // The decision check sees what the four-eyes rule needs: the requester and the action class.
            verify(approvals).canDecide(eq(viewer), argThat(check -> check != null
                    && forMe.id().equals(check.getId())
                    && REQUESTER.equals(check.getRequestedBy())
                    && "OUTBOUND".equals(check.getActionClass())
                    && "approval:decide".equals(check.getRequiredPermission())
                    && check.isPending()
                    && ORG.equals(check.getOrgId())));
            // The workspace's whole queue (with every payload) is never loaded for the presence.
            verify(approvals, never()).pending(any());
            // Parked runs have no work under way, so no steps are read for them.
            verify(store).stepsSinceLastModelCall(List.of());
        }

        // @find: test an approval never names its requester as the one to decide, team room presence
        @Test
        @DisplayName("an approval never names the person who asked for the work as the one to decide it")
        void anApprovalNamesNoDecider() {
            Agent ada = agent("Ada", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada));
            UUID runId = UUID.randomUUID();
            // The viewer asked for the work; the four-eyes rule leaves the decision to someone else.
            PendingApproval own = approval("Send the offer letter", VIEWER);
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(run(runId, ada, "waiting_approval", false, own, null)));
            when(approvals.canDecide(eq(viewer), isFor(own))).thenReturn(false);

            AgentPresence a = of(service(store).presence(ORG, viewer), ada);

            assertThat(a.state()).isEqualTo("waiting_approval");
            assertThat(a.waitingForYou()).isFalse();
            assertThat(a.waitingOn()).isNull();
        }

        // @find: test waiting for an answer, team room presence
        @Test
        @DisplayName("work parked on a question is waiting for an answer, with the question's first line")
        void waitingForAnAnswer() {
            Agent ada = agent("Ada", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada));
            UUID runId = UUID.randomUUID();
            PendingQuestion question =
                    new PendingQuestion(UUID.randomUUID(), "Which day suits the interview?", VIEWER, NOW.minusSeconds(40));
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(run(runId, ada, "waiting_input", false, null, question)));
            when(questions.canAnswer(
                            argThat(check -> check != null
                                    && question.id().equals(check.getId())
                                    && VIEWER.equals(check.getRequestedBy())),
                            eq(viewer)))
                    .thenReturn(true);

            AgentPresence a = of(service(store).presence(ORG, viewer), ada);

            assertThat(a.state()).isEqualTo("waiting_input");
            assertThat(a.questionId()).isEqualTo(question.id());
            assertThat(a.waitingText()).isEqualTo("Which day suits the interview?");
            assertThat(a.waitingForYou()).isTrue();
            assertThat(a.waitingOn()).isEqualTo(VIEWER);
            assertThat(a.since()).isEqualTo(question.createdAt());
            verify(questions, never()).pending(any(), anyInt());
        }

        // @find: test private work is anonymised, team room presence
        @Test
        @DisplayName("work from someone else's private conversation is only private work: no run, title, steps or approval")
        void privateWorkIsAnonymised() {
            Agent ada = agent("Ada", "active");
            Agent ben = agent("Ben", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada, ben));
            UUID hiddenRunning = UUID.randomUUID();
            UUID hiddenWaiting = UUID.randomUUID();
            PendingApproval approval = approval("Email the salary figures", REQUESTER);
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(
                            run(hiddenRunning, ada, "running", true),
                            run(hiddenWaiting, ben, "waiting_approval", true, approval, null)));
            when(approvals.canDecide(any(), any())).thenReturn(true);

            TeamRoom room = service(store).presence(ORG, viewer);

            for (AgentPresence presence : room.agents()) {
                assertThat(presence.state()).isEqualTo("private");
                assertThat(presence.runId()).isNull();
                assertThat(presence.goalId()).isNull();
                assertThat(presence.goalTitle()).isNull();
                assertThat(presence.taskTitle()).isNull();
                assertThat(presence.approvalId()).isNull();
                assertThat(presence.waitingText()).isNull();
                assertThat(presence.waitingOn()).isNull();
                assertThat(presence.waitingForYou()).isFalse();
                assertThat(presence.activeRuns()).isEqualTo(1);
            }
            // The hidden run's steps are never read, and the queued count leaves the same work out.
            verify(store).stepsSinceLastModelCall(List.of());
            verify(store).queuedByAgent(ORG, AS_VIEWER);
            verify(approvals, never()).canDecide(any(), any());
        }

        // @find: test a holder of chat:read_all sees every conversation's work, team room presence
        @Test
        @DisplayName("a holder of chat:read_all is read as seeing every conversation; anyone else by their own id")
        void whoTheStoreIsToldAbout() {
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
            service(store).presence(ORG, viewer);
            verify(store).activeRuns(ORG, AS_VIEWER);

            Actor owner = Actor.user("owner-1", ORG.toString(), "owner", Set.of("run:read", "chat:read_all"), 0L);
            when(access.canReadAll(owner)).thenReturn(true);
            service(store).presence(ORG, owner);
            verify(store).activeRuns(ORG, Viewer.seeingAll());
            verify(store).queuedByAgent(ORG, Viewer.seeingAll());
            // The rule lives in the store's SQL: no list of hidden conversations is ever loaded.
            verify(access, never()).hiddenConversationIds(any(), any());
        }

        // @find: test the run that needs a person wins, team room presence
        @Test
        @DisplayName("an agent with several runs shows the one waiting on you, and counts them all")
        void theRunThatNeedsAPersonWins() {
            Agent ada = agent("Ada", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada));
            UUID busy = UUID.randomUUID();
            UUID waiting = UUID.randomUUID();
            PendingApproval approval = approval("Refund order 1001", REQUESTER);
            when(store.activeRuns(ORG, AS_VIEWER))
                    .thenReturn(List.of(
                            run(busy, ada, "running", false), run(waiting, ada, "waiting_approval", false, approval, null)));
            when(approvals.canDecide(eq(viewer), isFor(approval))).thenReturn(true);

            AgentPresence a = of(service(store).presence(ORG, viewer), ada);

            assertThat(a.state()).isEqualTo("waiting_approval");
            assertThat(a.runId()).isEqualTo(waiting);
            assertThat(a.activeRuns()).isEqualTo(2);
        }

        // @find: test a fixed number of reads, team room presence
        @Test
        @DisplayName("the presence costs the same few reads however many agents and runs there are")
        void aFixedNumberOfReads() {
            List<Agent> roster = new ArrayList<>();
            List<ActiveRun> active = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                Agent agent = agent("Agent " + index, "active");
                roster.add(agent);
                active.add(run(UUID.randomUUID(), agent, "running", false));
            }
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(roster);
            when(store.activeRuns(ORG, AS_VIEWER)).thenReturn(active);

            TeamRoom room = service(store).presence(ORG, viewer);

            assertThat(room.agents()).hasSize(12).allSatisfy(presence -> assertThat(presence.state())
                    .isEqualTo("thinking"));
            verify(agents, times(1)).findByOrgIdOrderByName(ORG);
            verify(store, times(1)).activeRuns(eq(ORG), any());
            verify(store, times(1)).stepsSinceLastModelCall(anyCollection());
            verify(store, times(1)).lastPages(anyCollection());
            verify(store, times(1)).queuedByAgent(eq(ORG), any());
            verify(access, never()).hiddenConversationIds(any(), any());
            verify(approvals, never()).pending(any());
            verify(questions, never()).pending(any(), anyInt());
        }

        // @find: test the last page is read only for browsing without a site, team room presence
        @Test
        @DisplayName("the last page is looked up only for a browsing run whose call names no site")
        void lastPageOnlyWhenNeeded() {
            Agent ada = agent("Ada", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(ada));
            UUID clicking = UUID.randomUUID();
            when(store.activeRuns(ORG, AS_VIEWER)).thenReturn(List.of(run(clicking, ada, "running", false)));
            when(store.stepsSinceLastModelCall(anyCollection()))
                    .thenReturn(Map.of(
                            clicking, List.of(modelCall(clicking, new CallRecord("c1", "browser.click_button", "{}")))));
            when(store.lastPages(List.of(clicking))).thenReturn(Map.of(clicking, "https://shop.example.com/cart"));

            AgentPresence a = of(service(store).presence(ORG, viewer), ada);

            assertThat(a.state()).isEqualTo("browsing");
            assertThat(a.site()).isEqualTo("shop.example.com");
        }
    }

    // ---- The conversation ----------------------------------------------------------------------

    /**
     * The feed query's contract, kept in memory: the events on one side of the cursor, at most
     * {@code limit} of each kind, nearest the cursor first, with work from the conversations in
     * {@code hiddenFromPeople} left out for any viewer but one who sees all. The SQL itself, and
     * its private rule, are checked against Postgres in TeamRoomStorePersistenceTest.
     */
    static final class FakeStore extends TeamRoomStore {
        final List<FeedRow> rows = new ArrayList<>();
        final Map<String, UUID> conversationOf = new HashMap<>();
        final Set<UUID> hiddenFromPeople = new HashSet<>();
        final List<Viewer> viewersSeen = new ArrayList<>();
        final Map<UUID, PendingApproval> pending = new HashMap<>();
        final List<Collection<UUID>> pendingAsked = new ArrayList<>();

        FakeStore() {
            super(null, new ObjectMapper());
        }

        FeedRow add(FeedRow row, UUID conversationId) {
            rows.add(row);
            if (conversationId != null) {
                conversationOf.put(row.eventId(), conversationId);
            }
            return row;
        }

        @Override
        public List<FeedRow> feedRows(UUID orgId, Viewer viewer, FeedCursor cursor, boolean newer, int limit) {
            viewersSeen.add(viewer);
            Comparator<FeedRow> order = Comparator.comparing(FeedRow::cursor);
            Map<String, List<FeedRow>> byKind = rows.stream()
                    .filter(row -> {
                        UUID conversation = conversationOf.get(row.eventId());
                        return viewer.seesAll() || conversation == null || !hiddenFromPeople.contains(conversation);
                    })
                    .filter(row -> cursor == null
                            || (newer ? row.cursor().compareTo(cursor) > 0 : row.cursor().compareTo(cursor) < 0))
                    .sorted(newer ? order : order.reversed())
                    .collect(Collectors.groupingBy(FeedRow::kind));
            List<FeedRow> out = new ArrayList<>();
            for (List<FeedRow> kind : byKind.values()) {
                out.addAll(kind.subList(0, Math.min(limit, kind.size())));
            }
            // Deliberately unordered across kinds, as UNION ALL is.
            java.util.Collections.shuffle(out, new java.util.Random(7));
            return out;
        }

        @Override
        public Map<UUID, PendingApproval> pendingApprovals(UUID orgId, Collection<UUID> ids) {
            pendingAsked.add(List.copyOf(ids));
            Map<UUID, PendingApproval> found = new HashMap<>();
            for (UUID id : ids) {
                if (pending.containsKey(id)) {
                    found.put(id, pending.get(id));
                }
            }
            return found;
        }
    }

    private static FeedRow started(int index, Instant at) {
        return new FeedRow(
                "task_started",
                at,
                "t:%08d:1:s".formatted(index),
                null,
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                "Task " + index,
                null,
                "running",
                "chat",
                null);
    }

    private static FeedRow asked(int index, Instant at) {
        return new FeedRow(
                "goal_requested", at, "g:%08d".formatted(index), UUID.randomUUID(), null, null, null, null,
                REQUESTER, "Goal " + index, null, "running", "chat", null);
    }

    /** 120 events an hour ago, two kinds, many sharing a moment, so the id has to break ties. */
    private static List<FeedRow> history(FakeStore store) {
        List<FeedRow> all = new ArrayList<>();
        Instant base = NOW.minusSeconds(3600);
        for (int index = 0; index < 60; index++) {
            Instant at = base.plusSeconds(index / 3);
            all.add(store.add(started(index, at), null));
            all.add(store.add(asked(index, at), null));
        }
        all.sort(Comparator.comparing(FeedRow::cursor));
        return all;
    }

    @Nested
    @DisplayName("the conversation")
    class Conversation {

        private FakeStore store;

        @BeforeEach
        void stubs() {
            store = new FakeStore();
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
        }

        // @find: test newest page oldest first, team room feed
        @Test
        @DisplayName("by default the newest page comes back oldest first, with a way to the page before it")
        void newestPageOldestFirst() {
            List<FeedRow> all = history(store);

            FeedPage page = service(store).feed(ORG, viewer, null, null, 50);

            List<String> expected = all.subList(70, 120).stream().map(FeedRow::eventId).toList();
            assertThat(page.events()).extracting(FeedEvent::id).containsExactlyElementsOf(expected);
            assertThat(page.hasOlder()).isTrue();
            assertThat(page.hasNewer()).isFalse();
            assertThat(FeedCursor.decode(page.olderCursor(), "before")).isEqualTo(all.get(70).cursor());
            // Each event carries its own position too, for a client that trims the oldest it holds.
            assertThat(FeedCursor.decode(page.events().get(1).cursor(), "before")).isEqualTo(all.get(71).cursor());
            // An hour old, so already settled: the next poll starts right after the newest event.
            assertThat(FeedCursor.Poll.decode(page.newestCursor(), "after")).isEqualTo(FeedCursor.Poll.at(all.get(119).cursor()));
        }

        // @find: test paging back gives every event once, team room feed
        @Test
        @DisplayName("paging back through the history gives every event exactly once, ties included")
        void pagingBackGivesEveryEventOnce() {
            List<FeedRow> all = history(store);
            TeamRoomService service = service(store);

            List<String> seen = new ArrayList<>();
            FeedPage page = service.feed(ORG, viewer, null, null, 17);
            seen.addAll(0, page.events().stream().map(FeedEvent::id).toList());
            int pages = 1;
            while (page.hasOlder()) {
                page = service.feed(ORG, viewer, null, FeedCursor.decode(page.olderCursor(), "before"), 17);
                assertThat(page.newestCursor()).isNull();
                seen.addAll(0, page.events().stream().map(FeedEvent::id).toList());
                pages++;
            }

            assertThat(seen).containsExactlyElementsOf(all.stream().map(FeedRow::eventId).toList());
            assertThat(pages).isEqualTo(8);
        }

        // @find: test after returns what is new and re-reads the settle window, team room feed
        @Test
        @DisplayName("a poll returns what happened since, oldest first, and its next cursor re-reads the last few seconds")
        void afterReturnsWhatIsNew() {
            FeedRow old = store.add(started(1, NOW.minusSeconds(600)), null);
            FeedRow recent = store.add(started(2, NOW.minusSeconds(8)), null);
            FeedRow newest = store.add(asked(3, NOW.minusSeconds(2)), null);

            FeedPage page = service(store).feed(ORG, viewer, FeedCursor.Poll.at(old.cursor()), null, 50);

            assertThat(page.events()).extracting(FeedEvent::id).containsExactly(recent.eventId(), newest.eventId());
            assertThat(page.hasNewer()).isFalse();
            assertThat(page.olderCursor()).isNull();
            assertThat(FeedCursor.Poll.decode(page.newestCursor(), "after"))
                    .isEqualTo(FeedCursor.Poll.at(FeedCursor.before(NOW.minus(TeamRoomService.SETTLE))));
        }

        // @find: test a poll never moves backwards, team room feed
        @Test
        @DisplayName("a poll whose cursor is already inside the settle window never moves it backwards")
        void aPollNeverMovesBackwards() {
            FeedRow recent = store.add(started(1, NOW.minusSeconds(4)), null);

            FeedPage page = service(store).feed(ORG, viewer, FeedCursor.Poll.at(recent.cursor()), null, 50);

            assertThat(page.events()).isEmpty();
            assertThat(FeedCursor.Poll.decode(page.newestCursor(), "after")).isEqualTo(FeedCursor.Poll.at(recent.cursor()));
        }

        // @find: test a backlog is read page by page, team room feed
        @Test
        @DisplayName("more than a page since the last poll is read page by page, each starting after the last shown")
        void aBacklogIsReadPageByPage() {
            List<FeedRow> all = history(store);

            FeedPage first = service(store).feed(ORG, viewer, FeedCursor.Poll.at(all.get(9).cursor()), null, 50);

            assertThat(first.hasNewer()).isTrue();
            assertThat(first.events()).extracting(FeedEvent::id)
                    .containsExactlyElementsOf(all.subList(10, 60).stream().map(FeedRow::eventId).toList());
            // An hour old, so already settled: no floor to read again from.
            assertThat(FeedCursor.Poll.decode(first.newestCursor(), "after")).isEqualTo(FeedCursor.Poll.at(all.get(59).cursor()));

            FeedPage second = service(store)
                    .feed(ORG, viewer, FeedCursor.Poll.decode(first.newestCursor(), "after"), null, 100);
            assertThat(second.hasNewer()).isFalse();
            assertThat(second.events()).extracting(FeedEvent::id)
                    .containsExactlyElementsOf(all.subList(60, 120).stream().map(FeedRow::eventId).toList());
        }

        // @find: test a late commit inside a backlog still arrives, team room feed
        @Test
        @DisplayName("an event committed late inside a recent backlog still arrives once the backlog is drained")
        void aLateCommitInsideABacklogStillArrives() {
            FeedRow start = store.add(started(0, NOW.minusSeconds(40)), null);
            List<FeedRow> burst = new ArrayList<>();
            for (int index = 1; index <= 60; index++) {
                // Sixty events in the last fifteen seconds: more than a page, all inside the settle window.
                burst.add(store.add(asked(index, NOW.minusSeconds(15).plusMillis(index * 150L)), null));
            }
            burst.sort(Comparator.comparing(FeedRow::cursor));
            TeamRoomService service = service(store);

            FeedPage first = service.feed(ORG, viewer, FeedCursor.Poll.at(start.cursor()), null, 50);
            assertThat(first.hasNewer()).isTrue();
            FeedCursor.Poll afterFirst = FeedCursor.Poll.decode(first.newestCursor(), "after");
            assertThat(afterFirst.progress()).isEqualTo(burst.get(49).cursor());
            // Everything up to the settle line was read settled; the window after it is still owed a re-read.
            assertThat(afterFirst.floor()).isEqualTo(FeedCursor.before(NOW.minus(TeamRoomService.SETTLE)));

            // A task finishes with a moment before the last event shown, and commits only now.
            FeedRow late = store.add(started(99, burst.get(20).at().plusMillis(1)), null);

            FeedPage second = service.feed(ORG, viewer, afterFirst, null, 50);
            assertThat(second.hasNewer()).isFalse();
            assertThat(second.events()).extracting(FeedEvent::id).doesNotContain(late.eventId()).hasSize(10);
            FeedCursor.Poll afterSecond = FeedCursor.Poll.decode(second.newestCursor(), "after");
            // Drained: the next poll starts again from the floor, with nothing more owed.
            assertThat(afterSecond).isEqualTo(FeedCursor.Poll.at(afterFirst.floor()));

            FeedPage third = service.feed(ORG, viewer, afterSecond, null, 100);
            assertThat(third.events()).extracting(FeedEvent::id).contains(late.eventId());
        }

        // @find: test the next poll position, team room feed
        @Test
        @DisplayName("the next poll position: progress through a backlog, read the floor again once, else settle forwards")
        void theNextPollPosition() {
            FeedCursor settle = FeedCursor.before(NOW.minus(TeamRoomService.SETTLE));
            FeedCursor old = new FeedCursor(NOW.minusSeconds(600), "g:1");
            FeedCursor recent = new FeedCursor(NOW.minusSeconds(5), "g:2");
            FeedCursor newest = new FeedCursor(NOW.minusSeconds(1), "g:3");

            // A backlog page ending inside the window: the floor is the settle line.
            assertThat(TeamRoomService.nextPoll(FeedCursor.Poll.at(old), recent, settle, true))
                    .isEqualTo(new FeedCursor.Poll(recent, settle));
            // A later backlog page keeps the floor it was given.
            assertThat(TeamRoomService.nextPoll(new FeedCursor.Poll(recent, settle), newest, settle, true))
                    .isEqualTo(new FeedCursor.Poll(newest, settle));
            // Drained with a floor: read again from the floor.
            assertThat(TeamRoomService.nextPoll(new FeedCursor.Poll(recent, settle), newest, settle, false))
                    .isEqualTo(FeedCursor.Poll.at(settle));
            // Drained without one: the settle line, never behind where the poll started.
            assertThat(TeamRoomService.nextPoll(FeedCursor.Poll.at(old), newest, settle, false))
                    .isEqualTo(FeedCursor.Poll.at(settle));
            assertThat(TeamRoomService.nextPoll(FeedCursor.Poll.at(recent), null, settle, false))
                    .isEqualTo(FeedCursor.Poll.at(recent));
        }

        // @find: test private work is left out of the conversation, team room feed
        @Test
        @DisplayName("events from someone else's private conversation never appear, and the store is told whose eyes they are for")
        void privateWorkIsLeftOut() {
            UUID secret = UUID.randomUUID();
            store.hiddenFromPeople.add(secret);
            FeedRow open = store.add(asked(1, NOW.minusSeconds(100)), UUID.randomUUID());
            FeedRow hidden = store.add(asked(2, NOW.minusSeconds(90)), secret);
            FeedRow direct = store.add(started(3, NOW.minusSeconds(80)), null);

            FeedPage page = service(store).feed(ORG, viewer, null, null, 50);

            assertThat(page.events()).extracting(FeedEvent::id).containsExactly(open.eventId(), direct.eventId());
            assertThat(store.viewersSeen).containsExactly(AS_VIEWER);

            Actor owner = Actor.user("owner-1", ORG.toString(), "owner", Set.of("run:read", "chat:read_all"), 0L);
            when(access.canReadAll(owner)).thenReturn(true);
            FeedPage all = service(store).feed(ORG, owner, null, null, 50);
            assertThat(all.events()).extracting(FeedEvent::id).contains(hidden.eventId());
            assertThat(store.viewersSeen.getLast()).isEqualTo(Viewer.seeingAll());
            verify(access, never()).hiddenConversationIds(any(), any());
        }

        // @find: test an empty room, team room feed
        @Test
        @DisplayName("an empty conversation still hands back a cursor to poll from")
        void anEmptyRoom() {
            FeedPage page = service(store).feed(ORG, viewer, null, null, 50);

            assertThat(page.events()).isEmpty();
            assertThat(page.hasOlder()).isFalse();
            assertThat(page.olderCursor()).isNull();
            assertThat(FeedCursor.Poll.decode(page.newestCursor(), "after"))
                    .isEqualTo(FeedCursor.Poll.at(FeedCursor.before(NOW.minus(TeamRoomService.SETTLE))));
            verify(agents, never()).findByOrgIdOrderByName(any());
        }
    }

    // ---- Events in words -------------------------------------------------------------------------

    @Nested
    @DisplayName("events in words")
    class Words {

        private FakeStore store;
        private Agent hr;
        private Agent writer;

        @BeforeEach
        void stubs() {
            store = new FakeStore();
            hr = agent("HR", "active");
            writer = agent("Writer", "active");
            when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(hr, writer));
        }

        private FeedRow row(String kind, int second, UUID goalId, UUID taskId, UUID agentId, String title, String body,
                String status, String source, String label) {
            return store.add(
                    new FeedRow(kind, NOW.minusSeconds(600 - second), kind + ":" + second, goalId, taskId,
                            UUID.randomUUID(), UUID.randomUUID(), agentId, REQUESTER, title, body, status, source, label),
                    null);
        }

        private Task task(UUID goalId, String title, Agent agent, int position, List<UUID> dependsOn) {
            Task task = new Task();
            task.setOrgId(ORG);
            task.setGoalId(goalId);
            task.setAgentId(agent == null ? null : agent.getId());
            task.setTitle(title);
            task.setPosition(position);
            task.setDependsOn(dependsOn);
            return task;
        }

        private Map<String, FeedEvent> byId(FeedPage page) {
            return page.events().stream().collect(Collectors.toMap(FeedEvent::id, event -> event));
        }

        // @find: test goals, schedules and hand-offs in words, team room feed
        @Test
        @DisplayName("a request, a scheduled start, a hand-off, a last finish and a failure each read as themselves")
        void goalsSchedulesAndHandOffs() {
            UUID goal = UUID.randomUUID();
            Task screen = task(goal, "Screen the applications", hr, 0, List.of());
            Task write = task(goal, "Write the shortlist note", writer, 1, List.of(screen.getId()));
            when(tasks.findByGoalIdInOrderByPositionAsc(any())).thenReturn(List.of(screen, write));

            row("goal_requested", 1, goal, null, hr.getId(), "Hire a designer", null, "running", "chat", null);
            row("goal_requested", 2, UUID.randomUUID(), null, hr.getId(), "Monday digest", null, "running", "schedule",
                    "Monday morning digest");
            row("task_finished", 3, goal, screen.getId(), hr.getId(), "Screen the applications",
                    "## Shortlist\n\nThree strong candidates.", "completed", "chat", null);
            row("task_finished", 4, goal, write.getId(), writer.getId(), "Write the shortlist note",
                    "\n- **Sent** the note to the hiring manager.\nMore detail.", "completed", "chat", null);
            row("task_finished", 5, goal, UUID.randomUUID(), writer.getId(), "Book the interviews",
                    "The calendar connector refused the request.", "failed", "chat", null);

            Map<String, FeedEvent> events = byId(service(store).feed(ORG, viewer, null, null, 50));

            FeedEvent asked = events.get("goal_requested:1");
            assertThat(asked.kind()).isEqualTo("goal_requested");
            assertThat(asked.title()).isEqualTo("Hire a designer");
            assertThat(asked.agentName()).isEqualTo("HR");
            assertThat(asked.personId()).isEqualTo(REQUESTER);

            FeedEvent scheduled = events.get("goal_requested:2");
            assertThat(scheduled.kind()).isEqualTo("schedule_started");
            assertThat(scheduled.scheduleName()).isEqualTo("Monday morning digest");

            FeedEvent handed = events.get("task_finished:3");
            assertThat(handed.kind()).isEqualTo("task_handed_off");
            assertThat(handed.nextAgentId()).isEqualTo(writer.getId());
            assertThat(handed.nextAgentName()).isEqualTo("Writer");
            assertThat(handed.nextTaskTitle()).isEqualTo("Write the shortlist note");
            assertThat(handed.text()).isEqualTo("Shortlist");

            FeedEvent last = events.get("task_finished:4");
            assertThat(last.kind()).isEqualTo("task_finished");
            assertThat(last.text()).isEqualTo("Sent the note to the hiring manager.");
            assertThat(last.nextAgentId()).isNull();

            FeedEvent failed = events.get("task_finished:5");
            assertThat(failed.kind()).isEqualTo("task_failed");
            assertThat(failed.text()).isEqualTo("The calendar connector refused the request.");

            // One read of the finished tasks' goals, for the whole page.
            verify(tasks, times(1)).findByGoalIdInOrderByPositionAsc(any());
        }

        // @find: test approvals and questions in words, team room feed
        @Test
        @DisplayName("approvals read as asked for, approved, rejected, sent back or expired; questions as asked and answered")
        void approvalsAndQuestions() {
            FeedRow requested = row("approval_requested", 1, null, null, hr.getId(), "Send the offer letter", "gmail.send_message",
                    "pending", null, null);
            row("approval_decided", 2, null, null, hr.getId(), "Send the offer letter", null, "approved", null, null);
            row("approval_decided", 3, null, null, hr.getId(), "Post in Slack", null, "rejected", null, null);
            row("approval_decided", 4, null, null, hr.getId(), "Refund the order", null, "sent_back", null, null);
            row("approval_decided", 5, null, null, hr.getId(), "Delete the page", null, "expired", null, null);
            FeedRow question = row("question_asked", 6, null, null, writer.getId(), "Which tone should the note take?", null,
                    "pending", null, null);
            row("question_answered", 7, null, null, writer.getId(), "Which tone should the note take?", null, "answered", null,
                    null);

            Map<String, FeedEvent> events = byId(service(store).feed(ORG, viewer, null, null, 50));

            FeedEvent asked = events.get("approval_requested:1");
            assertThat(asked.kind()).isEqualTo("approval_requested");
            assertThat(asked.text()).isEqualTo("Send the offer letter");
            assertThat(asked.approvalId()).isEqualTo(requested.itemId());
            assertThat(asked.questionId()).isNull();
            assertThat(asked.runId()).isEqualTo(requested.runId());
            assertThat(events.get("approval_decided:2").kind()).isEqualTo("approval_approved");
            assertThat(events.get("approval_decided:3").kind()).isEqualTo("approval_rejected");
            assertThat(events.get("approval_decided:4").kind()).isEqualTo("approval_sent_back");
            assertThat(events.get("approval_decided:5").kind()).isEqualTo("approval_expired");

            FeedEvent q = events.get("question_asked:6");
            assertThat(q.kind()).isEqualTo("question_asked");
            assertThat(q.text()).isEqualTo("Which tone should the note take?");
            assertThat(q.questionId()).isEqualTo(question.itemId());
            assertThat(q.approvalId()).isNull();
            assertThat(events.get("question_answered:7").kind()).isEqualTo("question_answered");
            verify(tasks, never()).findByGoalIdInOrderByPositionAsc(any());
            // Only the still-pending approval is looked up, to say whether it waits on the viewer.
            assertThat(store.pendingAsked).containsExactly(List.of(requested.itemId()));
            assertThat(asked.forYou()).isFalse();
        }

        // @find: test an approval waits on you only when you may decide it, team room feed
        @Test
        @DisplayName("an asked-for approval is for you only while it waits and you may decide it; its requester is never the decider")
        void anApprovalIsForYouOnlyWhenYouMayDecide() {
            FeedRow mine = row("approval_requested", 1, null, null, hr.getId(), "Send the offer letter", null,
                    "pending", null, null);
            FeedRow fourEyes = row("approval_requested", 2, null, null, hr.getId(), "Refund the order", null,
                    "pending", null, null);
            FeedRow decided = row("approval_requested", 3, null, null, hr.getId(), "Post in Slack", null,
                    "approved", null, null);
            PendingApproval mineNow = new PendingApproval(
                    mine.itemId(), "Send the offer letter", REQUESTER, NOW.minusSeconds(300), "OUTBOUND", "approval:decide");
            PendingApproval ownRequest = new PendingApproval(
                    fourEyes.itemId(), "Refund the order", VIEWER, NOW.minusSeconds(200), "DESTRUCTIVE", "approval:decide");
            store.pending.put(mine.itemId(), mineNow);
            store.pending.put(fourEyes.itemId(), ownRequest);
            when(approvals.canDecide(eq(viewer), isFor(mineNow))).thenReturn(true);
            when(approvals.canDecide(eq(viewer), isFor(ownRequest))).thenReturn(false);

            Map<String, FeedEvent> events = byId(service(store).feed(ORG, viewer, null, null, 50));

            assertThat(events.get("approval_requested:1").forYou()).isTrue();
            assertThat(events.get("approval_requested:2").forYou()).isFalse();
            assertThat(events.get("approval_requested:3").forYou()).isFalse();
            // One lookup for the page, of the pending ones only.
            assertThat(store.pendingAsked).hasSize(1);
            assertThat(store.pendingAsked.getFirst()).containsExactlyInAnyOrder(mine.itemId(), fourEyes.itemId());
        }

        // @find: test runs started straight on an agent in words, team room feed
        @Test
        @DisplayName("work started straight on an agent reads as asked, then finished with its answer or failed with why")
        void directRuns() {
            FeedRow asked = row("run_requested", 1, null, null, hr.getId(),
                    "Find the three newest applications.\nThen sum them up.", null, "running", "manual", null);
            row("run_finished", 2, null, null, hr.getId(), "Find the three newest applications.",
                    "## Three found\nDetail follows.", "completed", "manual", null);
            row("run_finished", 3, null, null, writer.getId(), "Draft the note", "The model refused the request.",
                    "failed", "manual", null);
            row("run_finished", 4, null, null, writer.getId(), "Draft the memo",
                    "A person stopped this run before it finished.", "cancelled", "manual", null);

            Map<String, FeedEvent> events = byId(service(store).feed(ORG, viewer, null, null, 50));

            FeedEvent requested = events.get("run_requested:1");
            assertThat(requested.kind()).isEqualTo("run_requested");
            assertThat(requested.title()).isEqualTo("Find the three newest applications.");
            assertThat(requested.personId()).isEqualTo(REQUESTER);
            assertThat(requested.runId()).isEqualTo(asked.runId());
            assertThat(requested.agentName()).isEqualTo("HR");
            FeedEvent finished = events.get("run_finished:2");
            assertThat(finished.kind()).isEqualTo("run_finished");
            assertThat(finished.text()).isEqualTo("Three found");
            FeedEvent failed = events.get("run_finished:3");
            assertThat(failed.kind()).isEqualTo("run_failed");
            assertThat(failed.title()).isEqualTo("Draft the note");
            assertThat(failed.text()).isEqualTo("The model refused the request.");
            assertThat(events.get("run_finished:4").kind()).isEqualTo("run_failed");
            verify(tasks, never()).findByGoalIdInOrderByPositionAsc(any());
        }

        // @find: test who a finished task hands to, team room feed
        @Test
        @DisplayName("a finished task hands to the task that names it, else to the next position, else to nobody")
        void whoAFinishedTaskHandsTo() {
            UUID goal = UUID.randomUUID();
            Task first = task(goal, "First", hr, 0, List.of());
            Task second = task(goal, "Second", writer, 1, List.of());
            Task third = task(goal, "Third", hr, 2, List.of());
            Task namesFirst = task(goal, "Names first", writer, 5, List.of(first.getId()));
            List<Task> siblings = List.of(first, second, third, namesFirst);

            assertThat(TeamRoomService.nextTask(first.getId(), siblings)).isSameAs(namesFirst);
            assertThat(TeamRoomService.nextTask(second.getId(), siblings)).isSameAs(third);
            assertThat(TeamRoomService.nextTask(namesFirst.getId(), siblings)).isNull();
            assertThat(TeamRoomService.nextTask(UUID.randomUUID(), siblings)).isNull();
        }

        // @find: test one line of text, team room feed
        @Test
        @DisplayName("a result becomes its first line with words, without Markdown marks, cut with an ellipsis")
        void oneLine() {
            assertThat(TeamRoomService.oneLine("\n\n### The answer\nmore")).isEqualTo("The answer");
            assertThat(TeamRoomService.oneLine("1. **Booked** Tuesday")).isEqualTo("Booked Tuesday");
            assertThat(TeamRoomService.oneLine("> quoted")).isEqualTo("quoted");
            assertThat(TeamRoomService.oneLine("  ")).isNull();
            assertThat(TeamRoomService.oneLine(null)).isNull();
            String longLine = "word ".repeat(80);
            String cut = TeamRoomService.oneLine(longLine);
            assertThat(cut).hasSizeLessThanOrEqualTo(160).endsWith("…");
        }
    }
}
