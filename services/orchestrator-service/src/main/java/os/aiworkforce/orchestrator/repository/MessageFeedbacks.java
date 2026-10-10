// @find: message feedbacks repository, rate answer, thumbs up down upsert, withdraw rating, ratings for a run, MessageFeedbacks
// @what: Spring Data repository for MessageFeedback rows with a race-safe upsert.
// @flow: Used by the chat feedback endpoints and agent satisfaction figures.
// @find: message feedbacks repository, rate answer, thumbs up down upsert, withdraw rating, ratings for a run, MessageFeedbacks
// @what: Spring Data repository for MessageFeedback rows with a race-safe upsert.
// @flow: Used by the chat feedback endpoints and agent satisfaction figures.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.MessageFeedback;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface MessageFeedbacks extends JpaRepository<MessageFeedback, UUID> {

    // @find: my rating on a message
    // @find: my rating on a message
    Optional<MessageFeedback> findByOrgIdAndMessageIdAndUserId(UUID orgId, UUID messageId, UUID userId);

    // @find: my ratings in a conversation
    // @find: my ratings in a conversation
    /** What one person has rated in one conversation, for the thread to show beside each answer. */
    List<MessageFeedback> findByOrgIdAndConversationIdAndUserId(UUID orgId, UUID conversationId, UUID userId);

    // @find: ratings on a run
    // @find: ratings on a run
    /** Every rating on the answers one run gave, newest change first, for its trace. */
    List<MessageFeedback> findByOrgIdAndRunIdOrderByUpdatedAtDesc(UUID orgId, UUID runId);

    // @find: save thumbs up or down, rate answer
    // @find: save thumbs up or down, rate answer
    /**
     * Records a vote, or changes the one this person already cast on this answer. An atomic
     * upsert, so a double click, or two tabs, never breaks {@code chat_message_feedback_unique}.
     * The first vote's {@code created_at} is kept; {@code updated_at} moves with each change.
     */
    @Modifying
    @Query(
            value =
                    """
            insert into chat_message_feedback
                (id, org_id, conversation_id, message_id, user_id, agent_id, run_id, rating, reason,
                 created_at, updated_at)
            values (:id, :orgId, :conversationId, :messageId, :userId, :agentId, :runId, :rating, :reason,
                    now(), now())
            on conflict (org_id, message_id, user_id) do update set
                rating = excluded.rating,
                reason = excluded.reason,
                agent_id = excluded.agent_id,
                run_id = excluded.run_id,
                updated_at = now()
            """,
            nativeQuery = true)
    int upsert(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("conversationId") UUID conversationId,
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("runId") UUID runId,
            @Param("rating") short rating,
            @Param("reason") String reason);

    // @find: withdraw rating, remove thumbs
    // @find: withdraw rating, remove thumbs
    /** Withdraws one person's vote on one answer; 0 when there was none to withdraw. */
    @Modifying
    @Query("delete from MessageFeedback f where f.orgId = :orgId and f.messageId = :messageId and f.userId = :userId")
    int withdraw(@Param("orgId") UUID orgId, @Param("messageId") UUID messageId, @Param("userId") UUID userId);
}
