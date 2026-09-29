package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.RunStep;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface RunSteps extends JpaRepository<RunStep, UUID> {

    List<RunStep> findByRunIdOrderByPosition(UUID runId);

    @Query("select coalesce(max(s.position), -1) from RunStep s where s.runId = :runId")
    int highestPosition(@Param("runId") UUID runId);

    boolean existsByRunIdAndProviderId(UUID runId, String providerId);

    /** The run's latest step of one kind, such as its newest approval or question. */
    Optional<RunStep> findFirstByRunIdAndKindOrderByPositionDesc(UUID runId, String kind);
}
