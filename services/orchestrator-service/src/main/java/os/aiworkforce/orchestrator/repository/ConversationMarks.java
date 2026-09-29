package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.ConversationMark;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface ConversationMarks extends JpaRepository<ConversationMark, UUID> {

    Optional<ConversationMark> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    List<ConversationMark> findByOrgIdAndUserId(UUID orgId, UUID userId);

    /**
     * Sets {@code pinned} and/or {@code archived}; a null argument keeps the current value (or
     * false on insert). An atomic upsert, so a double click on Pin, or a read marker racing a
     * pin, never breaks {@code conversation_marks_unique}.
     */
    @Modifying
    @Query(
            value =
                    """
            insert into conversation_marks (id, org_id, conversation_id, user_id, pinned, archived, updated_at)
            values (:id, :orgId, :conversationId, :userId, coalesce(:pinned, false), coalesce(:archived, false), now())
            on conflict (conversation_id, user_id) do update set
                pinned = coalesce(:pinned, conversation_marks.pinned),
                archived = coalesce(:archived, conversation_marks.archived),
                updated_at = now()
            """,
            nativeQuery = true)
    int upsertFlags(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("conversationId") UUID conversationId,
            @Param("userId") UUID userId,
            @Param("pinned") Boolean pinned,
            @Param("archived") Boolean archived);

    /** Moves the read marker forward only; an older position never moves it back. */
    @Modifying
    @Query(
            value =
                    """
            insert into conversation_marks (id, org_id, conversation_id, user_id, last_read_position, updated_at)
            values (:id, :orgId, :conversationId, :userId, :position, now())
            on conflict (conversation_id, user_id) do update set
                last_read_position = greatest(coalesce(conversation_marks.last_read_position, -1), :position),
                updated_at = now()
            """,
            nativeQuery = true)
    int upsertRead(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("conversationId") UUID conversationId,
            @Param("userId") UUID userId,
            @Param("position") int position);
}
