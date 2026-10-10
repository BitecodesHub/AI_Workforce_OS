// @find: goals repository, list goals, recent goals, filter goals, active goals, finished goals, lock goal, detach conversation, goals completed count, Goals
// @what: Spring Data repository for Goal rows.
// @flow: Used by the goal service, chat, dashboard and scheduler.
// @find: goals repository, list goals, recent goals, filter goals, active goals, finished goals, lock goal, detach conversation, goals completed count, Goals
// @what: Spring Data repository for Goal rows.
// @flow: Used by the goal service, chat, dashboard and scheduler.
package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
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

import os.aiworkforce.orchestrator.domain.Goal;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Goals extends JpaRepository<Goal, UUID> {

    // @find: list recent goals
    // @find: list recent goals
    /**
     * The newest goals of a workspace, as a plain list.
     *
     * <p>A {@code Page} would add a {@code count(*)} over every goal the workspace has ever had to
     * each call, and the board reads this every few seconds.
     */
    @Query("select g from Goal g where g.orgId = :orgId order by g.createdAt desc")
    List<Goal> findRecent(@Param("orgId") UUID orgId, Pageable pageable);

    // @find: filter goals by status or search
    // @find: filter goals by status or search
    /**
     * The newest goals of a workspace, narrowed by any of status, source and schedule, as a plain
     * list. A filter that is not wanted is passed as its "any" value: {@code ''} for a status or a
     * source, {@code anySchedule = true} for the schedule (its id is then ignored, and never null).
     */
    @Query(
            """
            select g from Goal g
            where g.orgId = :orgId
              and (:status = '' or g.status = :status)
              and (:source = '' or g.source = :source)
              and (:anySchedule = true or g.scheduleId = :scheduleId)
            order by g.createdAt desc
            """)
    List<Goal> findFiltered(
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("source") String source,
            @Param("anySchedule") boolean anySchedule,
            @Param("scheduleId") UUID scheduleId,
            Pageable pageable);

    // @find: goals of a conversation updated since
    // @find: goals of a conversation updated since
    /** A conversation's goals that changed at or after a moment, for the conversation's incremental reads. */
    List<Goal> findByOrgIdAndConversationIdAndUpdatedAtGreaterThanEqual(UUID orgId, UUID conversationId, Instant since);

    // @find: get goal by id
    // @find: get goal by id
    Optional<Goal> findByIdAndOrgId(UUID id, UUID orgId);

    // @find: goals of a conversation by status
    // @find: goals of a conversation by status
    List<Goal> findByOrgIdAndConversationIdAndStatusIn(
            UUID orgId, UUID conversationId, java.util.Collection<String> statuses);

    // @find: count active goals per conversation
    // @find: count active goals per conversation
    /** [conversationId, count] of goals still in progress, per chat conversation. */
    @Query(
            """
            select g.conversationId, count(g) from Goal g
            where g.orgId = :orgId and g.conversationId in :ids and g.status in ('planning', 'running', 'waiting')
            group by g.conversationId
            """)
    List<Object[]> activeByConversation(@Param("orgId") UUID orgId, @Param("ids") java.util.Collection<UUID> ids);

    // @find: detach goals from deleted conversation
    // @find: detach goals from deleted conversation
    @Modifying(flushAutomatically = true)
    @Query("update Goal g set g.conversationId = null where g.orgId = :orgId and g.conversationId = :conversationId")
    int detachConversation(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId);

    // @find: ids of active goals
    // @find: ids of active goals
    /** Every active goal's id, oldest first, for Stop everything. Ids only, so the list is cheap at any size. */
    @Query(
            "select g.id from Goal g where g.orgId = :orgId and g.status in ('planning', 'running', 'waiting') order by g.createdAt")
    List<UUID> activeIds(@Param("orgId") UUID orgId);

    // @find: lock goal for update
    // @find: lock goal for update
    /**
     * Locks a goal for retry, so a double click gets a clean 409 instead of a version conflict.
     *
     * <p>The one place a goal is locked before its runs: a goal that failed or was stopped has no
     * active run, so no finishing run can hold a run or task lock and then wait for this one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Goal g where g.id = :id and g.orgId = :orgId")
    Optional<Goal> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    // @find: goals finished since a time
    // @find: goals finished since a time
    /** Goals that finished with this status since a moment, newest first: the board's "failed today". */
    @Query(
            """
            select g from Goal g where g.orgId = :orgId and g.status = :status and g.completedAt >= :since
            order by g.completedAt desc
            """)
    List<Goal> findFinishedSince(
            @Param("orgId") UUID orgId, @Param("status") String status, @Param("since") Instant since, Pageable page);

    // @find: count completed goals since a time, dashboard
    // @find: count completed goals since a time, dashboard
    long countByOrgIdAndStatusAndCompletedAtGreaterThanEqual(UUID orgId, String status, Instant since);
}
