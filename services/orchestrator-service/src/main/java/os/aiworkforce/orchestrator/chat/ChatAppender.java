// @find: append message to conversation, message position, conversation lock, version conflict, ChatAppender, add chat message, safe append, message ordering
// @what: The single safe way to append a message to a conversation, locking the conversation row so positions never collide.
// @flow: Called by CoordinatorService and ChatGoalListener whenever a message is stored.
package os.aiworkforce.orchestrator.chat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;

/**
 * The one safe way to append a message to a conversation.
 *
 * <p>Positions used to be allocated in memory and protected only by the conversation's
 * {@code @Version} lock: an agent's answer landing during a twenty-second planner call made the
 * person's own message fail with a version conflict and be lost. Questions add another concurrent
 * writer on top of that. Locking the conversation row here, and only here, fixes both.
 *
 * <p>Every write of a chat message goes through this class - in {@code CoordinatorService},
 * {@code ChatGoalListener} and {@code ConversationAdmin}. Nothing else calls {@code
 * Conversation.recordMessage}.
 */
@Component
public class ChatAppender {

    private final Conversations conversations;
    private final ChatMessages messages;
    private final EntityManager entityManager;

    public ChatAppender(Conversations conversations, ChatMessages messages, EntityManager entityManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.entityManager = entityManager;
    }

    /**
     * Locks the conversation row until the surrounding transaction ends. Call it last (lock
     * order, spec 0.2: run, then question or approval, then task, then goal, then conversation).
     *
     * <p>Flushes first, so every pending goal, task and run {@code UPDATE} already made in this
     * transaction reaches the database, and takes its row locks, before the conversation lock is
     * taken - JPA defers those writes until flush, so without this they would reach the database
     * after the conversation lock and break the lock order. Refreshes with the lock, so the row is
     * current even when this transaction had already loaded it; a stale managed copy would fail
     * its version check on save.
     */
    // @find: lock conversation row, lock conversation for update
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Conversation> lock(UUID orgId, UUID conversationId) {
        entityManager.flush();
        Optional<Conversation> found = conversations.findByIdAndOrgId(conversationId, orgId);
        found.ifPresent(c -> entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE));
        return found;
    }

    /** Appends one message at the next position of a conversation already locked by {@link #lock}. */
    // @find: append chat message, store message in conversation, new message position
    @Transactional(propagation = Propagation.MANDATORY)
    public ChatMessage append(
            Conversation locked,
            String authorKind,
            UUID authorId,
            UUID agentId,
            String kind,
            String content,
            Map<String, Object> detail,
            UUID goalId) {
        int position = locked.nextPosition();
        locked.recordMessage(CoordinatorService.truncateAtWord(content, 120));
        conversations.save(locked);
        return messages.save(ChatMessage.of(
                locked.getOrgId(),
                locked.getId(),
                position,
                authorKind,
                authorId,
                agentId,
                kind,
                content,
                detail,
                goalId));
    }
}
