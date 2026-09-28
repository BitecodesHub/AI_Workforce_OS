package os.aiworkforce.orchestrator.schedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    Optional<Goal> findFirstByScheduleIdOrderByCreatedAtDesc(UUID scheduleId);
}
