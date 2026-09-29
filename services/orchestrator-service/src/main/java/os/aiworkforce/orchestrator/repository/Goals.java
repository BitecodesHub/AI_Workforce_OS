package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.Goal;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Goals extends JpaRepository<Goal, UUID> {

    Page<Goal> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    Optional<Goal> findByIdAndOrgId(UUID id, UUID orgId);

    List<Goal> findByOrgIdAndConversationIdAndStatusIn(
            UUID orgId, UUID conversationId, java.util.Collection<String> statuses);

    /** [conversationId, count] of goals still in progress, per chat conversation. */
    @Query(
            """
            select g.conversationId, count(g) from Goal g
            where g.orgId = :orgId and g.conversationId in :ids and g.status in ('planning', 'running', 'waiting')
            group by g.conversationId
            """)
    List<Object[]> activeByConversation(@Param("orgId") UUID orgId, @Param("ids") java.util.Collection<UUID> ids);

    @Modifying(flushAutomatically = true)
    @Query("update Goal g set g.conversationId = null where g.orgId = :orgId and g.conversationId = :conversationId")
    int detachConversation(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId);

    /** Every active goal's id, oldest first, for Stop everything. Ids only, so the list is cheap at any size. */
    @Query(
            "select g.id from Goal g where g.orgId = :orgId and g.status in ('planning', 'running', 'waiting') order by g.createdAt")
    List<UUID> activeIds(@Param("orgId") UUID orgId);

    /**
     * Locks a goal for retry, so a double click gets a clean 409 instead of a version conflict.
     *
     * <p>The one place a goal is locked before its runs: a goal that failed or was stopped has no
     * active run, so no finishing run can hold a run or task lock and then wait for this one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Goal g where g.id = :id and g.orgId = :orgId")
    Optional<Goal> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** Goals that finished with this status since a moment, newest first: the board's "failed today". */
    @Query(
            """
            select g from Goal g where g.orgId = :orgId and g.status = :status and g.completedAt >= :since
            order by g.completedAt desc
            """)
    List<Goal> findFinishedSince(
            @Param("orgId") UUID orgId, @Param("status") String status, @Param("since") Instant since, Pageable page);

    long countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(UUID orgId, String status, Instant since);
}
