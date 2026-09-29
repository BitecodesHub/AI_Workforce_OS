package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
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

    @Query(
            """
            select a from Approval a
            where a.orgId = :orgId and a.status = 'pending'
            order by a.requestedAt
            """)
    List<Approval> findPending(@Param("orgId") UUID orgId);

    Optional<Approval> findByIdAndOrgId(UUID id, UUID orgId);

    List<Approval> findByRunIdAndStatus(UUID runId, String status);

    /** Pending approvals past their deadline, for the expiry sweep. */
    @Query("select a from Approval a where a.status = 'pending' and a.expiresAt < :now")
    List<Approval> findExpired(@Param("now") Instant now, Pageable pageable);

    /**
     * Approved approvals whose run is still parked, for the resume sweep. Only the run's newest approval counts: a run
     * that had an earlier approval granted and is now parked on a newer, pending one must never be resumed by it.
     */
    @Query(
            """
            select a from Approval a where a.status = 'approved' and a.decidedAt < :cutoff
              and exists (select 1 from Run r where r.id = a.runId and r.status = 'waiting_approval')
              and not exists (select 1 from Approval p where p.runId = a.runId and p.requestedAt > a.requestedAt)
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
