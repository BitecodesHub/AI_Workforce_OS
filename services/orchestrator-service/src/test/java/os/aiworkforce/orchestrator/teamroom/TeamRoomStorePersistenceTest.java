// @find: tests for team room store persistence, V28 indexes, active runs query, pending approval and question of a parked run, steps since last model call, last page, queued by agent, feed rows union, direct runs in the feed, cursor sql, private conversation left out of every kind, thousands of private conversations, TeamRoomStorePersistenceTest, TeamRoomStore
// @what: Runs the team room's SQL against the real schema (Flyway V1 to V28 on Postgres): presence reads, the feed's union and cursor, and the private-work rule for every kind of event, however many private conversations exist.
// @flow: Exercises TeamRoomStore against the database DatabaseForTests provides; opt-in with AIWOS_DATABASE_TESTS=true
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.StepFact;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.ActiveRun;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.FeedRow;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.PendingApproval;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.Viewer;

/**
 * The team room's SQL against the real schema. The service tests use an in-memory store, so they
 * cannot see SQL Postgres will not run, a union whose column types do not line up, a cursor
 * comparison that orders ids differently from Java, or a private-conversation rule left off one
 * kind of event. Opt-in, because it needs a database: run with {@code AIWOS_DATABASE_TESTS=true}
 * (see {@link DatabaseForTests}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class TeamRoomStorePersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID PERSON = UUID.fromString("00000000-0000-7000-8000-0000000000c3");
    /** Someone who is neither the creator of nor a participant in the private conversation. */
    private static final Viewer OUTSIDER = Viewer.person("00000000-0000-7000-8000-0000000000d4");
    /** The person who started the private conversation. */
    private static final Viewer CREATOR = Viewer.person("00000000-0000-7000-8000-0000000000e5");
    /** A person the creator added to it. */
    private static final Viewer ADDED = Viewer.person("00000000-0000-7000-8000-0000000000f6");

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
    private JdbcTemplate jdbc;

    private TeamRoomStore store;

    @BeforeEach
    void setUp() {
        store = new TeamRoomStore(new NamedParameterJdbcTemplate(jdbc), new ObjectMapper());
    }

    private static Instant ago(long seconds) {
        return Instant.now().minusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private UUID agent(UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into agents (id, org_id, key, name) values (?, ?, ?, ?)", id, org, name + id, name);
        return id;
    }

    /** A private conversation started by {@link #CREATOR}, with {@link #ADDED} added to it. */
    private UUID privateConversation(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into conversations (id, org_id, title, visibility, created_by) values (?, ?, 'Private', 'private', ?)",
                id, org, CREATOR.personId());
        jdbc.update(
                "insert into conversation_participants (conversation_id, user_id, added_by) values (?, ?, ?)",
                id, ADDED.personId(), CREATOR.personId());
        return id;
    }

    private UUID workspaceConversation(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into conversations (id, org_id, title, visibility, created_by) values (?, ?, 'Open', 'workspace', ?)",
                id, org, CREATOR.personId());
        return id;
    }

    private UUID goal(UUID org, String title, String source, UUID conversationId, UUID scheduleId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into goals (id, org_id, title, status, source, conversation_id, schedule_id, requested_by,
                                   created_at, updated_at)
                values (?, ?, ?, 'running', ?, ?, ?, ?, ?, ?)
                """,
                id, org, title, source, conversationId, scheduleId, PERSON, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    private UUID task(UUID org, UUID goalId, UUID agentId, String title, String status, int position,
            Instant startedAt, Instant completedAt, String result) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into tasks (id, org_id, goal_id, agent_id, title, instruction, status, position, attempt,
                                   started_at, completed_at, result)
                values (?, ?, ?, ?, ?, 'Do it', ?, ?, 1, ?, ?, ?)
                """,
                id, org, goalId, agentId, title, status, position,
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                result);
        return id;
    }

    private UUID run(UUID org, UUID taskId, UUID agentId, String status, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into runs (id, org_id, task_id, agent_id, agent_version_id, status, started_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                id, org, taskId, agentId, UUID.randomUUID(), status, Timestamp.from(startedAt));
        return id;
    }

    /** A run started straight on an agent by {@link #PERSON}, as AgentController.run saves one. */
    private UUID directRun(UUID agentId, String status, Instant startedAt, Instant completedAt, String failure) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into runs (id, org_id, task_id, agent_id, agent_version_id, status, trigger, started_at,
                                  completed_at, failure_reason, created_by)
                values (?, ?, null, ?, ?, ?, 'manual', ?, ?, ?, ?)
                """,
                id, ORG, agentId, UUID.randomUUID(), status, Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt), failure, PERSON.toString());
        return id;
    }

    private void step(UUID runId, int position, String kind, String detailJson) {
        jdbc.update(
                """
                insert into run_steps (id, org_id, run_id, position, kind, detail, occurred_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                UUID.randomUUID(), ORG, runId, position, kind, detailJson, Timestamp.from(ago(100 - position)));
    }

    private UUID approval(UUID runId, UUID taskId, UUID agentId, String summary, String status, Instant requestedAt,
            Instant decidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into approvals (id, org_id, run_id, task_id, agent_id, action_class, tool, summary, status,
                                       requested_at, expires_at, decided_at, decided_by, requested_by, payload)
                values (?, ?, ?, ?, ?, 'OUTBOUND', 'gmail.send_message', ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """,
                id, ORG, runId, taskId, agentId, summary, status, Timestamp.from(requestedAt),
                Timestamp.from(ago(-3600)), decidedAt == null ? null : Timestamp.from(decidedAt),
                decidedAt == null ? null : PERSON, PERSON, "{\"body\":\"" + "x".repeat(2000) + "\"}");
        return id;
    }

    private UUID question(UUID runId, UUID taskId, UUID goalId, UUID agentId, String text, boolean answered,
            Instant askedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into run_questions (id, org_id, run_id, task_id, goal_id, agent_id, tool_call_id, questions,
                                           status, answered_by, answered_at, requested_by, expires_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                """,
                id, ORG, runId, taskId, goalId, agentId, "q-" + id,
                "[{\"header\":\"Tone\",\"question\":\"" + text + "\"}]",
                answered ? "answered" : "pending", answered ? PERSON : null,
                answered ? Timestamp.from(askedAt.plusSeconds(10)) : null, PERSON, Timestamp.from(ago(-3600)),
                Timestamp.from(askedAt));
        return id;
    }

    // @find: test migration, team room store persistence
    @Test
    @DisplayName("V28 adds the indexes the team room reads by, and the earlier ones it relies on are there")
    void migration() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'orchestrator'", String.class);

        assertThat(indexes)
                .contains(
                        "goals_org_created_idx",
                        "tasks_org_started_idx",
                        "approvals_org_decided_idx",
                        "run_questions_org_answered_idx",
                        "runs_direct_started_idx",
                        "runs_direct_completed_idx",
                        // From V12 and V3, not repeated by V28.
                        "tasks_org_completed_idx",
                        "approvals_org_requested_idx",
                        "run_questions_org_created_idx",
                        "runs_org_status_started_idx");
    }

    // @find: test active runs, team room store persistence
    @Test
    @DisplayName("active runs come with their task, goal and what they wait on, for one workspace, newest first")
    void activeRuns() {
        UUID ada = agent(ORG, "Ada");
        UUID goalId = goal(ORG, "Weekly report", "chat", workspaceConversation(ORG), null, ago(300));
        UUID taskId = task(ORG, goalId, ada, "Draft it", "running", 0, ago(200), null, null);
        UUID older = run(ORG, taskId, ada, "waiting_approval", ago(200));
        UUID pending = approval(older, taskId, ada, "Send the report", "pending", ago(150), null);
        approval(older, taskId, ada, "An earlier one", "approved", ago(190), ago(185));
        UUID asking = run(ORG, null, ada, "waiting_input", ago(100));
        UUID question = question(asking, null, null, ada, "Which week?", false, ago(90));
        UUID newer = run(ORG, null, ada, "running", ago(10));
        run(ORG, null, ada, "completed", ago(5));
        run(OTHER_ORG, null, agent(OTHER_ORG, "Ben"), "running", ago(1));

        List<ActiveRun> active = store.activeRuns(ORG, OUTSIDER);

        assertThat(active).extracting(ActiveRun::runId).containsExactly(newer, asking, older);
        ActiveRun withTask = active.get(2);
        assertThat(withTask.taskTitle()).isEqualTo("Draft it");
        assertThat(withTask.goalTitle()).isEqualTo("Weekly report");
        assertThat(withTask.requestedBy()).isEqualTo(PERSON);
        assertThat(withTask.hidden()).isFalse();
        PendingApproval waitingOn = withTask.approval();
        assertThat(waitingOn.id()).isEqualTo(pending);
        assertThat(waitingOn.summary()).isEqualTo("Send the report");
        assertThat(waitingOn.requestedBy()).isEqualTo(PERSON);
        assertThat(waitingOn.actionClass()).isEqualTo("OUTBOUND");
        assertThat(waitingOn.requiredPermission()).isEqualTo("approval:decide");
        assertThat(withTask.question()).isNull();
        ActiveRun parkedOnQuestion = active.get(1);
        assertThat(parkedOnQuestion.question().id()).isEqualTo(question);
        assertThat(parkedOnQuestion.question().question()).isEqualTo("Which week?");
        assertThat(parkedOnQuestion.approval()).isNull();
        assertThat(active.get(0).goalId()).isNull();
        assertThat(active.get(0).approval()).isNull();
    }

    // @find: test active runs from a private conversation are hidden from outsiders only, team room store persistence
    @Test
    @DisplayName("a run of private work is hidden from an outsider, and not from its creator, a person they added or a reader of all")
    void activeRunsOfPrivateWork() {
        UUID ada = agent(ORG, "Ada");
        UUID secret = goal(ORG, "Salary review", "chat", privateConversation(ORG), null, ago(300));
        UUID runId = run(ORG, task(ORG, secret, ada, "Compare", "running", 0, ago(200), null, null), ada, "running",
                ago(200));

        assertThat(store.activeRuns(ORG, OUTSIDER)).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(runId);
            assertThat(run.hidden()).isTrue();
        });
        assertThat(store.activeRuns(ORG, CREATOR).getFirst().hidden()).isFalse();
        assertThat(store.activeRuns(ORG, ADDED).getFirst().hidden()).isFalse();
        assertThat(store.activeRuns(ORG, Viewer.seeingAll()).getFirst().hidden()).isFalse();
        // A person with no id matches no creator or participant.
        assertThat(store.activeRuns(ORG, Viewer.person(null)).getFirst().hidden()).isTrue();
    }

    // @find: test steps since the last model call and the last page, team room store persistence
    @Test
    @DisplayName("only the steps from the latest model answer on are read, with their calls parsed, and the last page found")
    void stepsSinceTheLastModelCall() {
        UUID ada = agent(ORG, "Ada");
        UUID runId = run(ORG, null, ada, "running", ago(100));
        step(runId, 0, "note", "{\"type\":\"instruction\",\"content\":\"Find the news\"}");
        step(runId, 1, "model_call", "{\"toolCalls\":[\"browser.open_page\"],\"toolCallRecords\":[{\"id\":\"c1\",\"name\":\"browser.open_page\",\"argumentsJson\":\"{\\\"url\\\":\\\"https://bbc.co.uk\\\"}\"}]}");
        step(runId, 2, "tool_call", "{\"toolCallId\":\"c1\",\"tool\":\"browser.open_page\",\"url\":\"https://www.bbc.co.uk/news\"}");
        step(runId, 3, "model_call", "{\"toolCallRecords\":[{\"id\":\"c2\",\"name\":\"browser.click_button\",\"argumentsJson\":\"{}\"}]}");
        UUID fresh = run(ORG, null, ada, "running", ago(50));
        step(fresh, 0, "note", "{\"type\":\"instruction\"}");

        Map<UUID, List<StepFact>> steps = store.stepsSinceLastModelCall(List.of(runId, fresh));

        assertThat(steps.get(runId)).extracting(StepFact::position).containsExactly(3);
        assertThat(steps.get(runId).getFirst().calls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("c2");
            assertThat(call.name()).isEqualTo("browser.click_button");
        });
        assertThat(steps.get(fresh)).extracting(StepFact::kind).containsExactly("note");
        assertThat(store.lastPages(List.of(runId, fresh))).containsExactly(Map.entry(runId, "https://www.bbc.co.uk/news"));
        assertThat(store.stepsSinceLastModelCall(List.of())).isEmpty();
    }

    // @find: test queued by agent leaves private work out, team room store persistence
    @Test
    @DisplayName("queued tasks are counted per agent, leaving out private work from those outside it")
    void queuedByAgent() {
        UUID ada = agent(ORG, "Ada");
        UUID open = goal(ORG, "Open", "chat", null, null, ago(60));
        UUID hidden = goal(ORG, "Hidden", "chat", privateConversation(ORG), null, ago(60));
        task(ORG, open, ada, "One", "pending", 0, null, null, null);
        task(ORG, open, ada, "Two", "ready", 1, null, null, null);
        task(ORG, hidden, ada, "Three", "pending", 0, null, null, null);

        assertThat(store.queuedByAgent(ORG, Viewer.seeingAll())).containsExactly(Map.entry(ada, 3));
        assertThat(store.queuedByAgent(ORG, CREATOR)).containsExactly(Map.entry(ada, 3));
        assertThat(store.queuedByAgent(ORG, OUTSIDER)).containsExactly(Map.entry(ada, 2));
    }

    // @find: test pending approvals by id without payload, team room store persistence
    @Test
    @DisplayName("pending approvals are read by id, decided ones and other workspaces' left out")
    void pendingApprovals() {
        UUID ada = agent(ORG, "Ada");
        UUID runId = run(ORG, null, ada, "waiting_approval", ago(100));
        UUID waiting = approval(runId, null, ada, "Send it", "pending", ago(90), null);
        UUID decided = approval(runId, null, ada, "Sent before", "approved", ago(95), ago(94));

        Map<UUID, PendingApproval> found = store.pendingApprovals(ORG, List.of(waiting, decided, UUID.randomUUID()));

        assertThat(found).containsOnlyKeys(waiting);
        assertThat(found.get(waiting).actionClass()).isEqualTo("OUTBOUND");
        assertThat(found.get(waiting).requestedBy()).isEqualTo(PERSON);
        assertThat(store.pendingApprovals(OTHER_ORG, List.of(waiting))).isEmpty();
        assertThat(store.pendingApprovals(ORG, List.of())).isEmpty();
    }

    // @find: test feed rows, union, cursor and direct runs, team room store persistence
    @Test
    @DisplayName("the feed reads every kind of event, on either side of a cursor, in the order Java sorts them")
    void feedRows() {
        UUID ada = agent(ORG, "Ada");
        UUID schedule = UUID.randomUUID();
        jdbc.update(
                """
                insert into schedules (id, org_id, name, agent_id, instruction, kind, cron, timezone, description)
                values (?, ?, 'Monday digest', ?, 'Sum up', 'recurring', '0 9 * * 1', 'UTC', 'Mondays at 9')
                """,
                schedule, ORG, ada);
        Instant same = ago(500);
        UUID goalId = goal(ORG, "Hire a designer", "chat", null, null, same);
        UUID scheduled = goal(ORG, "Digest", "schedule", null, schedule, same);
        UUID taskId = task(ORG, goalId, ada, "Screen", "completed", 0, ago(400), ago(300), "## Shortlist\nThree.");
        UUID runId = run(ORG, taskId, ada, "completed", ago(400));
        UUID decided = approval(runId, taskId, ada, "Send the note", "rejected", ago(350), ago(340));
        jdbc.update("update approvals set sent_back = true where id = ?", decided);
        question(runId, taskId, goalId, ada, "Formal or friendly?", true, ago(330));
        UUID direct = directRun(ada, "completed", ago(250), ago(240), null);
        step(direct, 0, "note", "{\"type\":\"instruction\",\"content\":\"Find the three newest applications.\\nThen sum up.\"}");
        step(direct, 1, "model_call", "{\"content\":\"An early thought\"}");
        step(direct, 2, "model_call", "{\"content\":\"## Three found\\nDetail.\"}");
        UUID failedDirect = directRun(ada, "failed", ago(230), ago(220), "The model refused.");
        goal(OTHER_ORG, "Elsewhere", "chat", null, null, ago(10));

        List<FeedRow> all = store.feedRows(ORG, OUTSIDER, null, false, 20);

        assertThat(all).extracting(FeedRow::kind)
                .containsExactlyInAnyOrder(
                        "goal_requested",
                        "goal_requested",
                        "task_started",
                        "task_finished",
                        "approval_requested",
                        "approval_decided",
                        "question_asked",
                        "question_answered",
                        "run_requested",
                        "run_requested",
                        "run_finished",
                        "run_finished");
        FeedRow scheduledRow = all.stream()
                .filter(row -> scheduled.equals(row.goalId()) && "goal_requested".equals(row.kind()))
                .findFirst()
                .orElseThrow();
        assertThat(scheduledRow.label()).isEqualTo("Monday digest");
        assertThat(scheduledRow.agentId()).isNull();
        FeedRow finished = all.stream().filter(row -> "task_finished".equals(row.kind())).findFirst().orElseThrow();
        assertThat(finished.body()).isEqualTo("## Shortlist\nThree.");
        FeedRow decidedRow = all.stream().filter(row -> "approval_decided".equals(row.kind())).findFirst().orElseThrow();
        assertThat(decidedRow.status()).isEqualTo("sent_back");
        assertThat(decidedRow.itemId()).isEqualTo(decided);
        assertThat(decidedRow.personId()).isEqualTo(PERSON);
        FeedRow asked = all.stream().filter(row -> "question_asked".equals(row.kind())).findFirst().orElseThrow();
        assertThat(asked.title()).isEqualTo("Formal or friendly?");

        FeedRow directAsked = all.stream()
                .filter(row -> "run_requested".equals(row.kind()) && direct.equals(row.runId()))
                .findFirst()
                .orElseThrow();
        assertThat(directAsked.personId()).isEqualTo(PERSON);
        assertThat(directAsked.title()).isEqualTo("Find the three newest applications.\nThen sum up.");
        assertThat(directAsked.source()).isEqualTo("manual");
        assertThat(directAsked.agentId()).isEqualTo(ada);
        FeedRow directDone = all.stream()
                .filter(row -> "run_finished".equals(row.kind()) && direct.equals(row.runId()))
                .findFirst()
                .orElseThrow();
        assertThat(directDone.status()).isEqualTo("completed");
        assertThat(directDone.body()).isEqualTo("## Three found\nDetail.");
        FeedRow directFailed = all.stream()
                .filter(row -> "run_finished".equals(row.kind()) && failedDirect.equals(row.runId()))
                .findFirst()
                .orElseThrow();
        assertThat(directFailed.status()).isEqualTo("failed");
        assertThat(directFailed.body()).isEqualTo("The model refused.");
        // A run for a task is told through its task, never twice.
        assertThat(all).noneMatch(row -> row.kind().startsWith("run_") && runId.equals(row.runId()));

        // The two goals share a moment; the cursor between them splits them exactly as Java orders them.
        List<FeedRow> sorted = all.stream().sorted(Comparator.comparing(FeedRow::cursor)).toList();
        FeedCursor between = sorted.get(0).cursor();
        assertThat(sorted.get(1).at()).isEqualTo(between.at());
        List<FeedRow> after = store.feedRows(ORG, OUTSIDER, between, true, 20);
        assertThat(after).extracting(FeedRow::eventId)
                .containsExactlyInAnyOrderElementsOf(sorted.subList(1, sorted.size()).stream().map(FeedRow::eventId).toList());
        List<FeedRow> before = store.feedRows(ORG, OUTSIDER, sorted.get(1).cursor(), false, 20);
        assertThat(before).extracting(FeedRow::eventId).containsExactly(sorted.get(0).eventId());

        // Each kind is limited on its own: one of each, newest first.
        assertThat(store.feedRows(ORG, OUTSIDER, null, false, 1)).hasSize(9);
    }

    // @find: test every kind of private event is left out, team room store persistence
    @Test
    @DisplayName("no kind of event from a private conversation reaches an outsider; all of them reach its people")
    void privateWorkIsLeftOutOfEveryKind() {
        UUID ada = agent(ORG, "Ada");
        UUID secret = privateConversation(ORG);
        UUID goalId = goal(ORG, "Salary review", "chat", secret, null, ago(600));
        UUID done = task(ORG, goalId, ada, "Compare salaries", "completed", 0, ago(500), ago(450), "Done.");
        UUID failed = task(ORG, goalId, ada, "Email the board", "failed", 1, ago(440), ago(430), null);
        UUID doneRun = run(ORG, done, ada, "completed", ago(500));
        UUID failedRun = run(ORG, failed, ada, "failed", ago(440));
        approval(doneRun, done, ada, "Email the figures", "approved", ago(480), ago(470));
        approval(failedRun, failed, ada, "Email the board", "pending", ago(435), null);
        question(doneRun, done, goalId, ada, "Which year?", true, ago(490));
        question(failedRun, failed, null, ada, "Which board?", false, ago(432));
        jdbc.update("update run_questions set conversation_id = ? where run_id = ?", secret, failedRun);

        assertThat(store.feedRows(ORG, OUTSIDER, null, false, 50)).isEmpty();
        assertThat(store.feedRows(ORG, OUTSIDER, FeedCursor.before(ago(10_000)), true, 50)).isEmpty();
        assertThat(store.feedRows(ORG, Viewer.person(null), null, false, 50)).isEmpty();

        for (Viewer insider : List.of(CREATOR, ADDED, Viewer.seeingAll())) {
            assertThat(store.feedRows(ORG, insider, null, false, 50)).extracting(FeedRow::kind)
                    .as("seen by %s", insider)
                    .containsExactlyInAnyOrder(
                            "goal_requested",
                            "task_started",
                            "task_started",
                            "task_finished",
                            "task_finished",
                            "approval_requested",
                            "approval_requested",
                            "approval_decided",
                            "question_asked",
                            "question_asked",
                            "question_answered");
        }
    }

    // @find: test thousands of private conversations, bind parameter limit, team room store persistence
    @Test
    @DisplayName("ten thousand private conversations hidden from a viewer do not change what the feed binds or break it")
    void thousandsOfPrivateConversations() {
        UUID ada = agent(ORG, "Ada");
        jdbc.update(
                """
                insert into conversations (id, org_id, title, visibility, created_by)
                select gen_random_uuid(), ?, 'Private', 'private', ?
                from generate_series(1, 10000)
                """,
                ORG, CREATOR.personId());
        UUID open = goal(ORG, "Hire a designer", "chat", workspaceConversation(ORG), null, ago(60));
        UUID secret = goal(ORG, "Salary review", "chat", privateConversation(ORG), null, ago(50));
        task(ORG, open, ada, "Screen", "pending", 0, null, null, null);
        task(ORG, secret, ada, "Compare", "pending", 0, null, null, null);
        UUID hiddenRun = run(ORG, task(ORG, secret, ada, "Read", "running", 1, ago(40), null, null), ada, "running",
                ago(40));
        Integer hiddenCount = jdbc.queryForObject(
                "select count(*) from conversations where org_id = ? and visibility = 'private'", Integer.class, ORG);
        assertThat(hiddenCount).isGreaterThan(10_000);

        List<FeedRow> rows = store.feedRows(ORG, OUTSIDER, null, false, 50);

        assertThat(rows).extracting(FeedRow::goalId).contains(open).doesNotContain(secret);
        assertThat(store.queuedByAgent(ORG, OUTSIDER)).containsExactly(Map.entry(ada, 1));
        assertThat(store.activeRuns(ORG, OUTSIDER)).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(hiddenRun);
            assertThat(run.hidden()).isTrue();
        });
    }
}
