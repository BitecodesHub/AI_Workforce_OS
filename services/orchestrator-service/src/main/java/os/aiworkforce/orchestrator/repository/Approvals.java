// @find: approvals repository, pending approvals, find expired approvals, approved awaiting resume, withdraw pending approvals, lock approval, record outcome, count pending by decider, Approvals
// @what: Spring Data repository for Approval rows including lock, expiry and resume queries.
// @flow: Used by the approval service, expiry sweep and resume worker.
// @find: approvals repository, pending approvals, find expired approvals, approved awaiting resume, withdraw pending approvals, lock approval, record outcome, count pending by decider, Approvals
// @what: Spring Data repository for Approval rows including lock, expiry and resume queries.
// @flow: Used by the approval service, expiry sweep and resume worker.
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

    // @find: record outcome of approved action
    // @find: record outcome of approved action
    /**
     * Records what happened when an approved call was carried out. A direct update, so it never
     * collides with a decision saved at the same moment and never overwrites one.
     */
    @Modifying
    @Query("update Approval a set a.outcome = :outcome where a.id = :id")
    int recordOutcome(@Param("id") UUID id, @Param("outcome") String outcome);

    // @find: list pending approvals, approval inbox
    // @find: list pending approvals, approval inbox
    @Query(
            """
            select a from Approval a
            where a.orgId = :orgId and a.status = 'pending'
            order by a.requestedAt
            """)
    List<Approval> findPending(@Param("orgId") UUID orgId);

    // @find: list approvals by status, paged
    // @find: list approvals by status, paged
    /**
     * One page of a workspace's approvals in any of {@code statuses}, in the order the page asks
     * for: the queue soonest-expiring first, the history newest decision first.
     */
    List<Approval> findByOrgIdAndStatusIn(UUID orgId, Collection<String> statuses, Pageable page);

    // @find: list approvals for an agent
    // @find: list approvals for an agent
    /** As {@link #findByOrgIdAndStatusIn}, for one agent's approvals only. */
    List<Approval> findByOrgIdAndStatusInAndAgentId(
            UUID orgId, Collection<String> statuses, UUID agentId, Pageable page);

    // @find: list approvals for a run
    // @find: list approvals for a run
    /** As {@link #findByOrgIdAndStatusIn}, for one run's approvals only: the card a run's own page shows. */
    List<Approval> findByOrgIdAndStatusInAndRunId(UUID orgId, Collection<String> statuses, UUID runId, Pageable page);

    // @find: count pending approvals per decider, who can approve
    // @find: count pending approvals per decider, who can approve
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

    // @find: count pending approvals per decider for agent
    // @find: count pending approvals per decider for agent
    /** As {@link #countPendingByDecider}, for one agent's approvals only. */
    @Query(
            """
            select a.requiredPermission, a.requestedBy, a.actionClass, count(a) from Approval a
            where a.orgId = :orgId and a.status = 'pending' and a.agentId = :agentId
            group by a.requiredPermission, a.requestedBy, a.actionClass
            """)
    List<Object[]> countPendingByDeciderForAgent(@Param("orgId") UUID orgId, @Param("agentId") UUID agentId);

    // @find: get approval by id
    // @find: get approval by id
    Optional<Approval> findByIdAndOrgId(UUID id, UUID orgId);

    // @find: lock approval for decision, pessimistic lock, decide approval
    // @find: lock approval for decision, pessimistic lock, decide approval
    /**
     * The approval, locked until the surrounding transaction ends, so two people deciding it at
     * once are taken one after the other: the second sees the first's decision instead of failing
     * on a stale version.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id and a.orgId = :orgId")
    Optional<Approval> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    // @find: approvals sent back with feedback for a run
    // @find: approvals sent back with feedback for a run
    /** Approvals of one run that were sent back with feedback, oldest first. */
    List<Approval> findByRunIdAndSentBackTrueOrderByRequestedAtAsc(UUID runId);

    // @find: approvals of a run by status
    // @find: approvals of a run by status
    List<Approval> findByRunIdAndStatus(UUID runId, String status);

    // @find: find expired approvals, expiry sweep
    // @find: find expired approvals, expiry sweep
    /** Pending approvals past their deadline, for the expiry sweep. */
    @Query("select a from Approval a where a.status = 'pending' and a.expiresAt < :now")
    List<Approval> findExpired(@Param("now") Instant now, Pageable pageable);

    // @find: approved approvals awaiting resume, resume run
    // @find: approved approvals awaiting resume, resume run
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

    // @find: withdraw pending approvals when run ends or is cancelled
    // @find: withdraw pending approvals when run ends or is cancelled
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
