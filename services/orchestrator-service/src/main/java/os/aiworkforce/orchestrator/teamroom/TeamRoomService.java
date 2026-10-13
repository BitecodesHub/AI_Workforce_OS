// @find: team room service, live presence, what each agent is doing, hand-off conversation, feed of goals tasks approvals questions, private work hidden, waiting for you, TeamRoomService, Team room page
// @what: Builds the team room: every agent's live presence and pages of the hand-off conversation, hiding other people's private work.
// @flow: Called by TeamRoomController; reads through TeamRoomStore, Agents and Tasks, and asks ApprovalService, QuestionService and ConversationAccess who may act and see.
package os.aiworkforce.orchestrator.teamroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.Activity;
import os.aiworkforce.orchestrator.teamroom.PresenceRules.RunState;
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
 * The team room: the AI team as colleagues at work.
 *
 * <p>Two reads, both polled by the page while it is visible, so both are a fixed handful of
 * statements however large the workspace is (no query per agent, run or event):
 *
 * <ul>
 *   <li>{@link #presence}: every agent that is not retired, with what it is doing now (see {@link
 *       PresenceRules}) and the run, goal or task it is doing it for.
 *   <li>{@link #feed}: the hand-off conversation, newest page by default, or the events after or
 *       before a {@link FeedCursor}.
 * </ul>
 *
 * <p>Private conversations stay private exactly as on the run pages (RunController.visibleRun,
 * ConversationAccess.hiddenTaskIds): an event from work a private conversation started is left out
 * of the conversation altogether, and an agent busy with such work is shown only as busy with
 * "private work", with no run, goal, task, approval or question. The rule is applied in the SQL
 * (TeamRoomStore), bound by the viewer's id alone, so its cost does not grow with the number of
 * private conversations.
 */
@Service
public class TeamRoomService {

    /** The events per page when the client does not ask for a number. */
    public static final int DEFAULT_LIMIT = 50;
    /** The most events one page holds. */
    public static final int MAX_LIMIT = 100;

    /**
     * How far back the "since" cursor reaches, so the next poll reads the last few seconds again.
     * An event is stamped when it happens but becomes readable only when its transaction commits, a
     * moment later; reading a short window twice (the client drops repeats by id) is what keeps an
     * event stamped just before the previous poll from being missed. A backlog read page by page
     * carries the window forward as the poll position's floor ({@link FeedCursor.Poll}).
     */
    static final Duration SETTLE = Duration.ofSeconds(20);

    /** Longest one-line text in an event. */
    private static final int LINE_LIMIT = 160;

    private final TeamRoomStore store;
    private final Agents agents;
    private final Tasks tasks;
    private final ApprovalService approvals;
    private final QuestionService questions;
    private final ConversationAccess access;
    private final Clock clock;

    /** Builds the service; used by Spring with the system clock. */
    @org.springframework.beans.factory.annotation.Autowired
    public TeamRoomService(
            TeamRoomStore store,
            Agents agents,
            Tasks tasks,
            ApprovalService approvals,
            QuestionService questions,
            ConversationAccess access) {
        this(store, agents, tasks, approvals, questions, access, Clock.systemUTC());
    }

    /** Builds the service with a fixed clock, for tests. */
    TeamRoomService(
            TeamRoomStore store,
            Agents agents,
            Tasks tasks,
            ApprovalService approvals,
            QuestionService questions,
            ConversationAccess access,
            Clock clock) {
        this.store = store;
        this.agents = agents;
        this.tasks = tasks;
        this.approvals = approvals;
        this.questions = questions;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Whose eyes the reads are for: everything for a holder of chat:read_all, otherwise the
     * viewer's own private conversations and every workspace one (ConversationAccess's rule). Pure
     * apart from the permission check.
     */
    Viewer viewer(Actor actor) {
        return access.canReadAll(actor) ? Viewer.seeingAll() : Viewer.person(actor.humanId());
    }

    // ---- Presence ----------------------------------------------------------------------------

    /**
     * Every agent that is not retired and what it is doing now, for {@code GET
     * /api/orchestrator/team-room}. Read only.
     *
     * <p>Flow: the agents (one query), the active runs with their task, goal, whether each is hidden
     * from the viewer and the approval or question a parked one waits on (one), the steps since the
     * latest model answer of the visible running ones (one), the last page of a browsing run only
     * when its call names no site (at most one) and queued counts (one). Each run becomes a {@link
     * RunState}; each agent shows the one that most needs a person ({@link PresenceRules#headline}).
     *
     * @param actor the viewer: decides what is private and what is waiting for them
     */
    // @find: team room presence, agents live state, GET /api/orchestrator/team-room
    public TeamRoom presence(UUID orgId, Actor actor) {
        Viewer viewer = viewer(actor);
        List<Agent> roster = agents.findByOrgIdOrderByName(orgId).stream()
                .filter(agent -> !agent.isRetired())
                .toList();
        List<ActiveRun> active = store.activeRuns(orgId, viewer);

        List<UUID> running = active.stream()
                .filter(run -> "running".equals(run.status()) && !run.hidden())
                .map(ActiveRun::runId)
                .toList();
        Map<UUID, List<StepFact>> steps = store.stepsSinceLastModelCall(running);
        List<UUID> needPage = running.stream()
                .filter(runId -> PresenceRules.needsLastPage(steps.getOrDefault(runId, List.of())))
                .toList();
        Map<UUID, String> lastPages = store.lastPages(needPage);
        Map<UUID, Integer> queued = store.queuedByAgent(orgId, viewer);

        Map<UUID, List<RunState>> byAgent = new HashMap<>();
        for (ActiveRun run : active) {
            RunState state = runState(
                    orgId, run, steps.getOrDefault(run.runId(), List.of()), lastPages.get(run.runId()), actor);
            byAgent.computeIfAbsent(run.agentId(), key -> new ArrayList<>()).add(state);
        }

        List<AgentPresence> presence = new ArrayList<>(roster.size());
        for (Agent agent : roster) {
            List<RunState> runs = byAgent.getOrDefault(agent.getId(), List.of());
            presence.add(agentPresence(agent, runs, queued.getOrDefault(agent.getId(), 0)));
        }
        return new TeamRoom(clock.instant(), presence);
    }

    /**
     * What one active run is doing, as the viewer may see it. Pure apart from the approval and
     * question permission checks (four-eyes rule included), which read the workspace setting.
     *
     * <p>An approval names no one it waits on: its requester may be exactly the person a four-eyes
     * rule stops from deciding it, and anyone holding the permission may decide. A question names
     * its requester, who may always answer the work they asked for.
     */
    RunState runState(UUID orgId, ActiveRun run, List<StepFact> steps, String lastPage, Actor actor) {
        Instant started = run.startedAt();
        if (run.hidden()) {
            return new RunState(
                    run.runId(), PresenceRules.PRIVATE, started, false, true, null, null, null, null, null, null,
                    null, null, null);
        }
        UUID requester = run.requestedBy() != null ? run.requestedBy() : parseUuid(run.createdBy());
        return switch (run.status()) {
            case "waiting_approval" -> {
                PendingApproval approval = run.approval();
                yield new RunState(
                        run.runId(),
                        PresenceRules.WAITING_APPROVAL,
                        approval == null || approval.requestedAt() == null ? started : approval.requestedAt(),
                        approval != null && mayDecide(orgId, approval, actor),
                        false,
                        run.goalId(),
                        run.goalTitle(),
                        run.taskId(),
                        run.taskTitle(),
                        null,
                        approval == null ? null : approval.id(),
                        null,
                        approval == null ? null : oneLine(approval.summary()),
                        null);
            }
            case "waiting_input" -> {
                PendingQuestion question = run.question();
                yield new RunState(
                        run.runId(),
                        PresenceRules.WAITING_INPUT,
                        question == null || question.createdAt() == null ? started : question.createdAt(),
                        question != null && mayAnswer(orgId, run, question, actor),
                        false,
                        run.goalId(),
                        run.goalTitle(),
                        run.taskId(),
                        run.taskTitle(),
                        null,
                        null,
                        question == null ? null : question.id(),
                        question == null ? null : oneLine(question.question()),
                        question != null && question.requestedBy() != null ? question.requestedBy() : requester);
            }
            default -> {
                Activity activity = PresenceRules.working(started, steps, lastPage);
                yield new RunState(
                        run.runId(),
                        activity.state(),
                        activity.since(),
                        false,
                        false,
                        run.goalId(),
                        run.goalTitle(),
                        run.taskId(),
                        run.taskTitle(),
                        activity,
                        null,
                        null,
                        null,
                        requester);
            }
        };
    }

    /**
     * Whether the viewer may decide this pending approval now: ApprovalService.canDecide on the
     * fields it reads (status, permission, requester, action class), without loading the payload.
     */
    private boolean mayDecide(UUID orgId, PendingApproval pending, Actor actor) {
        Approval check = new Approval();
        check.setId(pending.id());
        check.setOrgId(orgId);
        check.setStatus("pending");
        check.setSummary(pending.summary());
        check.setActionClass(pending.actionClass());
        if (pending.requiredPermission() != null) {
            check.setRequiredPermission(pending.requiredPermission());
        }
        check.setRequestedBy(pending.requestedBy());
        check.setRequestedAt(pending.requestedAt());
        return approvals.canDecide(actor, check);
    }

    /** Whether the viewer may answer this pending question now: QuestionService.canAnswer on its requester. */
    private boolean mayAnswer(UUID orgId, ActiveRun run, PendingQuestion pending, Actor actor) {
        RunQuestion check = new RunQuestion();
        check.setId(pending.id());
        check.setOrgId(orgId);
        check.setRunId(run.runId());
        check.setStatus("pending");
        check.setRequestedBy(pending.requestedBy());
        return questions.canAnswer(check, actor);
    }

    /** An agent's presence from its runs: the headline run's state, or resting (paused or idle). */
    private static AgentPresence agentPresence(Agent agent, List<RunState> runs, int queued) {
        RunState headline = PresenceRules.headline(runs).orElse(null);
        if (headline == null) {
            return new AgentPresence(
                    agent.getId(),
                    agent.getName(),
                    agent.getCategory(),
                    agent.getDescription(),
                    agent.isFallback(),
                    agent.getStatus(),
                    PresenceRules.restingState(agent.getStatus()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    0,
                    queued);
        }
        Activity activity = headline.activity();
        boolean shown = !headline.hidden();
        return new AgentPresence(
                agent.getId(),
                agent.getName(),
                agent.getCategory(),
                agent.getDescription(),
                agent.isFallback(),
                agent.getStatus(),
                headline.state(),
                headline.since(),
                shown ? headline.runId() : null,
                headline.goalId(),
                headline.goalTitle(),
                headline.taskId(),
                headline.taskTitle(),
                activity == null ? null : activity.server(),
                activity == null ? null : activity.tool(),
                activity == null ? null : activity.site(),
                headline.approvalId(),
                headline.questionId(),
                headline.waitingText(),
                shown ? headline.waitingOn() : null,
                headline.waitingForYou(),
                runs.size(),
                queued);
    }

    // ---- The conversation --------------------------------------------------------------------

    /**
     * One page of the hand-off conversation, for {@code GET /api/orchestrator/team-room/feed}. Read
     * only.
     *
     * <p>With neither cursor, the newest {@code limit} events. With {@code after}, the events after
     * its progress, oldest first, up to {@code limit} ({@code hasNewer} says whether more wait).
     * With {@code before}, the {@code limit} events before it. Every page is returned oldest first.
     *
     * <p>The {@code newestCursor} of the newest page and of an {@code after} page is where the next
     * poll starts, chosen so that no event is missed even when its transaction commits a moment
     * after it was stamped ({@link #SETTLE}):
     *
     * <ul>
     *   <li>more than a page waiting: carry on from the last event shown, and keep as the floor the
     *       point up to which every event was read after it settled;
     *   <li>the backlog drained, with a floor: start again from the floor, so the window crossed
     *       page by page is read once more now that it has settled;
     *   <li>otherwise: reach back to the settle line to read the last few seconds again, never
     *       behind where this poll started.
     * </ul>
     *
     * The client drops repeats by event id.
     *
     * <p>Flow: the events (one statement), the sibling tasks of finished tasks to find who each was
     * handed to (one, only when needed), the still-pending approvals the page asks for, to say
     * whether each waits on the viewer (one, only when needed), the agents (one).
     *
     * @param after the client's {@code newestCursor}, or null
     * @param before the client's {@code olderCursor}, or null; the controller refuses both at once
     * @param limit events per page, already held between 1 and {@value #MAX_LIMIT}
     */
    // @find: team room feed, hand-off conversation page, GET /api/orchestrator/team-room/feed
    public FeedPage feed(UUID orgId, Actor actor, FeedCursor.Poll after, FeedCursor before, int limit) {
        Instant now = clock.instant();
        FeedCursor settle = FeedCursor.before(now.minus(SETTLE));
        Viewer viewer = viewer(actor);
        boolean newer = after != null;
        FeedCursor cursor = newer ? after.progress() : before;

        List<FeedRow> rows = new ArrayList<>(store.feedRows(orgId, viewer, cursor, newer, limit + 1));
        Comparator<FeedRow> order = Comparator.comparing(FeedRow::cursor);
        rows.sort(newer ? order : order.reversed());
        boolean more = rows.size() > limit;
        List<FeedRow> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        if (!newer) {
            Collections.reverse(page);
        }

        List<FeedEvent> events = events(orgId, actor, page);
        FeedCursor first = page.isEmpty() ? null : page.getFirst().cursor();
        FeedCursor last = page.isEmpty() ? null : page.getLast().cursor();

        if (newer) {
            return new FeedPage(now, events, nextPoll(after, last, settle, more).encode(), null, false, more);
        }
        if (before != null) {
            return new FeedPage(now, events, null, first == null ? null : first.encode(), more, false);
        }
        FeedCursor.Poll next = FeedCursor.Poll.at(last == null ? settle : last.min(settle));
        return new FeedPage(now, events, next.encode(), first == null ? null : first.encode(), more, false);
    }

    /**
     * Where the poll after this one starts. Pure.
     *
     * <p>Every event at or before a poll position's floor (or, with no floor, its progress) has been
     * read at least once after it settled. A page read now settles everything up to {@code settle}.
     *
     * @param after where this poll started
     * @param last the last event this poll returned, or null for none
     * @param settle the settle line now: events before it can no longer gain late commits
     * @param more whether more events wait than this page held
     */
    static FeedCursor.Poll nextPoll(FeedCursor.Poll after, FeedCursor last, FeedCursor settle, boolean more) {
        FeedCursor from = after.progress();
        if (more) {
            // Make progress through the backlog from the last event shown. What this page read up to
            // the settle line is settled; what lies after it, and anything before this poll still
            // waiting on a floor, is read again once the backlog is drained.
            FeedCursor floor = after.floor() != null ? after.floor() : from.max(last.min(settle));
            return new FeedCursor.Poll(last, floor);
        }
        if (after.floor() != null) {
            // The backlog is drained: read again from the floor, once, so an event stamped inside the
            // window the pages crossed but committed after they passed it still arrives.
            return FeedCursor.Poll.at(after.floor());
        }
        // Reach back to the settle line to read the last few seconds again, but never behind where
        // this poll started: a cursor that moved backwards would re-read a backlog it already passed.
        return FeedCursor.Poll.at(from.max(from.max(last).min(settle)));
    }

    /**
     * Puts rows into events: agent names, who a finished task was handed to, whether a waiting
     * approval waits on the viewer, one-line texts. One query for the agents and, only when the page
     * needs them, one for its finished tasks' goals' tasks and one for its pending approvals.
     */
    private List<FeedEvent> events(UUID orgId, Actor actor, List<FeedRow> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, Agent> agentById = new HashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            agentById.put(agent.getId(), agent);
        }
        Set<UUID> handoffGoals = new LinkedHashSet<>();
        Set<UUID> waitingApprovals = new LinkedHashSet<>();
        for (FeedRow row : page) {
            if ("task_finished".equals(row.kind()) && "completed".equals(row.status()) && row.goalId() != null) {
                handoffGoals.add(row.goalId());
            }
            if ("approval_requested".equals(row.kind()) && "pending".equals(row.status()) && row.itemId() != null) {
                waitingApprovals.add(row.itemId());
            }
        }
        Map<UUID, List<Task>> siblingsByGoal = new HashMap<>();
        if (!handoffGoals.isEmpty()) {
            for (Task task : tasks.findByGoalIdInOrderByPositionAsc(handoffGoals)) {
                siblingsByGoal.computeIfAbsent(task.getGoalId(), key -> new ArrayList<>()).add(task);
            }
        }
        Set<UUID> forYou = new HashSet<>();
        if (!waitingApprovals.isEmpty()) {
            for (PendingApproval pending : store.pendingApprovals(orgId, waitingApprovals).values()) {
                if (mayDecide(orgId, pending, actor)) {
                    forYou.add(pending.id());
                }
            }
        }
        List<FeedEvent> events = new ArrayList<>(page.size());
        for (FeedRow row : page) {
            events.add(event(row, agentById, siblingsByGoal, forYou));
        }
        return events;
    }

    /** One row as an event: its kind refined by status, with names and texts filled in. */
    private static FeedEvent event(
            FeedRow row, Map<UUID, Agent> agentById, Map<UUID, List<Task>> siblingsByGoal, Set<UUID> forYou) {
        Agent agent = row.agentId() == null ? null : agentById.get(row.agentId());
        String kind = row.kind();
        String title = row.title();
        String text = null;
        Task next = null;
        String scheduleName = null;
        switch (row.kind()) {
            case "goal_requested" -> {
                if ("schedule".equals(row.source())) {
                    kind = "schedule_started";
                    scheduleName = row.label();
                }
            }
            case "task_finished" -> {
                if ("failed".equals(row.status())) {
                    kind = "task_failed";
                    text = oneLine(row.body());
                } else {
                    next = nextTask(row.taskId(), siblingsByGoal.getOrDefault(row.goalId(), List.of()));
                    kind = next != null && next.getAgentId() != null ? "task_handed_off" : "task_finished";
                    text = oneLine(row.body());
                }
            }
            case "run_requested" -> title = oneLine(row.title());
            case "run_finished" -> {
                kind = "completed".equals(row.status()) ? "run_finished" : "run_failed";
                title = oneLine(row.title());
                text = oneLine(row.body());
            }
            case "approval_requested" -> {
                title = null;
                text = oneLine(row.title());
            }
            case "approval_decided" -> {
                kind = switch (row.status() == null ? "" : row.status()) {
                    case "approved" -> "approval_approved";
                    case "sent_back" -> "approval_sent_back";
                    case "expired" -> "approval_expired";
                    default -> "approval_rejected";
                };
                title = null;
                text = oneLine(row.title());
            }
            case "question_asked", "question_answered" -> {
                title = null;
                text = oneLine(row.title());
            }
            default -> {
                /* task_started carries the task title as it is */
            }
        }
        Agent nextAgent = next == null || next.getAgentId() == null ? null : agentById.get(next.getAgentId());
        return new FeedEvent(
                row.eventId(),
                row.cursor().encode(),
                kind,
                row.at(),
                row.agentId(),
                agent == null ? null : agent.getName(),
                agent == null ? null : agent.getCategory(),
                agent != null && agent.isFallback(),
                row.personId(),
                next == null ? null : next.getAgentId(),
                nextAgent == null ? null : nextAgent.getName(),
                nextAgent == null ? null : nextAgent.getCategory(),
                next == null ? null : next.getTitle(),
                row.goalId(),
                row.taskId(),
                row.runId(),
                row.kind().startsWith("approval") ? row.itemId() : null,
                row.kind().startsWith("question") ? row.itemId() : null,
                title,
                text,
                scheduleName,
                "approval_requested".equals(row.kind()) && row.itemId() != null && forYou.contains(row.itemId()));
    }

    /**
     * The task a finished task hands its work to: the first sibling that names it in {@code
     * dependsOn}, otherwise (for siblings that name none, which wait for every earlier position) the
     * first sibling at the next position. The same rule the engine claims tasks by. Null when the
     * finished task was the last. Pure.
     */
    static Task nextTask(UUID taskId, List<Task> siblings) {
        Task finished = null;
        for (Task sibling : siblings) {
            if (sibling.getId().equals(taskId)) {
                finished = sibling;
            }
        }
        if (finished == null) {
            return null;
        }
        for (Task sibling : siblings) {
            List<UUID> dependsOn = sibling.getDependsOn();
            if (dependsOn != null && dependsOn.contains(taskId)) {
                return sibling;
            }
        }
        int position = finished.getPosition();
        Task next = null;
        for (Task sibling : siblings) {
            List<UUID> dependsOn = sibling.getDependsOn();
            boolean waitsForAllEarlier = dependsOn == null || dependsOn.isEmpty();
            if (waitsForAllEarlier
                    && sibling.getPosition() > position
                    && (next == null || sibling.getPosition() < next.getPosition())) {
                next = sibling;
            }
        }
        return next;
    }

    /**
     * The first line of a text with words in it, without Markdown's leading marks, cut to {@value
     * #LINE_LIMIT} characters with an ellipsis. Pure; null or blank gives null.
     *
     * <p>A fenced block (``` or ~~~) is skipped whole, fence lines included: an answer card such as
     * an email draft is a fenced block of JSON, and its fence line ("```email-draft") or its first
     * brace would otherwise stand in for the answer. Lines with no letter or digit (a rule such as
     * "---", a table's divider row) are skipped too. A text that is nothing but such lines gives
     * null, so the line falls back to the task or run title.
     */
    static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String fence = null;
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            String marker = fenceMarker(stripped);
            if (fence == null && marker != null) {
                fence = marker;
                continue;
            }
            if (fence != null) {
                if (marker != null && marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                        && stripped.substring(marker.length()).isBlank()) {
                    fence = null;
                }
                continue;
            }
            String plain = stripped.replaceFirst("^(#{1,6}\\s+|[-*+>]\\s+|\\d+[.)]\\s+)", "")
                    .replace("**", "")
                    .replace("__", "")
                    .strip();
            if (plain.codePoints().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            return plain.length() <= LINE_LIMIT ? plain : plain.substring(0, LINE_LIMIT - 1).stripTrailing() + "…";
        }
        return null;
    }

    /**
     * The fence a line opens or closes a fenced code block with (three or more backticks or tildes
     * at its start), or null when it is not a fence line. Pure.
     */
    private static String fenceMarker(String stripped) {
        if (stripped.length() < 3) {
            return null;
        }
        char mark = stripped.charAt(0);
        if (mark != '`' && mark != '~') {
            return null;
        }
        int length = 0;
        while (length < stripped.length() && stripped.charAt(length) == mark) {
            length++;
        }
        return length >= 3 ? stripped.substring(0, length) : null;
    }

    /** Parses an identifier, or null for null or a value that is not one. */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
