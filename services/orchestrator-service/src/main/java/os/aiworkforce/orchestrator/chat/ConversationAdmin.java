package os.aiworkforce.orchestrator.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.ConversationMarks;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Renames, pins, archives, marks read and deletes a conversation.
 *
 * <p>The conversation list is shared by the whole workspace, so these actions carry two different
 * kinds of authority. Pinning, archiving and reading are entirely personal - held in {@code
 * conversation_marks} for whoever asked, and refused only to a caller with no person behind them
 * at all. Renaming and deleting change the shared row itself, so they are open to the person who
 * started the conversation, or to an administrator; deleting also stops active work, and only when
 * the caller is allowed to stop it.
 */
@Service
public class ConversationAdmin {

    static final String DELETED_REASON = "The conversation was deleted.";
    private static final List<String> ACTIVE_GOAL_STATUSES = List.of("planning", "running", "waiting");

    private final Conversations conversations;
    private final ConversationMarks marks;
    private final Goals goals;
    private final GoalService goalService;
    private final QuestionService questions;
    private final ChatAppender appender;
    private final ConversationQueries queries;
    private final AuditClient audit;

    /** Who may read which conversation; absent only where a test builds this service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationAccess access;

    private Conversation visible(UUID orgId, Actor actor, UUID id) {
        if (access != null) {
            return access.require(orgId, actor, id);
        }
        return conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
    }

    public ConversationAdmin(
            Conversations conversations,
            ConversationMarks marks,
            Goals goals,
            GoalService goalService,
            QuestionService questions,
            ChatAppender appender,
            ConversationQueries queries,
            AuditClient audit) {
        this.conversations = conversations;
        this.marks = marks;
        this.goals = goals;
        this.goalService = goalService;
        this.questions = questions;
        this.appender = appender;
        this.queries = queries;
        this.audit = audit;
    }

    @Transactional
    public ConversationQueries.ConversationView rename(UUID orgId, Actor actor, UUID id, String title) {
        Conversation conversation =
                visible(orgId, actor, id);
        requireCanManage(conversation, actor, "rename");
        Conversation locked = appender.lock(orgId, id).orElseThrow(() -> ApiException.notFound("conversation", id));
        locked.setTitle(title == null ? "" : title.strip());
        conversations.save(locked);
        return queries.view(orgId, actor, locked);
    }

    @Transactional
    public void pin(UUID orgId, Actor actor, UUID id) {
        upsertFlags(orgId, actor, id, true, false, "pin");
    }

    @Transactional
    public void unpin(UUID orgId, Actor actor, UUID id) {
        upsertFlags(orgId, actor, id, false, null, "unpin");
    }

    @Transactional
    public void archive(UUID orgId, Actor actor, UUID id) {
        upsertFlags(orgId, actor, id, false, true, "archive");
    }

    @Transactional
    public void unarchive(UUID orgId, Actor actor, UUID id) {
        upsertFlags(orgId, actor, id, null, false, "unarchive");
    }

    private void upsertFlags(UUID orgId, Actor actor, UUID id, Boolean pinned, Boolean archived, String action) {
        visible(orgId, actor, id);
        UUID me = parseUuidOrNull(actor.humanId());
        if (me == null) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "Only a signed-in person can " + action + " a conversation.");
        }
        marks.upsertFlags(UuidV7.generate(), orgId, id, me, pinned, archived);
    }

    // ---- Who may read a conversation ------------------------------------------------------------

    /** The visibility of a new conversation in this workspace: its own setting, or private. */
    static final os.aiworkforce.platform.runtimeconfig.ConfigKey DEFAULT_VISIBILITY =
            os.aiworkforce.platform.runtimeconfig.ConfigKey.choice(
                    "chat.defaultVisibility",
                    os.aiworkforce.platform.runtimeconfig.ConfigKey.Scope.WORKSPACE,
                    "private",
                    List.of("private", "workspace"),
                    "Who can read a new conversation: private (the person who started it and people they add) or"
                            + " workspace (everyone with Chat access).");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.platform.runtimeconfig.RuntimeConfigService runtimeConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.orchestrator.repository.ConversationParticipants participants;

    /** What a new conversation starts as. Private when the setting cannot be read: the safer of the two. */
    public String defaultVisibility(UUID orgId) {
        if (runtimeConfig == null) {
            return "private";
        }
        try {
            runtimeConfig.register(List.of(DEFAULT_VISIBILITY));
            return "workspace".equals(runtimeConfig.getString(DEFAULT_VISIBILITY, orgId.toString()))
                    ? "workspace"
                    : "private";
        } catch (RuntimeException unreadable) {
            return "private";
        }
    }

    /** Opens a conversation to the workspace, or makes it private again. */
    @Transactional
    public ConversationQueries.ConversationView setVisibility(UUID orgId, Actor actor, UUID id, String visibility) {
        if (!"private".equals(visibility) && !"workspace".equals(visibility)) {
            throw ApiException.validation("visibility", "must be private or workspace");
        }
        Conversation conversation = access.requireOwner(orgId, actor, id);
        Conversation locked = appender.lock(orgId, id).orElseThrow(() -> ApiException.notFound("conversation", id));
        String before = locked.getVisibility();
        locked.setVisibility(visibility);
        conversations.save(locked);
        if (!before.equals(visibility)) {
            audit.record(
                    orgId,
                    actor,
                    "private".equals(visibility) ? "conversation.make_private" : "conversation.share",
                    "conversation",
                    id.toString(),
                    "succeeded",
                    java.util.Map.of("from", before, "to", visibility));
        }
        return queries.view(orgId, actor, locked);
    }

    /** People who may read a private conversation besides its creator. */
    @Transactional(readOnly = true)
    public List<String> participantIds(UUID orgId, Actor actor, UUID id) {
        access.require(orgId, actor, id);
        return participants.findByConversationId(id).stream()
                .map(os.aiworkforce.orchestrator.domain.ConversationParticipant::getUserId)
                .toList();
    }

    /** Adds people to a conversation. Already-present people are left alone. */
    @Transactional
    public List<String> addParticipants(UUID orgId, Actor actor, UUID id, List<String> userIds) {
        access.requireOwner(orgId, actor, id);
        List<String> added = new java.util.ArrayList<>();
        for (String userId : userIds == null ? List.<String>of() : userIds) {
            if (userId == null || userId.isBlank() || parseUuidOrNull(userId) == null) {
                throw ApiException.validation("userIds", "each entry must be a person's id");
            }
            if (!participants.existsByConversationIdAndUserId(id, userId)) {
                participants.save(new os.aiworkforce.orchestrator.domain.ConversationParticipant(
                        id, userId, String.valueOf(actor.humanId())));
                added.add(userId);
            }
        }
        if (!added.isEmpty()) {
            audit.record(
                    orgId,
                    actor,
                    "conversation.add_people",
                    "conversation",
                    id.toString(),
                    "succeeded",
                    java.util.Map.of("added", added));
        }
        return participantIds(orgId, actor, id);
    }

    @Transactional
    public void removeParticipant(UUID orgId, Actor actor, UUID id, String userId) {
        access.requireOwner(orgId, actor, id);
        participants.findById(new os.aiworkforce.orchestrator.domain.ConversationParticipant.Key(id, userId))
                .ifPresent(participants::delete);
    }


    /** A caller with no person behind them gets a quiet no-op, not an error - nothing to mark read for them. */
    @Transactional
    public void markRead(UUID orgId, Actor actor, UUID id, int position) {
        Conversation conversation =
                visible(orgId, actor, id);
        UUID me = parseUuidOrNull(actor.humanId());
        if (me == null) {
            return;
        }
        int clamped = Math.min(Math.max(position, 0), Math.max(0, conversation.getMessageCount() - 1));
        marks.upsertRead(UuidV7.generate(), orgId, id, me, clamped);
    }

    /**
     * Deletes a conversation, stopping its active work first - but only when the caller may stop
     * every bit of it. Nothing is stopped or deleted when somebody else's work is still running and
     * the caller cannot cancel work in general (D-9).
     */
    @Transactional
    public void delete(UUID orgId, Actor actor, UUID id) {
        Conversation conversation =
                visible(orgId, actor, id);
        requireCanManage(conversation, actor, "delete");

        List<Goal> active = goals.findByOrgIdAndConversationIdAndStatusIn(orgId, id, ACTIVE_GOAL_STATUSES);
        boolean canCancelOthers = actor.hasPermission(Permission.Codes.TASK_CANCEL);
        String human = actor.humanId();
        for (Goal goal : active) {
            boolean mine = goal.getRequestedBy() != null
                    && human != null
                    && goal.getRequestedBy().toString().equals(human);
            if (!mine && !canCancelOthers) {
                throw new ApiException(
                        ErrorCode.CONFLICT,
                        "Work someone else asked for is still running in "
                                + "this conversation. Ask them, or a manager, to stop it first.");
            }
        }
        for (Goal goal : active) {
            goalService.cancelIfActive(orgId, goal.getId(), DELETED_REASON);
        }

        goals.detachConversation(orgId, id);
        questions.detachConversation(orgId, id);
        // Read fresh, under a lock, rather than reusing the copy loaded above - the cancels just
        // above may already have appended notices that bumped its version, and saving the stale
        // copy over that would fail its own optimistic-lock check.
        Conversation locked = appender.lock(orgId, id).orElse(null);
        if (locked == null) {
            return;
        }
        String title = locked.getTitle();
        conversations.delete(locked);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("title", title);
        LifecycleAnnouncer.afterCommit(() ->
                audit.record(orgId, actor, "conversation.delete", "conversation", id.toString(), "succeeded", detail));
    }

    private static void requireCanManage(Conversation conversation, Actor actor, String action) {
        if (!ConversationQueries.canManage(conversation, actor)) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "Only the person who started this conversation, or an administrator, can " + action
                                    + " it.")
                    .with("requiredPermission", Permission.Codes.WORKSPACE_UPDATE);
        }
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
