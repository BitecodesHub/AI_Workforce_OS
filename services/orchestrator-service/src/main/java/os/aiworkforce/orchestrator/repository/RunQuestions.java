package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.RunQuestion;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Questions runs stopped to ask.
 *
 * <p>Only {@code QuestionService} uses this. Every query reached from a request filters by the
 * workspace, or runs after an org-checked lookup of the run it belongs to.
 */
public interface RunQuestions extends JpaRepository<RunQuestion, UUID> {

    Optional<RunQuestion> findByIdAndOrgId(UUID id, UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from RunQuestion q where q.id = :id and q.orgId = :orgId")
    Optional<RunQuestion> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from RunQuestion q where q.id = :id")
    Optional<RunQuestion> lockById(@Param("id") UUID id);

    Optional<RunQuestion> findByRunIdAndToolCallId(UUID runId, String toolCallId);

    List<RunQuestion> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** The run's newest question, whatever its status. Read inside the resume claim (A2.7 step 3). */
    Optional<RunQuestion> findFirstByRunIdOrderByCreatedAtDesc(UUID runId);

    long countByRunId(UUID runId);

    boolean existsByRunIdAndStatus(UUID runId, String status);

    List<RunQuestion> findByRunIdAndStatus(UUID runId, String status);

    @Query(
            "select q from RunQuestion q where q.orgId = :orgId and q.status = 'pending' order by q.expiresAt, q.createdAt")
    List<RunQuestion> findPending(@Param("orgId") UUID orgId, Pageable page);

    @Query("select q from RunQuestion q where q.orgId = :orgId order by q.createdAt desc")
    List<RunQuestion> findRecent(@Param("orgId") UUID orgId, Pageable page);

    /** Every pending question of a conversation, however old. */
    @Query(
            """
            select q from RunQuestion q
            where q.orgId = :orgId and q.conversationId = :conversationId and q.status = 'pending'
            """)
    List<RunQuestion> findPendingForConversation(
            @Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId);

    /** The newest closed or open questions of a conversation, newest first. */
    @Query(
            """
            select q from RunQuestion q
            where q.orgId = :orgId and q.conversationId = :conversationId
            order by q.createdAt desc
            """)
    List<RunQuestion> findRecentForConversation(
            @Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId, Pageable page);

    /** Ids only: each is then expired in its own transaction under a row lock. */
    @Query("select q.id from RunQuestion q where q.status = 'pending' and q.expiresAt < :now order by q.expiresAt")
    List<UUID> findExpiredIds(@Param("now") Instant now, Pageable page);

    /** Pending questions whose run is no longer waiting for them: the work stopped by a path that could not withdraw them. */
    @Query(
            """
            select q.id from RunQuestion q
            where q.status = 'pending'
              and not exists (select 1 from Run r where r.id = q.runId and r.status = 'waiting_input')
            order by q.createdAt
            """)
    List<UUID> findStrandedPendingIds(Pageable page);

    /**
     * Closed questions whose run is still parked, for the resume sweep. Only the run's newest question counts:
     * an earlier answered question of a run that has since asked again must never resume it.
     */
    @Query(
            """
            select q from RunQuestion q
            where q.status in ('answered', 'expired') and q.updatedAt < :cutoff
              and exists (select 1 from Run r where r.id = q.runId and r.status = 'waiting_input')
              and not exists (select 1 from RunQuestion q2 where q2.runId = q.runId and q2.createdAt > q.createdAt)
            order by q.updatedAt
            """)
    List<RunQuestion> findAwaitingResume(@Param("cutoff") Instant cutoff, Pageable page);

    /** Withdraws a run's pending question without loading it, so a concurrent answer never rolls this back. */
    @Modifying(flushAutomatically = true)
    @Query(
            """
            update RunQuestion q set q.status = 'cancelled', q.closedReason = :reason,
                   q.version = q.version + 1, q.updatedAt = :now
            where q.runId = :runId and q.status = 'pending'
            """)
    int withdrawPending(@Param("runId") UUID runId, @Param("reason") String reason, @Param("now") Instant now);

    /** [conversationId, requestedBy] for every pending question in these conversations. */
    @Query(
            """
            select q.conversationId, q.requestedBy from RunQuestion q
            where q.orgId = :orgId and q.status = 'pending' and q.conversationId in :ids
            """)
    List<Object[]> pendingByConversation(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids);

    /** Conversations holding a pending question this person asked for, for the "Needs you" group. */
    @Query(
            """
            select distinct q.conversationId from RunQuestion q
            where q.orgId = :orgId and q.status = 'pending' and q.requestedBy = :me and q.conversationId is not null
            """)
    List<UUID> conversationsNeedingAnswerFrom(@Param("orgId") UUID orgId, @Param("me") UUID me);

    @Modifying(flushAutomatically = true)
    @Query(
            "update RunQuestion q set q.conversationId = null where q.orgId = :orgId and q.conversationId = :conversationId")
    int detachConversation(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId);
}
