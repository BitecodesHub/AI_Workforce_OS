// @find: team room queries, active runs, pending approval and question of a parked run, in-flight tool call, steps since last model call, queued tasks by agent, feed events query, union of goals tasks runs approvals questions, keyset pagination sql, private conversation rule in sql, TeamRoomStore
// @what: The read-only SQL behind the team room: active runs with their task, goal and what they wait on, the latest steps of running runs, queued counts and the conversation's events around a cursor.
// @flow: Called by TeamRoomService; reads goals, tasks, runs, run_steps, approvals, run_questions, schedules, conversations and conversation_participants with NamedParameterJdbcTemplate.
package os.aiworkforce.orchestrator.teamroom;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.teamroom.PresenceRules.CallRecord;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.StepFact;

/**
 * The team room's reads, each one bounded and indexed, none of them per row: the presence is at
 * most four statements however many agents and runs there are, and a page of the conversation is
 * one.
 *
 * <p>Work from a private conversation is left out in the SQL itself, by the same rule as {@code
 * ConversationAccess.hiddenConversationIds}: a {@code private} conversation is hidden from everyone
 * but the person who started it, the people they added, and holders of {@code chat:read_all}. The
 * rule is a subquery bound by the viewer's person id alone, never by a list of the conversations it
 * hides, so a statement's parameters stay the same few however many private conversations a
 * workspace holds, and a page is never short because hidden rows were dropped after the limit.
 */
@Component
public class TeamRoomStore {

    /** The run statuses that still have an agent at work, or parked waiting on a person. */
    static final List<String> ACTIVE_STATUSES = List.of("running", "waiting_approval", "waiting_input");

    /** Most active runs read for one presence; a workspace with more shows the newest this many. */
    static final int ACTIVE_RUN_LIMIT = 500;

    /** Longest result, failure or instruction text read for one event; the service cuts it to one line anyway. */
    static final int TEXT_LIMIT = 600;

    /**
     * Whether the conversation in {@code %1$s} is a private one the viewer ({@code :me}) neither
     * started nor was added to, or one that no longer exists. The rule of {@code
     * ConversationAccess.HIDDEN_CONVERSATIONS} and {@code ConversationAccess.GONE_CONVERSATION}, as a
     * named-parameter predicate; a null conversation is never hidden. A conversation id that names
     * no conversation is a private one that was deleted (deleting a workspace one clears the id
     * from its work), so its work stays hidden from everyone without chat:read_all.
     */
    static final String HIDDEN_CONVERSATION =
            """
            %1$s is not null and (
                not exists (select 1 from conversations gc where gc.id = %1$s)
                or exists (select 1 from conversations hc
                           where hc.id = %1$s and hc.org_id = :orgId and hc.visibility = 'private'
                             and coalesce(hc.created_by, '') <> :me
                             and not exists (select 1 from conversation_participants hp
                                             where hp.conversation_id = hc.id and hp.user_id = :me)))""";

    /**
     * The workspace's runs still at work or parked, newest first, with the task and goal behind
     * each, whether it is hidden from the viewer ({@code %s}), and the pending approval or question
     * a parked run waits on: only the few fields presence shows, never an approval's payload.
     */
    private static final String ACTIVE_RUNS =
            """
            select r.id as run_id, r.agent_id, r.task_id, r.status, r.started_at, r.created_by,
                   t.title as task_title, t.goal_id, g.title as goal_title, g.requested_by,
                   %s as hidden,
                   pa.id as approval_id, pa.summary as approval_summary, pa.requested_by as approval_requested_by,
                   pa.requested_at as approval_requested_at, pa.action_class as approval_action_class,
                   pa.required_permission as approval_permission,
                   pq.id as question_id, pq.question as question_text, pq.requested_by as question_requested_by,
                   pq.created_at as question_created_at
            from runs r
            left join tasks t on t.id = r.task_id
            left join goals g on g.id = t.goal_id
            left join lateral (
                select a.id, a.summary, a.requested_by, a.requested_at, a.action_class, a.required_permission
                from approvals a
                where r.status = 'waiting_approval' and a.run_id = r.id and a.status = 'pending'
                order by a.requested_at desc
                limit 1) pa on true
            left join lateral (
                select q.id, q.questions -> 0 ->> 'question' as question, q.requested_by, q.created_at
                from run_questions q
                where r.status = 'waiting_input' and q.run_id = r.id and q.status = 'pending'
                order by q.created_at desc
                limit 1) pq on true
            where r.org_id = :orgId and r.status in (:statuses)
            order by r.started_at desc
            limit :limit
            """;

    /**
     * Every step of each run from its latest model answer on (every step, for a run with none yet):
     * only the small fields presence needs, never the detail document itself.
     */
    private static final String STEPS_SINCE_LAST_MODEL_CALL =
            """
            select s.run_id, s.kind, s.position, s.occurred_at,
                   case when s.kind = 'model_call' then (s.detail -> 'toolCallRecords')::text end as records,
                   s.detail ->> 'toolCallId' as tool_call_id,
                   s.detail ->> 'tool' as tool,
                   s.detail ->> 'url' as url
            from run_steps s
            where s.run_id in (:runIds)
              and s.position >= coalesce(
                    (select max(m.position) from run_steps m where m.run_id = s.run_id and m.kind = 'model_call'),
                    s.position)
            order by s.run_id, s.position
            """;

    private static final String LAST_PAGE =
            """
            select distinct on (s.run_id) s.run_id, s.detail ->> 'url' as url
            from run_steps s
            where s.run_id in (:runIds) and s.kind = 'tool_call' and coalesce(s.detail ->> 'url', '') <> ''
            order by s.run_id, s.position desc
            """;

    private static final String QUEUED_BY_AGENT =
            """
            select t.agent_id, count(*) as queued
            from tasks t
            join goals g on g.id = t.goal_id
            where t.org_id = :orgId and t.status in ('pending', 'ready') and t.agent_id is not null
              %s
            group by t.agent_id
            """;

    private static final String PENDING_APPROVALS =
            """
            select a.id, a.summary, a.requested_by, a.requested_at, a.action_class, a.required_permission
            from approvals a
            where a.org_id = :orgId and a.id in (:ids) and a.status = 'pending'
            """;

    /*
     * The conversation's events, one select per kind. Every select returns the same fourteen
     * columns in the same order, with typed nulls, so they can be stacked with UNION ALL. The event
     * id is part of the order (after the moment) so that two events in the same microsecond still
     * have one fixed order; ids are ASCII, compared under the "C" collation. Each select ends with
     * %s, where the private-conversation rule goes for a viewer who does not read every conversation.
     */

    private static final String GOAL_REQUESTED =
            """
            select 'goal_requested' as kind, g.created_at as happened_at, 'g:' || g.id::text as eid,
                   g.id as goal_id, cast(null as uuid) as task_id, cast(null as uuid) as run_id,
                   cast(null as uuid) as item_id,
                   (select ft.agent_id from tasks ft where ft.goal_id = g.id order by ft.position limit 1) as agent_id,
                   g.requested_by as person_id, g.title as title, cast(null as text) as body,
                   g.status as status, g.source as source, s.name as label
            from goals g
            left join schedules s on s.id = g.schedule_id
            where g.org_id = :orgId %s
            """;

    private static final String TASK_STARTED =
            """
            select 'task_started' as kind, t.started_at as happened_at,
                   't:' || t.id::text || ':' || t.attempt::text || ':s' as eid,
                   t.goal_id as goal_id, t.id as task_id, cast(null as uuid) as run_id,
                   cast(null as uuid) as item_id, t.agent_id as agent_id,
                   g.requested_by as person_id, t.title as title, cast(null as text) as body,
                   t.status as status, g.source as source, cast(null as text) as label
            from tasks t
            join goals g on g.id = t.goal_id
            where t.org_id = :orgId and t.started_at is not null %s
            """;

    private static final String TASK_FINISHED =
            """
            select 'task_finished' as kind, t.completed_at as happened_at,
                   't:' || t.id::text || ':' || t.attempt::text || ':f' as eid,
                   t.goal_id as goal_id, t.id as task_id, cast(null as uuid) as run_id,
                   cast(null as uuid) as item_id, t.agent_id as agent_id,
                   g.requested_by as person_id, t.title as title,
                   left(case when t.status = 'completed' then coalesce(t.result, '')
                             else coalesce(t.failure_reason, '') end, :textLimit) as body,
                   t.status as status, g.source as source, cast(null as text) as label
            from tasks t
            join goals g on g.id = t.goal_id
            where t.org_id = :orgId and t.completed_at is not null and t.status in ('completed', 'failed') %s
            """;

    /** Who started a run straight on an agent: the audit column holds a person's id as text, or a service's name. */
    private static final String RUN_PERSON =
            """
            case when r.created_by ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                 then cast(r.created_by as uuid) end""";

    /** What a run started straight on an agent was asked: its first step, the instruction note. */
    private static final String RUN_INSTRUCTION =
            """
            left((select si.detail ->> 'content' from run_steps si
                  where si.run_id = r.id and si.kind = 'note' and si.detail ->> 'type' = 'instruction'
                  order by si.position limit 1), :textLimit)""";

    private static final String RUN_REQUESTED =
            """
            select 'run_requested' as kind, r.started_at as happened_at, 'r:' || r.id::text || ':s' as eid,
                   cast(null as uuid) as goal_id, cast(null as uuid) as task_id, r.id as run_id,
                   cast(null as uuid) as item_id, r.agent_id as agent_id,
                   %1$s as person_id, %2$s as title, cast(null as text) as body,
                   r.status as status, r.trigger as source, cast(null as text) as label
            from runs r
            where r.org_id = :orgId and r.task_id is null %%s
            """
                    .formatted(RUN_PERSON, RUN_INSTRUCTION);

    private static final String RUN_FINISHED =
            """
            select 'run_finished' as kind, r.completed_at as happened_at, 'r:' || r.id::text || ':f' as eid,
                   cast(null as uuid) as goal_id, cast(null as uuid) as task_id, r.id as run_id,
                   cast(null as uuid) as item_id, r.agent_id as agent_id,
                   %1$s as person_id, %2$s as title,
                   left(case when r.status = 'completed'
                             then coalesce((select sa.detail ->> 'content' from run_steps sa
                                            where sa.run_id = r.id and sa.kind = 'model_call'
                                            order by sa.position desc limit 1), '')
                             else coalesce(r.failure_reason, '') end, :textLimit) as body,
                   r.status as status, r.trigger as source, cast(null as text) as label
            from runs r
            where r.org_id = :orgId and r.task_id is null and r.completed_at is not null
              and r.status in ('completed', 'failed', 'cancelled', 'abandoned') %%s
            """
                    .formatted(RUN_PERSON, RUN_INSTRUCTION);

    private static final String APPROVAL_REQUESTED =
            """
            select 'approval_requested' as kind, a.requested_at as happened_at, 'a:' || a.id::text || ':r' as eid,
                   g.id as goal_id, a.task_id as task_id, a.run_id as run_id, a.id as item_id,
                   a.agent_id as agent_id, coalesce(a.requested_by, g.requested_by) as person_id,
                   a.summary as title, a.tool as body, a.status as status, g.source as source,
                   cast(null as text) as label
            from approvals a
            left join tasks t on t.id = a.task_id
            left join goals g on g.id = t.goal_id
            where a.org_id = :orgId %s
            """;

    private static final String APPROVAL_DECIDED =
            """
            select 'approval_decided' as kind, a.decided_at as happened_at, 'a:' || a.id::text || ':d' as eid,
                   g.id as goal_id, a.task_id as task_id, a.run_id as run_id, a.id as item_id,
                   a.agent_id as agent_id, a.decided_by as person_id, a.summary as title, a.tool as body,
                   case when a.sent_back then 'sent_back' else a.status end as status, g.source as source,
                   cast(null as text) as label
            from approvals a
            left join tasks t on t.id = a.task_id
            left join goals g on g.id = t.goal_id
            where a.org_id = :orgId and a.decided_at is not null and a.status in ('approved', 'rejected', 'expired') %s
            """;

    private static final String QUESTION_ASKED =
            """
            select 'question_asked' as kind, q.created_at as happened_at, 'q:' || q.id::text || ':a' as eid,
                   q.goal_id as goal_id, q.task_id as task_id, q.run_id as run_id, q.id as item_id,
                   q.agent_id as agent_id, q.requested_by as person_id,
                   (q.questions -> 0 ->> 'question') as title, cast(null as text) as body,
                   q.status as status, g.source as source, cast(null as text) as label
            from run_questions q
            left join goals g on g.id = q.goal_id
            where q.org_id = :orgId %s
            """;

    private static final String QUESTION_ANSWERED =
            """
            select 'question_answered' as kind, q.answered_at as happened_at, 'q:' || q.id::text || ':r' as eid,
                   q.goal_id as goal_id, q.task_id as task_id, q.run_id as run_id, q.id as item_id,
                   q.agent_id as agent_id, q.answered_by as person_id,
                   (q.questions -> 0 ->> 'question') as title, cast(null as text) as body,
                   q.status as status, g.source as source, cast(null as text) as label
            from run_questions q
            left join goals g on g.id = q.goal_id
            where q.org_id = :orgId and q.answered_at is not null and q.status = 'answered' %s
            """;

    /** The conversation a goal's work came from. */
    private static final String GOAL_CONVERSATION = "g.conversation_id";

    /** The conversation a question came from: its goal's, or the one it is shown in. */
    private static final String QUESTION_CONVERSATION = "coalesce(g.conversation_id, q.conversation_id)";

    /**
     * One kind of event: its select, and the conversation its work came from, for the private rule
     * (null for a run started straight on an agent, which no conversation started, and which the
     * run pages show to everyone with {@code run:read}).
     */
    record FeedBranch(String kind, String sql, String conversation) {}

    /** Every kind of event, in the order their selects are stacked. */
    static final List<FeedBranch> FEED_BRANCHES = List.of(
            new FeedBranch("goal_requested", GOAL_REQUESTED, GOAL_CONVERSATION),
            new FeedBranch("task_started", TASK_STARTED, GOAL_CONVERSATION),
            new FeedBranch("task_finished", TASK_FINISHED, GOAL_CONVERSATION),
            new FeedBranch("run_requested", RUN_REQUESTED, null),
            new FeedBranch("run_finished", RUN_FINISHED, null),
            new FeedBranch("approval_requested", APPROVAL_REQUESTED, GOAL_CONVERSATION),
            new FeedBranch("approval_decided", APPROVAL_DECIDED, GOAL_CONVERSATION),
            new FeedBranch("question_asked", QUESTION_ASKED, QUESTION_CONVERSATION),
            new FeedBranch("question_answered", QUESTION_ANSWERED, QUESTION_CONVERSATION));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    /** Receives the named-parameter JDBC template and the JSON reader for stored tool-call records. */
    public TeamRoomStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Whose eyes a read is for.
     *
     * @param seesAll the viewer holds {@code chat:read_all}, so no conversation is hidden from them
     * @param personId the viewer's person id as text; empty for an actor with none, which then matches
     *     no creator or participant, so every private conversation is hidden from it
     */
    public record Viewer(boolean seesAll, String personId) {

        /** Treats a null person id as the empty one. */
        public Viewer {
            personId = personId == null ? "" : personId;
        }

        /** A holder of {@code chat:read_all}: every conversation's work is in sight. Pure. */
        public static Viewer seeingAll() {
            return new Viewer(true, "");
        }

        /** A person who sees workspace conversations and the private ones they started or were added to. Pure. */
        public static Viewer person(String personId) {
            return new Viewer(false, personId);
        }
    }

    /** The pending approval a parked run waits on: the fields presence shows and the decision check needs. */
    public record PendingApproval(
            UUID id, String summary, UUID requestedBy, Instant requestedAt, String actionClass, String requiredPermission) {}

    /** The pending question a parked run waits on: its first question and who asked for the work. */
    public record PendingQuestion(UUID id, String question, UUID requestedBy, Instant createdAt) {}

    /**
     * One active run with the task and goal behind it (both null for a run started straight on an
     * agent).
     *
     * @param createdBy who started a direct run (text, as the audit column stores it)
     * @param hidden the run's work came from a private conversation hidden from the viewer
     * @param approval the pending approval a run in {@code waiting_approval} waits on, or null
     * @param question the pending question a run in {@code waiting_input} waits on, or null
     */
    public record ActiveRun(
            UUID runId,
            UUID agentId,
            UUID taskId,
            String status,
            Instant startedAt,
            String createdBy,
            String taskTitle,
            UUID goalId,
            String goalTitle,
            UUID requestedBy,
            boolean hidden,
            PendingApproval approval,
            PendingQuestion question) {}

    /**
     * One event row as the feed query returns it, before it is put into words.
     *
     * <p>{@code itemId} is the approval or question; {@code personId} is whoever the event is about
     * (requester, decider, answerer, or who started a direct run); {@code title} and {@code body}
     * carry the kind's own text (see the selects); {@code label} is the schedule's name for a
     * scheduled goal; {@code source} is the goal's source, or the trigger of a direct run.
     */
    public record FeedRow(
            String kind,
            Instant at,
            String eventId,
            UUID goalId,
            UUID taskId,
            UUID runId,
            UUID itemId,
            UUID agentId,
            UUID personId,
            String title,
            String body,
            String status,
            String source,
            String label) {

        /** This row's position in the conversation. */
        public FeedCursor cursor() {
            return new FeedCursor(at, eventId);
        }
    }

    /**
     * Every run of the workspace still in progress or parked, newest first, at most {@value
     * #ACTIVE_RUN_LIMIT}, each with whether it is hidden from {@code viewer} and the approval or
     * question it waits on. One read-only statement (runs_org_status_started_idx, and per parked run
     * approvals_run_requested_idx or run_questions_run_idx).
     */
    // @find: active runs for presence, runs in progress
    public List<ActiveRun> activeRuns(UUID orgId, Viewer viewer) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("statuses", ACTIVE_STATUSES)
                .addValue("limit", ACTIVE_RUN_LIMIT);
        String hidden = hiddenPredicate(viewer, GOAL_CONVERSATION, params);
        return jdbc.query(ACTIVE_RUNS.formatted(hidden), params, (rs, index) -> {
            UUID approvalId = rs.getObject("approval_id", UUID.class);
            UUID questionId = rs.getObject("question_id", UUID.class);
            return new ActiveRun(
                    rs.getObject("run_id", UUID.class),
                    rs.getObject("agent_id", UUID.class),
                    rs.getObject("task_id", UUID.class),
                    rs.getString("status"),
                    instant(rs, "started_at"),
                    rs.getString("created_by"),
                    rs.getString("task_title"),
                    rs.getObject("goal_id", UUID.class),
                    rs.getString("goal_title"),
                    rs.getObject("requested_by", UUID.class),
                    rs.getBoolean("hidden"),
                    approvalId == null
                            ? null
                            : new PendingApproval(
                                    approvalId,
                                    rs.getString("approval_summary"),
                                    rs.getObject("approval_requested_by", UUID.class),
                                    instant(rs, "approval_requested_at"),
                                    rs.getString("approval_action_class"),
                                    rs.getString("approval_permission")),
                    questionId == null
                            ? null
                            : new PendingQuestion(
                                    questionId,
                                    rs.getString("question_text"),
                                    rs.getObject("question_requested_by", UUID.class),
                                    instant(rs, "question_created_at")));
        });
    }

    /**
     * For each run, its steps from its latest model answer on, oldest first; a run with no model
     * answer yet gets every step. One read-only statement; an empty id set reads nothing.
     */
    // @find: steps since last model call, in-flight tool calls
    public Map<UUID, List<StepFact>> stepsSinceLastModelCall(Collection<UUID> runIds) {
        Map<UUID, List<StepFact>> byRun = new LinkedHashMap<>();
        if (runIds.isEmpty()) {
            return byRun;
        }
        jdbc.query(STEPS_SINCE_LAST_MODEL_CALL, new MapSqlParameterSource("runIds", runIds), rs -> {
            UUID runId = rs.getObject("run_id", UUID.class);
            byRun.computeIfAbsent(runId, key -> new ArrayList<>())
                    .add(new StepFact(
                            runId,
                            rs.getString("kind"),
                            rs.getInt("position"),
                            instant(rs, "occurred_at"),
                            records(rs.getString("records")),
                            rs.getString("tool_call_id"),
                            rs.getString("tool"),
                            rs.getString("url")));
        });
        return byRun;
    }

    /**
     * The page each run's web browser last had open, from its newest tool call that recorded one.
     * One read-only statement; an empty id set reads nothing.
     */
    // @find: last page the browser had open
    public Map<UUID, String> lastPages(Collection<UUID> runIds) {
        Map<UUID, String> pages = new HashMap<>();
        if (runIds.isEmpty()) {
            return pages;
        }
        jdbc.query(LAST_PAGE, new MapSqlParameterSource("runIds", runIds), rs -> {
            pages.put(rs.getObject("run_id", UUID.class), rs.getString("url"));
        });
        return pages;
    }

    /**
     * How many tasks wait to start, per agent, leaving out work from conversations hidden from the
     * viewer. One read-only statement (tasks_ready_idx).
     */
    // @find: queued tasks per agent
    public Map<UUID, Integer> queuedByAgent(UUID orgId, Viewer viewer) {
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId);
        String visible = visibleClause(viewer, GOAL_CONVERSATION, params);
        Map<UUID, Integer> queued = new HashMap<>();
        jdbc.query(QUEUED_BY_AGENT.formatted(visible), params, rs -> {
            queued.put(rs.getObject("agent_id", UUID.class), rs.getInt("queued"));
        });
        return queued;
    }

    /**
     * The approvals among {@code ids} that are still pending, with what the decision check needs
     * and without their payload. One read-only statement (the primary key); an empty id set reads
     * nothing. The caller passes at most a page of ids.
     */
    // @find: pending approvals by id, approval decision check without payload
    public Map<UUID, PendingApproval> pendingApprovals(UUID orgId, Collection<UUID> ids) {
        Map<UUID, PendingApproval> pending = new HashMap<>();
        if (ids.isEmpty()) {
            return pending;
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("ids", ids);
        jdbc.query(PENDING_APPROVALS, params, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            pending.put(
                    id,
                    new PendingApproval(
                            id,
                            rs.getString("summary"),
                            rs.getObject("requested_by", UUID.class),
                            instant(rs, "requested_at"),
                            rs.getString("action_class"),
                            rs.getString("required_permission")));
        });
        return pending;
    }

    /**
     * The events on one side of a cursor, at most {@code limit} of each kind, in no particular
     * overall order: the caller merges them and keeps the first {@code limit}.
     *
     * <p>One read-only statement (see {@link #feedSql}): one select per kind, each ordered and
     * limited on its own index, stacked with UNION ALL. {@code newer} false reads events before
     * {@code cursor}, newest first (all of them when the cursor is null); true reads events after
     * it, oldest first. Events whose work a conversation hidden from {@code viewer} started are
     * left out in each select.
     */
    // @find: feed events around a cursor, team room conversation page
    public List<FeedRow> feedRows(UUID orgId, Viewer viewer, FeedCursor cursor, boolean newer, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("limit", limit)
                .addValue("textLimit", TEXT_LIMIT);
        if (cursor != null) {
            params.addValue("cursorAt", Timestamp.from(cursor.at())).addValue("cursorId", cursor.id());
        }
        return jdbc.query(feedSql(viewer, cursor != null, newer, params), params, (rs, index) -> new FeedRow(
                rs.getString("kind"),
                instant(rs, "happened_at"),
                rs.getString("eid"),
                rs.getObject("goal_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("label")));
    }

    /**
     * The feed statement: every {@link #FEED_BRANCHES} select with the private rule for {@code
     * viewer} where its work can come from a conversation, filtered to one side of the cursor,
     * ordered and limited on its own, stacked with UNION ALL. Binds the viewer's id into {@code
     * params} when the rule is needed; no other parameter depends on the viewer. Pure apart from
     * that.
     */
    static String feedSql(Viewer viewer, boolean hasCursor, boolean newer, MapSqlParameterSource params) {
        String side;
        if (!hasCursor) {
            side = "true";
        } else {
            side = newer
                    ? "b.happened_at >= :cursorAt and (b.happened_at > :cursorAt or b.eid collate \"C\" > :cursorId)"
                    : "b.happened_at <= :cursorAt and (b.happened_at < :cursorAt or b.eid collate \"C\" < :cursorId)";
        }
        String direction = newer ? "asc" : "desc";
        StringBuilder sql = new StringBuilder();
        for (FeedBranch branch : FEED_BRANCHES) {
            if (!sql.isEmpty()) {
                sql.append("\nunion all\n");
            }
            sql.append("(select * from (")
                    .append(branch.sql().formatted(visibleClause(viewer, branch.conversation(), params)))
                    .append(") b where ")
                    .append(side)
                    .append(" order by b.happened_at ")
                    .append(direction)
                    .append(", b.eid collate \"C\" ")
                    .append(direction)
                    .append(" limit :limit)");
        }
        return sql.toString();
    }

    /**
     * The clause that leaves out work from conversations hidden from the viewer, as {@code and not
     * (...)} over {@code conversation}; empty when the viewer sees every conversation or the work
     * cannot come from one. Binds the viewer's id (once, as a single value).
     */
    static String visibleClause(Viewer viewer, String conversation, MapSqlParameterSource params) {
        if (viewer.seesAll() || conversation == null) {
            return "";
        }
        return "and not " + hiddenPredicate(viewer, conversation, params);
    }

    /**
     * Whether the work behind {@code conversation} is hidden from the viewer, as a boolean SQL
     * expression: {@code false} for a viewer who sees everything, otherwise the private rule. Binds
     * the viewer's id (once, as a single value).
     */
    static String hiddenPredicate(Viewer viewer, String conversation, MapSqlParameterSource params) {
        if (viewer.seesAll()) {
            return "false";
        }
        params.addValue("me", viewer.personId());
        return "(" + HIDDEN_CONVERSATION.formatted(conversation) + ")";
    }

    /** Reads stored {@code toolCallRecords} JSON into call records; anything unreadable is no calls. */
    private List<CallRecord> records(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = json.readTree(text);
            if (!node.isArray()) {
                return List.of();
            }
            List<CallRecord> calls = new ArrayList<>();
            for (JsonNode call : node) {
                calls.add(new CallRecord(
                        textOrNull(call, "id"), textOrNull(call, "name"), textOrNull(call, "argumentsJson")));
            }
            return calls;
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** A text field of a JSON object, or null. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** A timestamp column as an instant, or null. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
