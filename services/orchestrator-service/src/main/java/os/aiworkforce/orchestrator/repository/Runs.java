package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
    @Query("""
            select r from Run r
            where r.status = 'running' and r.leaseExpiresAt is not null and r.leaseExpiresAt < :now
            """)
    List<Run> findAbandoned(@Param("now") Instant now, Pageable pageable);

    long countByOrgIdAndStatus(UUID orgId, String status);
}
