// @find: schedule goals repository, goals started by a schedule, schedule run history query, last goal of schedule, ScheduleGoals
// @what: Repository finding the goals a schedule has started.
// @flow: Used by ScheduleController runs and ScheduleService
package os.aiworkforce.orchestrator.schedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.Goal;

/**
 * The goals a schedule created, read from this package rather than added to {@code
 * os.aiworkforce.orchestrator.repository.Goals}: a second repository interface over the same
 * table and entity is an ordinary thing for Spring Data to serve, and it keeps every file this
 * package needs inside the package it owns.
 */
public interface ScheduleGoals extends JpaRepository<Goal, UUID> {

    List<Goal> findByOrgIdAndScheduleIdOrderByCreatedAtDesc(UUID orgId, UUID scheduleId);

    /**
     * One page of a schedule's goals, newest first: a schedule that fires every few minutes starts
     * thousands of goals a month, so its history is read a page at a time, served by the
     * {@code (schedule_id, created_at desc)} index.
     */
    Page<Goal> findByOrgIdAndScheduleIdOrderByCreatedAtDesc(UUID orgId, UUID scheduleId, Pageable pageable);

    Optional<Goal> findFirstByScheduleIdOrderByCreatedAtDesc(UUID scheduleId);
}
