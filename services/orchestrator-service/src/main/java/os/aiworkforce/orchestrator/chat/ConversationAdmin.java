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
                conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
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
        conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
        UUID me = parseUuidOrNull(actor.humanId());
        if (me == null) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "Only a signed-in person can " + action + " a conversation.");
        }
        marks.upsertFlags(UuidV7.generate(), orgId, id, me, pinned, archived);
    }

    /** A caller with no person behind them gets a quiet no-op, not an error - nothing to mark read for them. */
    @Transactional
    public void markRead(UUID orgId, Actor actor, UUID id, int position) {
        Conversation conversation =
                conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
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
                conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
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
