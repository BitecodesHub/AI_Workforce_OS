// @find: chat queued messages repository, queued messages list, conversations waiting, expire old queued, release stale starts, ChatQueuedMessages
// @what: Spring Data repository for ChatQueuedMessage rows.
// @flow: Used by the chat queue worker and message edit/cancel endpoints.
// @find: chat queued messages repository, queued messages list, conversations waiting, expire old queued, release stale starts, ChatQueuedMessages
// @what: Spring Data repository for ChatQueuedMessage rows.
// @flow: Used by the chat queue worker and message edit/cancel endpoints.
package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.ChatQueuedMessage;

/** Messages waiting their turn in a conversation, oldest first. */
public interface ChatQueuedMessages extends JpaRepository<ChatQueuedMessage, UUID> {

    // @find: list queued messages of a conversation
    // @find: list queued messages of a conversation
    List<ChatQueuedMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    // @find: get queued message for edit or cancel
    // @find: get queued message for edit or cancel
    Optional<ChatQueuedMessage> findByIdAndOrgIdAndConversationId(UUID id, UUID orgId, UUID conversationId);

    // @find: conversations with queued messages waiting
    // @find: conversations with queued messages waiting
    /** [orgId, conversationId] of every conversation with a message still waiting to start. */
    @Query("select distinct q.orgId, q.conversationId from ChatQueuedMessage q where q.status = 'queued'")
    List<Object[]> conversationsWaiting();

    // @find: expire old queued messages
    // @find: expire old queued messages
    /** Marks messages that waited longer than allowed; they are shown as expired and never started. */
    @Modifying
    @Query(
            "update ChatQueuedMessage q set q.status = 'expired', q.updatedAt = :now"
                    + " where q.status = 'queued' and q.createdAt < :cut")
    int expireOlderThan(@Param("cut") Instant cut, @Param("now") Instant now);

    // @find: release queued messages stuck starting
    // @find: release queued messages stuck starting
    /** A "Start now anyway" claim that never finished (a crash) goes back in the queue. */
    @Modifying
    @Query(
            "update ChatQueuedMessage q set q.status = 'queued', q.updatedAt = :now"
                    + " where q.status = 'starting' and q.updatedAt < :cut")
    int releaseStaleStarts(@Param("cut") Instant cut, @Param("now") Instant now);
}
