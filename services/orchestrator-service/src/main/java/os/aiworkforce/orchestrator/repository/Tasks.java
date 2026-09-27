package os.aiworkforce.orchestrator.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.LlmUsageRecord;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Tasks extends JpaRepository<Task, UUID> {

    List<Task> findByGoalIdOrderByPosition(UUID goalId);

    Optional<Task> findByIdAndOrgId(UUID id, UUID orgId);

    /** Candidates for the next run, oldest first. Read without a lock; {@link #claim} takes one. */
    @Query("""
            select t from Task t
            where t.orgId = :orgId and t.status in ('pending', 'ready')
            order by t.createdAt
            """)
    List<Task> findClaimable(@Param("orgId") UUID orgId, Pageable pageable);

    /**
     * Locks one task for the caller, or returns nothing when it has already been taken.
     *
     * <p>{@code SKIP LOCKED} (a lock timeout of -2) rather than waiting: a goal is advanced both by
     * the request that created it and by the goal sweep, possibly on two instances, and a task
     * held by one of them is running, so the other should move on rather than queue behind a run
     * that may take minutes. Only the task actually claimed is locked, so cancelling some other
     * goal in the workspace never waits for this one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Task t where t.id = :id and t.status in ('pending', 'ready')")
    Optional<Task> claim(@Param("id") UUID id);

    /** Workspaces with a task waiting to start, for the goal sweep. */
    @Query("select distinct t.orgId from Task t where t.status in ('pending', 'ready')")
    List<UUID> findOrgIdsWithClaimableTasks();

    /**
     * Tasks still shown as in progress although their run has ended.
     *
     * <p>A task has at most one active run at a time, so "has a finished run and no active one"
     * is the same as "its latest run has finished". The goal sweep repairs these, which is what
     * catches a task left behind by a run that ended before its outcome was reported to it.
     */
    @Query("""
            select t from Task t
            where t.status in ('running', 'waiting_approval')
              and exists (select 1 from Run r where r.taskId = t.id
                          and r.status in ('completed', 'failed', 'cancelled', 'abandoned'))
              and not exists (select 1 from Run r where r.taskId = t.id
                              and r.status in ('running', 'waiting_approval'))
            order by t.updatedAt
            """)
    List<Task> findStranded(Pageable pageable);
}
