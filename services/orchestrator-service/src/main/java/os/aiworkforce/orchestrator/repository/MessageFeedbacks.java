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

    Optional<MessageFeedback> findByOrgIdAndMessageIdAndUserId(UUID orgId, UUID messageId, UUID userId);

    /** What one person has rated in one conversation, for the thread to show beside each answer. */
    List<MessageFeedback> findByOrgIdAndConversationIdAndUserId(UUID orgId, UUID conversationId, UUID userId);

    /** Every rating on the answers one run gave, newest change first, for its trace. */
    List<MessageFeedback> findByOrgIdAndRunIdOrderByUpdatedAtDesc(UUID orgId, UUID runId);

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

    /** Withdraws one person's vote on one answer; 0 when there was none to withdraw. */
    @Modifying
    @Query("delete from MessageFeedback f where f.orgId = :orgId and f.messageId = :messageId and f.userId = :userId")
    int withdraw(@Param("orgId") UUID orgId, @Param("messageId") UUID messageId, @Param("userId") UUID userId);
}
