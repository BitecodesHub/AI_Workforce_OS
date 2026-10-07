package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, and the application fails at startup on a missing bean; one
 * interface per file is also the convention every other repository in this service follows.
 */

public interface Schedules extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByOrgIdOrderByNameAsc(UUID orgId);

    Optional<Schedule> findByIdAndOrgId(UUID id, UUID orgId);

    /** Every schedule in a workspace that fires as this person, enabled or not. */
    List<Schedule> findByOrgIdAndRequestedBy(UUID orgId, UUID requestedBy);

    /**
     * Every enabled, due schedule across every workspace, oldest-due first.
     *
     * <p>Bounded to {@code pageable}'s size so one sweep tick never holds the sweep open for an
     * unbounded backlog; a workspace with more due schedules than fit in one tick simply gets the
     * rest on the next one.
     */
    @Query("select s from Schedule s where s.enabled = true and s.nextRunAt <= :now order by s.nextRunAt asc")
    List<Schedule> findDue(@Param("now") Instant now, Pageable pageable);
}
