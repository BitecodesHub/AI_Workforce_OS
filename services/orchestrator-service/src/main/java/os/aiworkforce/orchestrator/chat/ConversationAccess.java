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
 * list can leave them out without loading them first.
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

    private final Conversations conversations;
    private final ConversationParticipants participants;
    private final JdbcTemplate jdbc;
    private final AuditClient audit;

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

    /** The conversation, when this person may read it; otherwise "not found". */
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
     */
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

    public boolean canRead(Conversation conversation, Actor actor) {
        return !conversation.isPrivate() || isMember(conversation, actor) || canReadAll(actor);
    }

    /** The creator, or somebody they added. */
    public boolean isMember(Conversation conversation, Actor actor) {
        String me = actor.humanId();
        if (me == null) {
            return false;
        }
        return me.equals(conversation.getCreatedBy())
                || participants.existsByConversationIdAndUserId(conversation.getId(), me);
    }

    public boolean canReadAll(Actor actor) {
        return actor.hasPermission(Permission.Codes.CHAT_READ_ALL);
    }

    /** Only the person who started a conversation, or a holder of chat:read_all, changes who may read it. */
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

    /** Goals started from a private conversation this person is not part of. Empty for a holder of chat:read_all. */
    public Set<UUID> hiddenGoalIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(
                "select g.id from goals g where g.org_id = ? and g.conversation_id in (" + HIDDEN_CONVERSATIONS + ")",
                UUID.class,
                orgId,
                orgId,
                me,
                me));
    }

    /** Tasks of those goals. */
    public Set<UUID> hiddenTaskIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(
                "select t.id from tasks t join goals g on g.id = t.goal_id where g.org_id = ?"
                        + " and g.conversation_id in (" + HIDDEN_CONVERSATIONS + ")",
                UUID.class,
                orgId,
                orgId,
                me,
                me));
    }

    /** Whether work from this conversation is out of this person's sight. */
    public boolean hidden(UUID orgId, Actor actor, UUID conversationId) {
        if (conversationId == null) {
            return false;
        }
        return conversations
                .findByIdAndOrgId(conversationId, orgId)
                .map(conversation -> !canRead(conversation, actor))
                .orElse(false);
    }

    /** Conversation ids this person may not read, for filtering a list of conversation ids. */
    public Set<UUID> hiddenConversationIds(UUID orgId, Actor actor) {
        if (canReadAll(actor)) {
            return Set.of();
        }
        String me = meOrNone(actor);
        return Set.copyOf(jdbc.queryForList(HIDDEN_CONVERSATIONS, UUID.class, orgId, me, me));
    }

    /** People added to a conversation, for the share dialog. */
    public Map<String, String> participantsOf(UUID conversationId) {
        return participants.findByConversationId(conversationId).stream()
                .collect(Collectors.toMap(
                        p -> p.getUserId(), p -> p.getAddedBy(), (a, b) -> a, LinkedHashMap::new));
    }

    private static String meOrNone(Actor actor) {
        // A person with no id is matched by nothing, so every private thread is hidden from them.
        return actor.humanId() == null ? "" : actor.humanId();
    }
}
