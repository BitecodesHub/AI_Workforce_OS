// @find: run takeovers repository, find takeover of a run, who has control, RunTakeovers
// @what: Spring Data repository for RunTakeover rows, one per run.
// @flow: Used by TakeoverService
package os.aiworkforce.orchestrator.takeover;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RunTakeover} rows, keyed by run id. */
public interface RunTakeovers extends JpaRepository<RunTakeover, UUID> {

    /** A run's takeover, only when it belongs to the given workspace. */
    Optional<RunTakeover> findByRunIdAndOrgId(UUID runId, UUID orgId);
}
