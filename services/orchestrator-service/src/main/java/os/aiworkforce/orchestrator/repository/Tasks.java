package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.Task;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Tasks extends JpaRepository<Task, UUID> {

    List<Task> findByGoalIdOrderByPosition(UUID goalId);

    /**
     * The same tasks {@link #findByGoalIdOrderByPosition} would return for each goal, fetched in
     * one query for every goal a caller already has in hand - a board refresh or a claim sweep
     * reading many goals at once builds its own {@code goalId -> tasks} map from this by grouping
     * while preserving order, rather than issuing one query per goal.
     */
    List<Task> findByGoalIdInOrderByPositionAsc(java.util.Collection<UUID> goalIds);

    Optional<Task> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Candidates for the next run, oldest first. Read without a lock; {@link #claim} takes one.
     *
     * <p>A task assigned to a paused (or retired) agent is left out entirely rather than returned
     * and skipped: a caller asks for a bounded window of candidates, and a workspace with many
     * tasks stuck behind one paused agent must not starve every other goal's work out of that
     * window. A task with no agent at all is still a candidate - it is claimed and then reported
     * as unable to start, which is a different failure to leaving it pending forever.
     */
    @Query(
            """
            select t from Task t
            where t.orgId = :orgId and t.status in ('pending', 'ready')
              and (t.agentId is null or exists (
                    select 1 from Agent a where a.id = t.agentId and a.status = 'active'))
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

    /** How many of a workspace's tasks are in one status, for the per-workspace cap on running work. */
    long countByOrgIdAndStatus(UUID orgId, String status);

    /** Workspaces with a task waiting to start, for the goal sweep. */
    @Query("select distinct t.orgId from Task t where t.status in ('pending', 'ready')")
    List<UUID> findOrgIdsWithClaimableTasks();

    /**
     * Tasks still shown as in progress although their run has ended.
     *
     * <p>A task has at most one active run at a time, so "has a finished run and no active one"
     * is the same as "its latest run has finished". The goal sweep repairs these, which is what
     * catches a task left behind by a run that ended before its outcome was reported to it.
     *
     * <p>A task waiting for an answer is active like one waiting for an approval, and is never
     * "repaired" while its run waits. Only a run started since the task's current attempt began
     * counts: a retried or re-claimed task is running from its claim before its new run exists,
     * and the finished run of an earlier attempt must not settle it.
     */
    @Query(
            """
            select t from Task t
            where t.status in ('running', 'waiting_approval', 'waiting_input')
              and exists (select 1 from Run r where r.taskId = t.id and r.startedAt >= t.startedAt
                          and r.status in ('completed', 'failed', 'cancelled', 'abandoned'))
              and not exists (select 1 from Run r where r.taskId = t.id
                              and r.status in ('running', 'waiting_approval', 'waiting_input'))
            order by t.updatedAt
            """)
    List<Task> findStranded(Pageable pageable);

    /** Pending or ready tasks, including those held behind a paused agent, for the board's queue. */
    @Query("select t from Task t where t.orgId = :orgId and t.status in ('pending', 'ready') order by t.createdAt")
    List<Task> findQueued(@Param("orgId") UUID orgId, Pageable page);

    /** [conversationId, count] of tasks waiting for approval, per chat conversation. */
    @Query(
            """
            select g.conversationId, count(t) from Task t, Goal g
            where t.goalId = g.id and g.orgId = :orgId and g.conversationId in :ids and t.status = 'waiting_approval'
            group by g.conversationId
            """)
    List<Object[]> waitingApprovalByConversation(
            @Param("orgId") UUID orgId, @Param("ids") java.util.Collection<UUID> ids);

    /** Conversations with a task waiting for approval, for the Chat "Needs you" group of an approver. */
    @Query(
            """
            select distinct g.conversationId from Task t, Goal g
            where t.goalId = g.id and g.orgId = :orgId and g.conversationId is not null
              and t.status = 'waiting_approval'
            """)
    List<UUID> conversationsWaitingForApproval(@Param("orgId") UUID orgId);
}
