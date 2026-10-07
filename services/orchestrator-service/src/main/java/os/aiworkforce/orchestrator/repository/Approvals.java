package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.Approval;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Approvals extends JpaRepository<Approval, UUID> {

    /**
     * Records what happened when an approved call was carried out. A direct update, so it never
     * collides with a decision saved at the same moment and never overwrites one.
     */
    @Modifying
    @Query("update Approval a set a.outcome = :outcome where a.id = :id")
    int recordOutcome(@Param("id") UUID id, @Param("outcome") String outcome);

    @Query(
            """
            select a from Approval a
            where a.orgId = :orgId and a.status = 'pending'
            order by a.requestedAt
            """)
    List<Approval> findPending(@Param("orgId") UUID orgId);

    /**
     * One page of a workspace's approvals in any of {@code statuses}, in the order the page asks
     * for: the queue soonest-expiring first, the history newest decision first.
     */
    List<Approval> findByOrgIdAndStatusIn(UUID orgId, Collection<String> statuses, Pageable page);

    /** As {@link #findByOrgIdAndStatusIn}, for one agent's approvals only. */
    List<Approval> findByOrgIdAndStatusInAndAgentId(
            UUID orgId, Collection<String> statuses, UUID agentId, Pageable page);

    /** As {@link #findByOrgIdAndStatusIn}, for one run's approvals only: the card a run's own page shows. */
    List<Approval> findByOrgIdAndStatusInAndRunId(UUID orgId, Collection<String> statuses, UUID runId, Pageable page);

    /**
     * How many approvals are pending, counted by what decides who may answer them: the permission
     * an approver needs, who asked for the work, and what the action does. Each row is {@code
     * [requiredPermission, requestedBy, actionClass, count]}, so the caller can say how many of
     * them this viewer could decide without loading a single payload.
     */
    @Query(
            """
            select a.requiredPermission, a.requestedBy, a.actionClass, count(a) from Approval a
            where a.orgId = :orgId and a.status = 'pending'
            group by a.requiredPermission, a.requestedBy, a.actionClass
            """)
    List<Object[]> countPendingByDecider(@Param("orgId") UUID orgId);

    /** As {@link #countPendingByDecider}, for one agent's approvals only. */
    @Query(
            """
            select a.requiredPermission, a.requestedBy, a.actionClass, count(a) from Approval a
            where a.orgId = :orgId and a.status = 'pending' and a.agentId = :agentId
            group by a.requiredPermission, a.requestedBy, a.actionClass
            """)
    List<Object[]> countPendingByDeciderForAgent(@Param("orgId") UUID orgId, @Param("agentId") UUID agentId);

    Optional<Approval> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The approval, locked until the surrounding transaction ends, so two people deciding it at
     * once are taken one after the other: the second sees the first's decision instead of failing
     * on a stale version.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id and a.orgId = :orgId")
    Optional<Approval> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** Approvals of one run that were sent back with feedback, oldest first. */
    List<Approval> findByRunIdAndSentBackTrueOrderByRequestedAtAsc(UUID runId);

    List<Approval> findByRunIdAndStatus(UUID runId, String status);

    /** Pending approvals past their deadline, for the expiry sweep. */
    @Query("select a from Approval a where a.status = 'pending' and a.expiresAt < :now")
    List<Approval> findExpired(@Param("now") Instant now, Pageable pageable);

    /**
     * Approved approvals whose run is still parked, for the resume sweep.
     *
     * <p>Starts from the parked runs, not from approvals: every approval ever granted stays
     * approved, so a query that began there would rescan the whole history each minute. A run
     * leaves {@code waiting_approval} as soon as it resumes, so its status is the record that the
     * resume happened. Only the run's newest approval counts: a run that had an earlier approval
     * granted and is now parked on a newer, pending one must never be resumed by it.
     */
    @Query(
            """
            select a from Run r join Approval a on a.runId = r.id
            where r.status = 'waiting_approval'
              and a.status = 'approved' and a.decidedAt < :cutoff
              and a.requestedAt = (select max(p.requestedAt) from Approval p where p.runId = r.id)
            order by a.decidedAt
            """)
    List<Approval> findApprovedAwaitingResume(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Withdraws a run's pending approvals without loading them, so a decision committing at the
     * same moment simply wins or loses and never rolls back the stop with a version conflict.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            """
            update Approval a set a.status = 'cancelled', a.decidedAt = :now, a.version = a.version + 1, a.updatedAt = :now
            where a.runId = :runId and a.status = 'pending'
            """)
    int withdrawPending(@Param("runId") UUID runId, @Param("now") Instant now);
}
