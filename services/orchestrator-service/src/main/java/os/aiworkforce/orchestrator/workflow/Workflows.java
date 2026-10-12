// @find: workflows repository, list workflows, workflow by id in workspace, Workflows
// @what: Spring Data repository for Workflow rows, always keyed on the workspace.
// @flow: Used by WorkflowService
package os.aiworkforce.orchestrator.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * Spring Data scans for top-level repository interfaces only, so this sits in its own file like
 * every other repository in this service.
 */

/** Spring Data repository for {@link Workflow} rows (table {@code workflows}). */
public interface Workflows extends JpaRepository<Workflow, UUID> {

    // @find: list workflows of a workspace, most recently changed first
    /** A workspace's workflows, most recently changed first, one page at a time. */
    List<Workflow> findByOrgIdOrderByUpdatedAtDesc(UUID orgId, Pageable page);

    // @find: workflow by id in workspace
    /** One workflow, only when it belongs to the given workspace. */
    Optional<Workflow> findByIdAndOrgId(UUID id, UUID orgId);
}
