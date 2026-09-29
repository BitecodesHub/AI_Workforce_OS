package os.aiworkforce.orchestrator.chat;

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
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.ConversationMark;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
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
            SearchMatch match) {}

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
            boolean hasEarlier) {}

    public record MessagesPage(List<ChatMessageView> messages, boolean hasEarlier) {}

    // ---- The conversation list ----------------------------------------------------------------

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
                    : conversations.searchAmong(orgId, archivedIds, false, actorId, pattern);
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
                    : conversations.searchAmong(orgId, pinnedIds, mine, actorId, pattern);

            Set<UUID> needsYouIds = new LinkedHashSet<>(questions.conversationsNeedingAnswerFrom(orgId, me));
            if (actor.hasPermission(Permission.Codes.APPROVAL_DECIDE)) {
                needsYouIds.addAll(tasks.conversationsWaitingForApproval(orgId));
            }
            needsYouIds.removeAll(pinnedIds);
            needsYouRows = needsYouIds.isEmpty()
                    ? List.of()
                    : conversations.searchAmong(orgId, needsYouIds, false, actorId, pattern);
        }

        Set<UUID> excluded = new LinkedHashSet<>(pinnedIds);
        excluded.addAll(archivedIds);
        List<Conversation> mainRowsFetched = conversations.searchExcluding(
                orgId, orDummy(excluded), mine, actorId, pattern, PageRequest.of(effectivePage, effectiveSize + 1));
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
                match);
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

    @Transactional(readOnly = true)
    public ConversationDetail detail(UUID orgId, Actor actor, UUID id, int limit) {
        Conversation conversation =
                conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
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
        Map<UUID, Goal> goalById = new LinkedHashMap<>();
        if (!goalIds.isEmpty()) {
            for (Goal goal : goals.findAllById(goalIds)) {
                if (orgId.equals(goal.getOrgId())) {
                    goalById.put(goal.getId(), goal);
                }
            }
        }
        List<Task> allTasks =
                goalById.isEmpty() ? List.of() : tasks.findByGoalIdInOrderByPositionAsc(goalById.keySet());
        Map<UUID, List<Task>> tasksByGoal = allTasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Task::getGoalId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<UUID> taskIds = allTasks.stream().map(Task::getId).toList();
        Map<UUID, Run> latestRunByTask = GoalViews.latestRunByTask(runs, taskIds);
        List<BoardService.GoalView> goalViews = goalById.values().stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt))
                .map(goal ->
                        GoalViews.goalView(goal, tasksByGoal.getOrDefault(goal.getId(), List.of()), latestRunByTask))
                .toList();

        List<QuestionService.QuestionView> questionViews =
                questions.views(questions.forConversation(orgId, id, 100), actor);

        return new ConversationDetail(
                view(orgId, actor, conversation), messageViews, goalViews, questionViews, hasEarlier);
    }

    @Transactional(readOnly = true)
    public MessagesPage messagesPage(UUID orgId, UUID id, int before, int limit) {
        conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
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
