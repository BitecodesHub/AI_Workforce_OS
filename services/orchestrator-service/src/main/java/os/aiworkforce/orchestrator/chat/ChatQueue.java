// @find: chat queue, one answer at a time, conversation busy, queued messages, edit queued message, cancel queued message, waiting message, ChatQueue, Busy state, start next message
// @what: Tracks whether a conversation is busy and holds the messages waiting for it to finish, oldest first.
// @flow: Called by CoordinatorService, ChatQueueRunner and ChatController.
package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.ChatQueuedMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.ChatQueuedMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * One answer at a time per conversation: whether a conversation has work in progress, and the
 * messages waiting for it to finish.
 *
 * <p>A conversation is busy while a message's routing is still being decided (before any goal
 * exists), while a queued message is being started by "Start now anyway", and while any goal it
 * started is planning, running or parked waiting for a decision. A message sent while it is busy is
 * stored here, never answered alongside, and starts by itself - oldest first - when the work ends.
 * Work parked for an approval or an answer does not release the queue: the person decides, or
 * starts the queued message anyway, which stops the parked work.
 */
@Component
public class ChatQueue {

    /** A routing decision that has not finished in this long is taken to have died with its server. */
    static final Duration DECIDING_STALE = Duration.ofMinutes(3);

    /** A queued message not started in this long expires: it is shown, and never started. */
    static final Duration EXPIRY = Duration.ofHours(24);

    /** A "Start now anyway" claim that has not finished in this long goes back in the queue. */
    static final Duration STARTING_STALE = Duration.ofMinutes(2);

    /** The goal statuses that count as work in progress. */
    static final List<String> ACTIVE_GOAL_STATUSES = List.of("planning", "running", "waiting");
    private static final Set<String> PARKED = Set.of("waiting_approval", "waiting_input");
    private static final Set<String> MOVING = Set.of("running", "claimed");

    /** Whether a conversation has work in progress, as the console shows it; {@link #wire} is the text sent to the client. */
    public enum Busy {
        IDLE("idle"),
        WORKING("working"),
        WAITING_DECISION("waiting_decision");

        private final String wire;

        Busy(String wire) {
            this.wire = wire;
        }

        /** The lower-case text the client receives. */
        public String wire() {
            return wire;
        }
    }

    /**
     * A waiting message as the console shows it.
     *
     * @param order its place in the queue, from 1
     * @param canManage whether the reader may edit or cancel it: its sender, or the conversation's owner
     */
    public record QueuedMessageView(
            UUID id,
            int order,
            UUID authorId,
            String text,
            List<UUID> agentIds,
            int attachmentCount,
            Instant createdAt,
            Instant updatedAt,
            String status,
            boolean canManage) {}

    private final ChatQueuedMessages queue;
    private final Goals goals;
    private final Tasks tasks;
    private final Conversations conversations;

    /** Wires the queue, goal, task and conversation repositories. */
    public ChatQueue(ChatQueuedMessages queue, Goals goals, Tasks tasks, Conversations conversations) {
        this.queue = queue;
        this.goals = goals;
        this.tasks = tasks;
        this.conversations = conversations;
    }

    /**
     * What the conversation is doing right now.
     *
     * <p>Working while a routing decision began less than {@link #DECIDING_STALE} ago or a "Start
     * now" claim began less than {@link #STARTING_STALE} ago; otherwise it looks at the goals still
     * in progress: waiting for a decision when at least one task is parked for an approval or an
     * answer and none is running or claimed, working in every other case, idle when no goal is
     * active. Read-only; called by CoordinatorService, {@link ConversationQueries} and the runner.
     */
    // @find: is conversation busy, busy state, work in progress
    public Busy state(UUID orgId, Conversation conversation) {
        Instant now = Instant.now();
        if (conversation.getDecidingSince() != null
                && conversation.getDecidingSince().isAfter(now.minus(DECIDING_STALE))) {
            return Busy.WORKING;
        }
        List<ChatQueuedMessage> waiting = queue.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        if (waiting != null
                && waiting.stream()
                        .anyMatch(q -> ChatQueuedMessage.STARTING.equals(q.getStatus())
                                && q.getUpdatedAt().isAfter(now.minus(STARTING_STALE)))) {
            return Busy.WORKING;
        }
        List<Goal> active =
                goals.findByOrgIdAndConversationIdAndStatusIn(orgId, conversation.getId(), ACTIVE_GOAL_STATUSES);
        if (active == null || active.isEmpty()) {
            return Busy.IDLE;
        }
        List<Task> all = tasks.findByGoalIdInOrderByPositionAsc(
                active.stream().map(Goal::getId).toList());
        boolean parked = all != null && all.stream().anyMatch(t -> PARKED.contains(t.getStatus()));
        boolean moving = all != null && all.stream().anyMatch(t -> MOVING.contains(t.getStatus()));
        return parked && !moving ? Busy.WAITING_DECISION : Busy.WORKING;
    }

    /** Whether the conversation has any work in progress; a convenience over {@link #state}. */
    public boolean busy(UUID orgId, Conversation conversation) {
        return state(orgId, conversation) != Busy.IDLE;
    }

    /** The goals still in progress in a conversation. */
    public List<Goal> activeGoals(UUID orgId, UUID conversationId) {
        List<Goal> active = goals.findByOrgIdAndConversationIdAndStatusIn(orgId, conversationId, ACTIVE_GOAL_STATUSES);
        return active == null ? List.of() : active;
    }

    /** The oldest message waiting to start, if any; expired messages and those already starting are skipped. Read-only. */
    // @find: next queued message, oldest waiting message
    public java.util.Optional<ChatQueuedMessage> next(UUID conversationId) {
        return queue.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(q -> ChatQueuedMessage.QUEUED.equals(q.getStatus()))
                .filter(q -> q.getCreatedAt().isAfter(Instant.now().minus(EXPIRY)))
                .findFirst();
    }

    /**
     * The conversation's waiting messages as the console shows them, oldest first and numbered from 1.
     * A message queued for longer than {@link #EXPIRY} is shown as expired without waiting for the
     * sweep. Called by {@link ConversationQueries} and {@link ChatController}. Read-only.
     *
     * @param reader decides each row's {@code canManage} flag
     */
    // @find: queued message views, waiting messages shown in chat
    public List<QueuedMessageView> views(UUID conversationId, Conversation conversation, Actor reader) {
        List<ChatQueuedMessage> all = queue.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        Instant cut = Instant.now().minus(EXPIRY);
        List<QueuedMessageView> views = new ArrayList<>();
        int order = 0;
        for (ChatQueuedMessage q : all) {
            // Shown as expired as soon as it is, without waiting for the sweep to write it.
            String status = ChatQueuedMessage.QUEUED.equals(q.getStatus()) && q.getCreatedAt().isBefore(cut)
                    ? ChatQueuedMessage.EXPIRED
                    : q.getStatus();
            views.add(new QueuedMessageView(
                    q.getId(),
                    ++order,
                    q.getAuthorId(),
                    q.getText(),
                    q.getAgentIds(),
                    q.getAttachmentIds().size(),
                    q.getCreatedAt(),
                    q.getUpdatedAt(),
                    status,
                    canManage(q, conversation, reader)));
        }
        return views;
    }

    /** The view of one waiting message, with its real place in the queue; a stand-in at place 1 when it is no longer listed. Read-only. */
    public QueuedMessageView view(ChatQueuedMessage q, Conversation conversation, Actor reader) {
        return views(q.getConversationId(), conversation, reader).stream()
                .filter(v -> v.id().equals(q.getId()))
                .findFirst()
                .orElseGet(() -> new QueuedMessageView(
                        q.getId(),
                        1,
                        q.getAuthorId(),
                        q.getText(),
                        q.getAgentIds(),
                        q.getAttachmentIds().size(),
                        q.getCreatedAt(),
                        q.getUpdatedAt(),
                        q.getStatus(),
                        canManage(q, conversation, reader)));
    }

    /** Its sender may change or cancel it, and so may whoever owns the conversation; false for an unknown reader. */
    static boolean canManage(ChatQueuedMessage q, Conversation conversation, Actor reader) {
        if (reader == null) {
            return false;
        }
        String human = reader.humanId();
        if (human == null) {
            return false;
        }
        if (q.getAuthorId() != null && q.getAuthorId().toString().equals(human)) {
            return true;
        }
        return conversation != null && human.equals(conversation.getCreatedBy());
    }

    // ---- Changing a waiting message -------------------------------------------------------

    /**
     * Changes the text of a waiting message.
     *
     * <p>Flow: loads it and checks the caller may manage it, refuses a message that is no longer
     * queued, has started or has expired (conflict), refuses an empty text when it has no files
     * (validation), then saves. Called by {@link ChatController#editQueued}. Writes one queue row.
     */
    // @find: edit queued message, change waiting message text
    @Transactional
    public QueuedMessageView edit(UUID orgId, UUID conversationId, UUID queuedId, String text, Actor actor) {
        ChatQueuedMessage q = require(orgId, conversationId, queuedId, actor);
        if (!ChatQueuedMessage.QUEUED.equals(q.getStatus())
                || q.getCreatedAt().isBefore(Instant.now().minus(EXPIRY))) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    ChatQueuedMessage.STARTING.equals(q.getStatus())
                            ? "This message is already starting, so it can no longer be changed."
                            : "This message expired. Send it again instead.");
        }
        if ((text == null || text.isBlank()) && q.getAttachmentIds().isEmpty()) {
            throw ApiException.validation("text", "Write a message, or cancel it instead.");
        }
        q.setText(text == null ? "" : text);
        queue.save(q);
        return view(q, conversation(orgId, conversationId), actor);
    }

    /**
     * Removes a waiting message. The caller must be able to manage it; a message that is already
     * starting cannot be cancelled (conflict). Deletes one queue row. Called by {@link ChatController#cancelQueued}.
     */
    // @find: cancel queued message, remove waiting message
    @Transactional
    public void cancel(UUID orgId, UUID conversationId, UUID queuedId, Actor actor) {
        ChatQueuedMessage q = require(orgId, conversationId, queuedId, actor);
        if (ChatQueuedMessage.STARTING.equals(q.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "This message is already starting, so it cannot be cancelled.");
        }
        queue.delete(q);
    }

    /**
     * Expires messages that waited too long and returns stale "Start now" claims to the queue.
     * Updates queue rows only; called periodically by {@link ChatQueueRunner}.
     */
    // @find: tidy queue, remove stale queued messages
    @Transactional
    public void tidy() {
        Instant now = Instant.now();
        queue.expireOlderThan(now.minus(EXPIRY), now);
        queue.releaseStaleStarts(now.minus(STARTING_STALE), now);
    }

    /** Every conversation with a message waiting to start, as [orgId, conversationId], each listed once. Read-only; called by {@link ChatQueueRunner}. */
    // @find: waiting conversations, conversations with queued messages
    @Transactional(readOnly = true)
    public List<UUID[]> waitingConversations() {
        List<UUID[]> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Object[] row : queue.conversationsWaiting()) {
            UUID conversation = (UUID) row[1];
            if (seen.add(conversation)) {
                out.add(new UUID[] {(UUID) row[0], conversation});
            }
        }
        return out;
    }

    /** The queued message, when it exists in this conversation and the caller may manage it; not found or permission denied otherwise. */
    ChatQueuedMessage require(UUID orgId, UUID conversationId, UUID queuedId, Actor actor) {
        ChatQueuedMessage q = queue.findByIdAndOrgIdAndConversationId(queuedId, orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("queued message", queuedId));
        if (!canManage(q, conversation(orgId, conversationId), actor)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only the person who sent this message can change it.");
        }
        return q;
    }

    /** The conversation row, or null when it is gone. */
    private Conversation conversation(UUID orgId, UUID conversationId) {
        return conversations.findByIdAndOrgId(conversationId, orgId).orElse(null);
    }

    // ---- The sender, kept with the message ------------------------------------------------

    /** Who sent a message, as needed to start it later with the same authority: identity, role and permissions as a plain map. Never null. */
    static Map<String, Object> snapshot(Actor actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (actor == null) {
            return m;
        }
        m.put("id", actor.id());
        m.put("kind", actor.kind().name());
        m.put("orgId", actor.orgId());
        m.put("roleId", actor.roleId());
        m.put("permissions", List.copyOf(actor.permissions()));
        m.put("permissionVersion", actor.permissionVersion());
        m.put("onBehalfOf", actor.onBehalfOf());
        m.put("sessionId", actor.sessionId());
        return m;
    }

    /** The sender again, rebuilt from {@link #snapshot}, with its permissions as they were when queued; null when nothing usable was kept. */
    @SuppressWarnings("unchecked")
    static Actor restore(Map<String, Object> m) {
        if (m == null || !(m.get("id") instanceof String id) || id.isBlank()) {
            return null;
        }
        Actor.Kind kind;
        try {
            kind = Actor.Kind.valueOf(String.valueOf(m.getOrDefault("kind", "USER")));
        } catch (IllegalArgumentException e) {
            kind = Actor.Kind.USER;
        }
        Set<String> permissions = new HashSet<>();
        if (m.get("permissions") instanceof List<?> list) {
            list.forEach(p -> permissions.add(String.valueOf(p)));
        }
        long version = m.get("permissionVersion") instanceof Number n ? n.longValue() : 0L;
        return new Actor(
                id,
                kind,
                (String) m.get("orgId"),
                (String) m.get("roleId"),
                permissions,
                version,
                (String) m.get("onBehalfOf"),
                (String) m.get("sessionId"),
                null,
                Map.of("queued", true));
    }
}
