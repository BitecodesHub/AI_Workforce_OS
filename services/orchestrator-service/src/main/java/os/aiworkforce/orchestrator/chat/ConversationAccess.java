// @find: conversation access, who can read a conversation, private chat, workspace chat, chat:read_all, conversation members, hidden conversations, ConversationAccess, permission check chat, participants
// @what: Decides who may read or change a conversation and which goals, tasks and conversations are hidden from a person.
// @flow: Called by ChatController services, AttachmentService, FeedbackController and board code.
package os.aiworkforce.orchestrator.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ConversationParticipants;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Who may read a conversation.
 *
 * <p>A {@code workspace} conversation is read by everyone with Chat access. A {@code private} one
 * is read by the person who started it, by the people they added, and by anybody holding {@code
 * chat:read_all}, which is given to owners only and is logged every time it is the reason a read
 * was allowed.
 *
 * <p>Everybody else is told the conversation does not exist, never that it is forbidden: a 403
 * would confirm that a private thread with that id is there.
 *
 * <p>The same rule hides the work a private thread started. {@link #hiddenGoalIds} and {@link
 * #hiddenTaskIds} answer "which goals and tasks must this person not see" in the database, so a
 * list can leave them out without loading them first. Work from a private thread that was deleted
 * keeps its id and stays hidden from everyone but holders of {@code chat:read_all} ({@link
 * #GONE_CONVERSATION}).
 */
@Component
public class ConversationAccess {

    /** A private conversation this person is not part of, as the three tables that carry its work see it. */
    private static final String HIDDEN_CONVERSATIONS =
            """
            select c.id from conversations c
            where c.org_id = ? and c.visibility = 'private'
              and coalesce(c.created_by, '') <> ?
              and not exists (select 1 from conversation_participants p
                              where p.conversation_id = c.id and p.user_id = ?)
            """;

    /**
     * Whether the conversation the column in {@code %s} names no longer exists. Deleting a private
     * conversation leaves its goals and questions holding its id (ConversationAdmin.delete), and
     * that work stays hidden from everyone without chat:read_all, the people who were in it
     * included: there is no longer a conversation to say who they were. A deleted workspace
     * conversation's work is let go of it (the id is cleared), so it is never caught here.
     */
    static final String GONE_CONVERSATION =
            "not exists (select 1 from conversations gc where gc.id = %s)";

    /**
     * The ids of deleted private conversations whose goals or questions are still in the workspace,
     * so a list that filters by conversation id leaves their work out too.
     */
    private static final String GONE_CONVERSATIONS =
            """
            select g.conversation_id from goals g
            where g.org_id = ? and g.conversation_id is not null and %1$s
            union
            select q.conversation_id from run_questions q
            where q.org_id = ? and q.conversation_id is not null and %2$s
            """
                    .formatted(GONE_CONVERSATION.formatted("g.conversation_id"),
                            GONE_CONVERSATION.formatted("q.conversation_id"));

    /** Looks up conversations of a workspace. */
    private final Conversations conversations;
    /** The people added to private conversations. */
    private final ConversationParticipants participants;
    /** Used for the set-based queries that find hidden conversations, goals and tasks. */
    private final JdbcTemplate jdbc;
    /** Receives the entry written when {@code chat:read_all} is the only reason a read is allowed. */
    private final AuditClient audit;

    /**
     * Creates the access rules over the conversation and participant repositories, JDBC and the audit client.
     */
    public ConversationAccess(
            Conversations conversations,
            ConversationParticipants participants,
            JdbcTemplate jdbc,
            AuditClient audit) {
        this.conversations = conversations;
        this.participants = participants;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * The conversation, when this person may read it; otherwise "not found".
     *
     * <p>Loads the conversation within the workspace and applies {@link #canRead}. A missing
     * conversation and one the person may not read give the same {@code not found} error, so a
     * private thread is never confirmed to exist. Read only; used by the chat controllers and by
     * {@code AttachmentService} and {@code FeedbackController}.
     */
    // @find: require access to conversation, check chat permission
    public Conversation require(UUID orgId, Actor actor, UUID id) {
        Conversation conversation = conversations
                .findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", id));
        if (!canRead(conversation, actor)) {
            throw ApiException.notFound("conversation", id);
        }
        return conversation;
    }

    /**
     * Like {@link #require}, for a read that should be logged when {@code chat:read_all} is the only
     * reason it is allowed.
     *
     * <p>Side effect: writes a {@code chat.read_private} audit entry naming the thread's creator
     * when the conversation is private and the actor is not a member. Failures are those of
     * {@link #require}.
     */
    // @find: require read access to conversation
    public Conversation requireForRead(UUID orgId, Actor actor, UUID id) {
        Conversation conversation = require(orgId, actor, id);
        if (conversation.isPrivate() && !isMember(conversation, actor)) {
            audit.record(
                    orgId,
                    actor,
                    "chat.read_private",
                    "conversation",
                    id.toString(),
                    "succeeded",
                    Map.of("createdBy", String.valueOf(conversation.getCreatedBy())));
        }
        return conversation;
    }

    /**
     * Whether the person may read the conversation: it is a workspace one, they are a member, or they hold {@code chat:read_all}. Pure.
     */
    public boolean canRead(Conversation conversation, Actor actor) {
        return !conversation.isPrivate() || isMember(conversation, actor) || canReadAll(actor);
    }

    /**
     * The creator, or somebody they added.
     *
     * <p>An actor with no person id, such as a service, is never a member.
     */
    public boolean isMember(Conversation conversation, Actor actor) {
        String me = actor.humanId();
        if (me == null) {
            return false;
        }
        return me.equals(conversation.getCreatedBy())
                || participants.existsByConversationIdAndUserId(conversation.getId(), me);
    }

    /** Whether the actor holds the {@code chat:read_all} permission. */
    public boolean canReadAll(Actor actor) {
        return actor.hasPermission(Permission.Codes.CHAT_READ_ALL);
    }

    /**
     * Only the person who started a conversation, or a holder of chat:read_all, changes who may read it.
     *
     * <p>Applies {@link #require} first, so an unreadable conversation is still "not found". A
     * reader who is neither the creator nor a holder of {@code chat:read_all} is refused with
     * {@code PERMISSION_DENIED}.
     */
    // @find: require conversation owner, only owner can change
    public Conversation requireOwner(UUID orgId, Actor actor, UUID id) {
        Conversation conversation = require(orgId, actor, id);
        boolean creator = actor.humanId() != null && actor.humanId().equals(conversation.getCreatedBy());
        if (!creator && !canReadAll(actor)) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.PERMISSION_DENIED,
                    "Only the person who started this conversation can change who it is shared with.");
        }
        return conversation;
    }

    /** Whether a goal ({@code g}) came from a conversation hidden from the person, or from a deleted private one. */
    private static final String HIDDEN_GOAL =
            "g.conversation_id is not null and (g.conversation_id in (" + HIDDEN_CONVERSATIONS + ") or "
                    + GONE_CONVERSATION.formatted("g.conversation_id") + ")";

    /**
     * Goals started from a private conversation this person is not part of, or from a private
     * conversation that has since been deleted. Empty for a holder of chat:read_all.
     *
     * <p>One read-only query; lists use the result to leave those goals out.
     */
    // @find: hidden goal ids, goals in private chats, goals of a deleted private chat
    public Set<UUID> hiddenGoalIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(
                "select g.id from goals g where g.org_id = ? and " + HIDDEN_GOAL,
                UUID.class,
                orgId,
                orgId,
                me,
                me));
    }

    /**
     * Tasks of those goals.
     *
     * <p>As {@link #hiddenGoalIds}, one read-only query joined through the goals.
     */
    // @find: hidden task ids, tasks in private chats
    public Set<UUID> hiddenTaskIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(
                "select t.id from tasks t join goals g on g.id = t.goal_id where g.org_id = ? and " + HIDDEN_GOAL,
                UUID.class,
                orgId,
                orgId,
                me,
                me));
    }

    /**
     * Whether work from this conversation is out of this person's sight.
     *
     * <p>False when there is no conversation; the opposite of {@link #canRead} when it exists. A
     * conversation id that names no conversation is one that was deleted while private (a deleted
     * workspace one's work has its id cleared), so its work is hidden from everyone but holders of
     * chat:read_all.
     */
    public boolean hidden(UUID orgId, Actor actor, UUID conversationId) {
        if (conversationId == null) {
            return false;
        }
        return conversations
                .findByIdAndOrgId(conversationId, orgId)
                .map(conversation -> !canRead(conversation, actor))
                .orElseGet(() -> !canReadAll(actor));
    }

    /**
     * Conversation ids this person may not read, for filtering a list of conversation ids: the
     * private ones they are not part of, and the deleted private ones whose goals or questions are
     * still in the workspace.
     *
     * <p>Empty for a holder of {@code chat:read_all}; otherwise one read-only query.
     */
    // @find: hidden conversation ids, private chats hidden from a person, deleted private chats
    public Set<UUID> hiddenConversationIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(
                HIDDEN_CONVERSATIONS + " union " + GONE_CONVERSATIONS, UUID.class, orgId, me, me, orgId, orgId));
    }

    /** People added to a conversation, for the share dialog. */
    public Map<String, String> participantsOf(UUID conversationId) {
        return participants.findByConversationId(conversationId).stream()
                .collect(Collectors.toMap(
                        p -> p.getUserId(), p -> p.getAddedBy(), (a, b) -> a, LinkedHashMap::new));
    }

    /** The actor's person id, or an empty string that matches no one. */
    private static String meOrNone(Actor actor) {
        // A person with no id is matched by nothing, so every private thread is hidden from them.
        return actor.humanId() == null ? "" : actor.humanId();
    }
}
