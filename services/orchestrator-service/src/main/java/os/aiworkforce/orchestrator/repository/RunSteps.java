// @find: run steps repository, trace steps of a run, highest step position, practice data used, last step of kind, RunSteps
// @what: Spring Data repository for RunStep rows.
// @flow: Used by the run engine and the trace view.
// @find: run steps repository, trace steps of a run, highest step position, practice data used, last step of kind, RunSteps
// @what: Spring Data repository for RunStep rows.
// @flow: Used by the run engine and the trace view.
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

    // @find: list trace steps of a run
    // @find: list trace steps of a run
    List<RunStep> findByRunIdOrderByPosition(UUID runId);

    // @find: highest step position, next step number
    // @find: highest step position, next step number
    @Query("select coalesce(max(s.position), -1) from RunStep s where s.runId = :runId")
    int highestPosition(@Param("runId") UUID runId);

    // @find: run used provider
    // @find: run used provider
    boolean existsByRunIdAndProviderId(UUID runId, String providerId);

    // @find: did run use practice or sandbox data
    // @find: did run use practice or sandbox data
    /**
     * Whether a tool call in the run succeeded against practice data rather than a real account:
     * an email "sent" that way never left the machine, and the answer has to say so.
     */
    @Query(
            value = "select exists (select 1 from run_steps s where s.run_id = :runId and s.kind = 'tool_call'"
                    + " and s.detail ->> 'mode' = 'sandbox' and s.detail ->> 'status' = 'SUCCEEDED')",
            nativeQuery = true)
    boolean usedPracticeData(@Param("runId") UUID runId);

    // @find: latest step of a kind
    // @find: latest step of a kind
    /** The run's latest step of one kind, such as its newest approval or question. */
    Optional<RunStep> findFirstByRunIdAndKindOrderByPositionDesc(UUID runId, String kind);
}
