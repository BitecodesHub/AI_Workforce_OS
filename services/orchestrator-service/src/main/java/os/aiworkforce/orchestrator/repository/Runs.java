package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.Collection;
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

import os.aiworkforce.orchestrator.domain.Run;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Runs extends JpaRepository<Run, UUID> {

    Page<Run> findByOrgIdOrderByStartedAtDesc(UUID orgId, Pageable pageable);

    /*
     * The filtered lists behind the Runs page. Every one is keyed on the workspace first, so a
     * filter can never widen a query beyond the caller's own organisation.
     */

    Page<Run> findByOrgIdAndStatusOrderByStartedAtDesc(UUID orgId, String status, Pageable pageable);

    Page<Run> findByOrgIdAndAgentIdOrderByStartedAtDesc(UUID orgId, UUID agentId, Pageable pageable);

    Page<Run> findByOrgIdAndAgentIdAndStatusOrderByStartedAtDesc(
            UUID orgId, UUID agentId, String status, Pageable pageable);

    Optional<Run> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The most recent run against a task.
     *
     * <p>A task can be retried, so more than one run may exist for it; the latest is the one a
     * person actually wants when they ask "what happened with this task" - it is the attempt that
     * is either still going or produced the result the task now shows.
     */
    Optional<Run> findFirstByTaskIdOrderByStartedAtDesc(UUID taskId);

    /**
     * The same runs {@link #findFirstByTaskIdOrderByStartedAtDesc} would return one at a time, for
     * every task id a caller already has in hand, in one query - ordered so that a caller grouping
     * by task id and keeping only the first row per group ends up with each task's latest run,
     * exactly as the single-task lookup does.
     */
    List<Run> findByTaskIdInOrderByTaskIdAscStartedAtDesc(java.util.Collection<UUID> taskIds);

    /**
     * Runs whose worker stopped renewing the lease.
     *
     * <p>Used by the reaper. Without it, a process killed mid-run leaves work that shows as
     * running forever: it never completes, never fails, and never gets retried.
     */
    @Query(
            """
            select r from Run r
            where r.status = 'running' and r.leaseExpiresAt is not null and r.leaseExpiresAt < :now
            """)
    List<Run> findAbandoned(@Param("now") Instant now, Pageable pageable);

    long countByOrgIdAndStatus(UUID orgId, String status);

    /**
     * Claims a parked run for the resume that will drive it, or returns 0 when another resume, a
     * stop or an expiry got there first.
     *
     * <p>A conditional update rather than a read and a save, so two resumes of one run - an
     * answer's own submission and the sweep, or two instances - can never both drive it. It
     * clears the persistence context, so a caller reloads the run before saving it again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update Run r set r.status = 'running', r.workerId = :worker, r.leaseExpiresAt = :lease,
                   r.version = r.version + 1, r.updatedAt = :now
            where r.id = :id and r.status = :from
            """)
    int claimParked(
            @Param("id") UUID id,
            @Param("from") String from,
            @Param("worker") String worker,
            @Param("lease") Instant lease,
            @Param("now") Instant now);

    /** Runs started directly on an agent rather than for a task, in these statuses. */
    List<Run> findByOrgIdAndTaskIdIsNullAndStatusIn(UUID orgId, Collection<String> statuses);

    /** Locks one run for a stop, so a concurrent claimParked waits behind it and then loses. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id and r.orgId = :orgId")
    Optional<Run> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** Locks every active run of a goal, in id order so two cancels never deadlock each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select r from Run r
            where r.status in ('running', 'waiting_approval', 'waiting_input')
              and r.taskId in (select t.id from Task t where t.goalId = :goalId)
            order by r.id
            """)
    List<Run> lockActiveByGoal(@Param("goalId") UUID goalId);
}
