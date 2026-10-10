// @find: conversation queries, list conversations, conversation list, search chats, conversation detail, messages page, earlier messages, unread, pinned, archived, needs me, ConversationQueries, chat sidebar
// @what: Read-only queries that build the conversation list, a conversation's detail and its pages of messages for a person.
// @flow: Called by ChatController.
package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.board.BoardService;
import os.aiworkforce.orchestrator.board.GoalViews;
import os.aiworkforce.orchestrator.board.GoalViews.TaskRuns;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.ConversationMark;
import os.aiworkforce.orchestrator.domain.Goal;
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
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Reads conversations and their messages for a person, without changing anything.
 *
 * <p>The list a person sees is one shared, workspace-wide list, personalised only by what this
 * class adds on top of it: which rows they pinned or archived, which ones have something waiting
 * on them, and which are unread - all kept in {@link ConversationMark}, never on {@link
 * Conversation} itself, so pinning or reading a shared thread changes nobody else's list.
 */
@Service
public class ConversationQueries {

    private static final int MAX_QUERY_CHARS = 200;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final int DETAIL_MIN_LIMIT = 20;
    private static final int DETAIL_MAX_LIMIT = 500;
    private static final int MESSAGES_MIN_LIMIT = 1;
    private static final int MESSAGES_MAX_LIMIT = 200;
    /** The most new messages one incremental read returns; more than this and it reads the whole window instead. */
    private static final int DELTA_MESSAGE_LIMIT = 50;
    private static final int QUESTION_LIMIT = 100;
    /**
     * How far back an incremental read looks before the moment it was asked about. A row written
     * just before that moment may commit just after the read that produced it, and would otherwise
     * be missed by every read that follows. Seeing a row twice costs nothing: readers merge by id.
     */
    private static final Duration DELTA_OVERLAP = Duration.ofSeconds(5);
    private static final int SEARCH_MATCH_BATCH = 500;
    private static final int SNIPPET_BEFORE = 60;
    private static final int SNIPPET_AFTER = 80;
    private static final Set<String> SCOPES = Set.of("all", "mine", "archived");
    private static final List<String> GOAL_ACTIVE_STATUSES = List.of("planning", "running", "waiting");
    /** Never a real conversation id; stands in for an empty {@code in (...)} or {@code not in (...)} list. */
    private static final UUID NONE = new UUID(0L, 0L);

    private final Conversations conversations;
    private final ConversationMarks marks;
    private final ChatMessages messages;
    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final QuestionService questions;

    /** Who may read which conversation; absent only where a test builds this service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationAccess access;

    /** The conversation's waiting messages; absent only where a test builds this service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatQueue chatQueue;

    public ConversationQueries(
            Conversations conversations,
            ConversationMarks marks,
            ChatMessages messages,
            Goals goals,
            Tasks tasks,
            Runs runs,
            QuestionService questions) {
        this.conversations = conversations;
        this.marks = marks;
        this.messages = messages;
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.questions = questions;
    }

    // ---- Shapes -----------------------------------------------------------------------------

    public record SearchMatch(UUID messageId, String snippet) {}

    public record ConversationView(
            UUID id,
            String title,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            String lastMessagePreview,
            int messageCount,
            boolean pinned,
            boolean archived,
            String activity,
            boolean canManage,
            boolean unread,
            SearchMatch match,
            /** {@code private} (its creator and the people added) or {@code workspace} (everyone with Chat access). */
            String visibility,
            /** Whether this person may change who can read it: its creator, or a holder of chat:read_all. */
            boolean canShare) {}

    public record ConversationPage(
            List<ConversationView> pinned,
            List<ConversationView> needsYou,
            List<ConversationView> conversations,
            boolean hasMore) {}

    public record ChatMessageView(
            UUID id,
            int position,
            String authorKind,
            UUID authorId,
            UUID agentId,
            String kind,
            String content,
            Map<String, Object> detail,
            UUID goalId,
            Instant createdAt) {}

    public record ConversationDetail(
            ConversationView conversation,
            List<ChatMessageView> messages,
            List<BoardService.GoalView> goals,
            List<QuestionService.QuestionView> questions,
            boolean hasEarlier,
            Instant generatedAt,
            /** Every message waiting its turn, oldest first; always the whole queue, also on a delta read. */
            List<ChatQueue.QueuedMessageView> queued,
            /** idle, working or waiting_decision. */
            String busy) {

        public ConversationDetail(
                ConversationView conversation,
                List<ChatMessageView> messages,
                List<BoardService.GoalView> goals,
                List<QuestionService.QuestionView> questions,
                boolean hasEarlier,
                Instant generatedAt) {
            this(conversation, messages, goals, questions, hasEarlier, generatedAt, List.of(), "idle");
        }

        ConversationDetail withQueue(List<ChatQueue.QueuedMessageView> waiting, String state) {
            return new ConversationDetail(
                    conversation, messages, goals, questions, hasEarlier, generatedAt, waiting, state);
        }
    }

    public record MessagesPage(List<ChatMessageView> messages, boolean hasEarlier) {}

    // ---- The conversation list ----------------------------------------------------------------

    // @find: list conversations, search messages, chat sidebar, filters pinned archived unread, GET /api/conversations
    @Transactional(readOnly = true)
    public ConversationPage list(UUID orgId, Actor actor, String q, String scope, int page, int size) {
        String query = q == null ? "" : q.strip();
        if (query.length() > MAX_QUERY_CHARS) {
            throw ApiException.validation("q", "must be at most " + MAX_QUERY_CHARS + " characters");
        }
        String effectiveScope = scope == null ? "all" : scope;
        if (!SCOPES.contains(effectiveScope)) {
            throw ApiException.validation("scope", "must be one of " + SCOPES);
        }
        int effectiveSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        int effectivePage = Math.max(0, page);
        String pattern = query.isBlank() ? "" : "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";

        UUID me = parseUuidOrNull(actor.humanId());
        String actorId = actor.humanId();
        boolean readAll = actor.hasPermission(Permission.Codes.CHAT_READ_ALL);
        boolean mine = "mine".equals(effectiveScope);
        List<ConversationMark> myMarks = me == null ? List.of() : marks.findByOrgIdAndUserId(orgId, me);
        Map<UUID, ConversationMark> markByConversation = new LinkedHashMap<>();
        Set<UUID> pinnedIds = new LinkedHashSet<>();
        Set<UUID> archivedIds = new LinkedHashSet<>();
        for (ConversationMark mark : myMarks) {
            markByConversation.put(mark.getConversationId(), mark);
            if (mark.isPinned() && !mark.isArchived()) {
                pinnedIds.add(mark.getConversationId());
            }
            if (mark.isArchived()) {
                archivedIds.add(mark.getConversationId());
            }
        }

        if ("archived".equals(effectiveScope)) {
            List<Conversation> archivedRows = archivedIds.isEmpty()
                    ? List.of()
                    : conversations.searchAmong(orgId, archivedIds, false, actorId, readAll, pattern);
            int from = Math.min(effectivePage * effectiveSize, archivedRows.size());
            int to = Math.min(from + effectiveSize, archivedRows.size());
            List<Conversation> page1 = archivedRows.subList(from, to);
            Map<UUID, SearchMatch> matches = matchesFor(page1, query);
            List<ConversationView> views = page1.stream()
                    .map(c -> view(
                            c, actor, markByConversation.get(c.getId()), Map.of(), Map.of(), Map.of(), me, matches))
                    .toList();
            return new ConversationPage(List.of(), List.of(), views, to < archivedRows.size());
        }

        List<ConversationView> pinnedViews = List.of();
        List<ConversationView> needsYouViews = List.of();
        List<Conversation> pinnedRows = List.of();
        List<Conversation> needsYouRows = List.of();
        if (effectivePage == 0) {
            pinnedRows = pinnedIds.isEmpty()
                    ? List.of()
                    : conversations.searchAmong(orgId, pinnedIds, mine, actorId, readAll, pattern);

            Set<UUID> needsYouIds = new LinkedHashSet<>(questions.conversationsNeedingAnswerFrom(orgId, me));
            if (actor.hasPermission(Permission.Codes.APPROVAL_DECIDE)) {
                needsYouIds.addAll(tasks.conversationsWaitingForApproval(orgId));
            }
            needsYouIds.removeAll(pinnedIds);
            needsYouRows = needsYouIds.isEmpty()
                    ? List.of()
                    : conversations.searchAmong(orgId, needsYouIds, false, actorId, readAll, pattern);
        }

        Set<UUID> excluded = new LinkedHashSet<>(pinnedIds);
        excluded.addAll(archivedIds);
        List<Conversation> mainRowsFetched = conversations.searchExcluding(
                orgId, orDummy(excluded), mine, actorId, readAll, pattern, PageRequest.of(effectivePage, effectiveSize + 1));
        boolean hasMore = mainRowsFetched.size() > effectiveSize;
        List<Conversation> mainRows = hasMore ? mainRowsFetched.subList(0, effectiveSize) : mainRowsFetched;

        List<Conversation> all = new ArrayList<>(pinnedRows.size() + needsYouRows.size() + mainRows.size());
        all.addAll(pinnedRows);
        all.addAll(needsYouRows);
        all.addAll(mainRows);
        Set<UUID> allIds = all.stream()
                .map(Conversation::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Boolean> activeGoal = activityMap(goals.activeByConversation(orgId, orDummy(allIds)));
        Map<UUID, Boolean> waitingApproval = activityMap(tasks.waitingApprovalByConversation(orgId, orDummy(allIds)));
        Map<UUID, List<UUID>> pendingRequesters = new LinkedHashMap<>();
        for (Object[] row : questions.pendingByConversation(orgId, allIds)) {
            UUID conversationId = (UUID) row[0];
            UUID requestedBy = (UUID) row[1];
            pendingRequesters
                    .computeIfAbsent(conversationId, k -> new ArrayList<>())
                    .add(requestedBy);
        }
        Map<UUID, SearchMatch> matches = matchesFor(all, query);

        pinnedViews = pinnedRows.stream()
                .map(c -> view(
                        c,
                        actor,
                        markByConversation.get(c.getId()),
                        activeGoal,
                        waitingApproval,
                        pendingRequesters,
                        me,
                        matches))
                .toList();
        needsYouViews = needsYouRows.stream()
                .map(c -> view(
                        c,
                        actor,
                        markByConversation.get(c.getId()),
                        activeGoal,
                        waitingApproval,
                        pendingRequesters,
                        me,
                        matches))
                .toList();
        List<ConversationView> mainViews = mainRows.stream()
                .map(c -> view(
                        c,
                        actor,
                        markByConversation.get(c.getId()),
                        activeGoal,
                        waitingApproval,
                        pendingRequesters,
                        me,
                        matches))
                .toList();
        return new ConversationPage(pinnedViews, needsYouViews, mainViews, hasMore);
    }

    /** The view for a single, freshly created or freshly changed conversation, with no marks yet loaded. */
    // @find: conversation view, row in chat list
    @Transactional(readOnly = true)
    public ConversationView view(UUID orgId, Actor actor, Conversation conversation) {
        UUID me = parseUuidOrNull(actor.humanId());
        ConversationMark mark = me == null
                ? null
                : marks.findByConversationIdAndUserId(conversation.getId(), me).orElse(null);
        Map<UUID, Boolean> activeGoal = activityMap(goals.activeByConversation(orgId, List.of(conversation.getId())));
        Map<UUID, Boolean> waitingApproval =
                activityMap(tasks.waitingApprovalByConversation(orgId, List.of(conversation.getId())));
        Map<UUID, List<UUID>> pendingRequesters = new LinkedHashMap<>();
        for (Object[] row : questions.pendingByConversation(orgId, List.of(conversation.getId()))) {
            pendingRequesters
                    .computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add((UUID) row[1]);
        }
        return view(conversation, actor, mark, activeGoal, waitingApproval, pendingRequesters, me, Map.of());
    }

    private ConversationView view(
            Conversation conversation,
            Actor actor,
            ConversationMark mark,
            Map<UUID, Boolean> activeGoal,
            Map<UUID, Boolean> waitingApproval,
            Map<UUID, List<UUID>> pendingRequesters,
            UUID me,
            Map<UUID, SearchMatch> matches) {
        UUID id = conversation.getId();
        String activity = activityOf(id, actor, me, activeGoal, waitingApproval, pendingRequesters);
        boolean unread = mark != null
                && mark.getLastReadPosition() != null
                && conversation.getMessageCount() - 1 > mark.getLastReadPosition();
        boolean canManage = canManage(conversation, actor);
        SearchMatch match = matches.get(id);
        return new ConversationView(
                id,
                conversation.getTitle(),
                parseUuidOrNull(conversation.getCreatedBy()),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                conversation.getLastMessagePreview(),
                conversation.getMessageCount(),
                mark != null && mark.isPinned(),
                mark != null && mark.isArchived(),
                activity,
                canManage,
                unread,
                match,
                conversation.getVisibility(),
                canShare(conversation, actor));
    }

    private static String activityOf(
            UUID id,
            Actor actor,
            UUID me,
            Map<UUID, Boolean> activeGoal,
            Map<UUID, Boolean> waitingApproval,
            Map<UUID, List<UUID>> pendingRequesters) {
        List<UUID> requesters = pendingRequesters.get(id);
        if (requesters != null) {
            if (me != null && requesters.contains(me)) {
                return "needs_answer";
            }
            return "waiting_answer";
        }
        if (Boolean.TRUE.equals(waitingApproval.get(id))) {
            return actor.hasPermission(Permission.Codes.APPROVAL_DECIDE) ? "needs_approval" : "waiting_approval";
        }
        if (Boolean.TRUE.equals(activeGoal.get(id))) {
            return "working";
        }
        return "idle";
    }

    private static Map<UUID, Boolean> activityMap(List<Object[]> countRows) {
        Map<UUID, Boolean> present = new LinkedHashMap<>();
        for (Object[] row : countRows) {
            UUID conversationId = (UUID) row[0];
            long count = ((Number) row[1]).longValue();
            if (count > 0) {
                present.put(conversationId, true);
            }
        }
        return present;
    }

    static boolean canShare(Conversation conversation, Actor actor) {
        String human = actor.humanId();
        return (human != null && human.equals(conversation.getCreatedBy()))
                || actor.hasPermission(Permission.Codes.CHAT_READ_ALL);
    }

    static boolean canManage(Conversation conversation, Actor actor) {
        String human = actor.humanId();
        return (human != null && human.equals(conversation.getCreatedBy()))
                || actor.hasPermission(Permission.Codes.WORKSPACE_UPDATE);
    }

    /** The first matching message (id + snippet) per conversation, when there is a search term. */
    private Map<UUID, SearchMatch> matchesFor(List<Conversation> rows, String query) {
        if (query.isBlank() || rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = rows.stream().map(Conversation::getId).distinct().toList();
        String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
        Map<UUID, SearchMatch> matches = new LinkedHashMap<>();
        for (Object[] row : messages.matchesIn(ids, pattern, PageRequest.of(0, SEARCH_MATCH_BATCH))) {
            UUID conversationId = (UUID) row[0];
            UUID messageId = (UUID) row[1];
            String content = (String) row[2];
            matches.putIfAbsent(conversationId, new SearchMatch(messageId, snippetOf(content, query)));
        }
        return matches;
    }

    private static String snippetOf(String content, String query) {
        if (content == null) {
            return "";
        }
        int at = content.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (at < 0) {
            return CoordinatorService.truncateAtWord(content, SNIPPET_BEFORE + SNIPPET_AFTER);
        }
        int start = Math.max(0, at - SNIPPET_BEFORE);
        int end = Math.min(content.length(), at + query.length() + SNIPPET_AFTER);
        String middle = content.substring(start, end).strip();
        String prefix = start > 0 ? "…" : "";
        String suffix = end < content.length() ? "…" : "";
        return prefix + middle + suffix;
    }

    private static Set<UUID> orDummy(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(NONE) : ids;
    }

    // ---- Conversation detail ------------------------------------------------------------------

    /** The conversation's whole recent window: its newest messages, their goals, and its questions. */
    // @find: conversation detail, open a chat with messages
    @Transactional(readOnly = true)
    public ConversationDetail detail(UUID orgId, Actor actor, UUID id, int limit) {
        return detail(orgId, actor, id, limit, null, null);
    }

    /**
     * A conversation, whole or as what changed since a reader last looked.
     *
     * <p>With neither {@code after} nor {@code since} it is the conversation's newest {@code limit}
     * messages, the goals they and the open work refer to, and its questions. With both, it is only
     * what a reader holding everything up to message {@code after}, read at {@code since}, does not
     * have yet: the messages after that position, the goals that are still open or changed since
     * (with their tasks and latest runs), and the questions that are still open, changed since, or
     * belong to one of those goals. {@code generatedAt} is the moment to ask about next time, taken
     * from this server's clock so no reader's own clock matters. A reader merges what it gets by id,
     * which is also all it takes to read a whole response.
     *
     * <p>When more than {@link #DELTA_MESSAGE_LIMIT} messages arrived since {@code after}, the
     * change would not fit in a small response, so the whole window is returned instead and the
     * reader replaces what it holds.
     */
    // @find: conversation detail polling, messages after position or since time
    @Transactional(readOnly = true)
    public ConversationDetail detail(UUID orgId, Actor actor, UUID id, int limit, Integer after, Instant since) {
        Instant generatedAt = Instant.now();
        // Only a whole read is logged when chat:read_all is what allowed it: a thread open on screen
        // polls for changes every few seconds, and one entry per poll would drown the log.
        boolean whole = after == null || since == null;
        Conversation conversation = access == null
                ? conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id))
                : whole ? access.requireForRead(orgId, actor, id) : access.require(orgId, actor, id);
        if (after != null && since != null) {
            ConversationDetail changes = changesSince(orgId, actor, conversation, after, since, generatedAt);
            if (changes != null) {
                return withQueue(orgId, actor, conversation, changes);
            }
        }
        int effectiveLimit = Math.max(DETAIL_MIN_LIMIT, Math.min(DETAIL_MAX_LIMIT, limit));

        List<ChatMessage> fetched =
                messages.findByConversationIdOrderByPositionDesc(id, PageRequest.of(0, effectiveLimit + 1));
        boolean hasEarlier = fetched.size() > effectiveLimit;
        List<ChatMessage> kept = hasEarlier ? fetched.subList(0, effectiveLimit) : fetched;
        List<ChatMessage> ascending = new ArrayList<>(kept);
        java.util.Collections.reverse(ascending);
        List<ChatMessageView> messageViews =
                ascending.stream().map(ConversationQueries::toMessageView).toList();

        Set<UUID> goalIds = new LinkedHashSet<>();
        for (ChatMessage message : ascending) {
            if (message.getGoalId() != null) {
                goalIds.add(message.getGoalId());
            }
        }
        for (Goal goal : goals.findByOrgIdAndConversationIdAndStatusIn(orgId, id, GOAL_ACTIVE_STATUSES)) {
            goalIds.add(goal.getId());
        }
        List<BoardService.GoalView> goalViews = goalViews(orgId, goalIds);

        List<QuestionService.QuestionView> questionViews =
                questions.views(questions.forConversation(orgId, id, QUESTION_LIMIT), actor);

        return withQueue(
                orgId,
                actor,
                conversation,
                new ConversationDetail(
                        view(orgId, actor, conversation), messageViews, goalViews, questionViews, hasEarlier, generatedAt));
    }

    /** The detail with the conversation's queue and whether it is busy, read fresh every time. */
    private ConversationDetail withQueue(UUID orgId, Actor actor, Conversation conversation, ConversationDetail detail) {
        if (chatQueue == null) {
            return detail;
        }
        return detail.withQueue(
                chatQueue.views(conversation.getId(), conversation, actor),
                chatQueue.state(orgId, conversation).wire());
    }

    /**
     * What changed in a conversation since a reader looked, or null when too much did for a small
     * answer (see {@link #detail(UUID, Actor, UUID, int, Integer, Instant)}).
     */
    private ConversationDetail changesSince(
            UUID orgId, Actor actor, Conversation conversation, int after, Instant since, Instant generatedAt) {
        UUID id = conversation.getId();
        List<ChatMessage> newest =
                messages.findByConversationIdOrderByPositionDesc(id, PageRequest.of(0, DELTA_MESSAGE_LIMIT + 1));
        // Newest first, so these cover everything after `after` unless every one of them is newer
        // and the thread goes on beyond them.
        boolean covered = newest.size() <= DELTA_MESSAGE_LIMIT || newest.getLast().getPosition() <= after;
        if (!covered) {
            return null;
        }
        List<ChatMessage> fresh = new ArrayList<>(
                newest.stream().filter(message -> message.getPosition() > after).toList());
        java.util.Collections.reverse(fresh);
        Instant cut = since.minus(DELTA_OVERLAP);

        Set<UUID> goalIds = new LinkedHashSet<>();
        for (ChatMessage message : fresh) {
            if (message.getGoalId() != null) {
                goalIds.add(message.getGoalId());
            }
        }
        // Open goals every time: their steps, cost and status move without the goal's own row
        // changing. A finished goal is sent once, when its row last changed.
        for (Goal goal : goals.findByOrgIdAndConversationIdAndStatusIn(orgId, id, GOAL_ACTIVE_STATUSES)) {
            goalIds.add(goal.getId());
        }
        for (Goal goal : goals.findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(orgId, id, cut)) {
            goalIds.add(goal.getId());
        }
        List<BoardService.GoalView> goalViews = goalViews(orgId, goalIds);
        Set<UUID> sentGoalIds = new LinkedHashSet<>();
        goalViews.forEach(goal -> sentGoalIds.add(goal.id()));

        // A question is sent while it is open, once it changes, and whenever its goal is sent: its
        // run's status (the "still working on it" line) moves with the goal, not with the question.
        List<RunQuestion> changed = questions.forConversation(orgId, id, QUESTION_LIMIT).stream()
                .filter(question -> "pending".equals(question.getStatus())
                        || (question.getUpdatedAt() != null && !question.getUpdatedAt().isBefore(cut))
                        || (question.getGoalId() != null && sentGoalIds.contains(question.getGoalId())))
                .toList();

        // A delta carries no window, so it never claims there is anything earlier: the reader
        // keeps what it already knew about that.
        return new ConversationDetail(
                view(orgId, actor, conversation),
                fresh.stream().map(ConversationQueries::toMessageView).toList(),
                goalViews,
                questions.views(changed, actor),
                false,
                generatedAt);
    }

    /** The goals with these ids in this workspace, oldest first, each with its tasks and what its runs add up to. */
    private List<BoardService.GoalView> goalViews(UUID orgId, Set<UUID> goalIds) {
        if (goalIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Goal> goalById = new LinkedHashMap<>();
        for (Goal goal : goals.findAllById(goalIds)) {
            if (orgId.equals(goal.getOrgId())) {
                goalById.put(goal.getId(), goal);
            }
        }
        List<Task> allTasks =
                goalById.isEmpty() ? List.of() : tasks.findByGoalIdInOrderByPositionAsc(goalById.keySet());
        Map<UUID, List<Task>> tasksByGoal = allTasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Task::getGoalId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<UUID> taskIds = allTasks.stream().map(Task::getId).toList();
        Map<UUID, TaskRuns> runsByTask = GoalViews.runsByTask(runs, taskIds);
        return goalById.values().stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt))
                .map(goal -> GoalViews.goalView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), runsByTask))
                .toList();
    }

    // @find: messages page, load earlier messages
    @Transactional(readOnly = true)
    public MessagesPage messagesPage(UUID orgId, Actor actor, UUID id, int before, int limit) {
        if (access == null) {
            conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
        } else {
            access.require(orgId, actor, id);
        }
        int effectiveLimit = Math.max(MESSAGES_MIN_LIMIT, Math.min(MESSAGES_MAX_LIMIT, limit));
        List<ChatMessage> fetched = messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(
                id, before, PageRequest.of(0, effectiveLimit + 1));
        boolean hasEarlier = fetched.size() > effectiveLimit;
        List<ChatMessage> kept = hasEarlier ? fetched.subList(0, effectiveLimit) : fetched;
        List<ChatMessage> ascending = new ArrayList<>(kept);
        java.util.Collections.reverse(ascending);
        return new MessagesPage(
                ascending.stream().map(ConversationQueries::toMessageView).toList(), hasEarlier);
    }

    private static ChatMessageView toMessageView(ChatMessage message) {
        return new ChatMessageView(
                message.getId(),
                message.getPosition(),
                message.getAuthorKind(),
                message.getAuthorId(),
                message.getAgentId(),
                message.getKind(),
                message.getContent(),
                message.getDetail(),
                message.getGoalId(),
                message.getCreatedAt());
    }

    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
