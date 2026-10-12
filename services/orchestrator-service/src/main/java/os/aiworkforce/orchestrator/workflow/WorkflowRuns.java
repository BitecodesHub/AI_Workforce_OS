// @find: workflow runs repository, run history of a workflow, run by goal, latest run per workflow, WorkflowRuns
// @what: Spring Data repository for WorkflowRun rows: a workflow's history, the run behind a goal, and each workflow's latest run.
// @flow: Used by WorkflowService and GoalTimelineService
package os.aiworkforce.orchestrator.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link WorkflowRun} rows (table {@code workflow_runs}). */
public interface WorkflowRuns extends JpaRepository<WorkflowRun, UUID> {

    // @find: run history of a workflow, newest first
    /** One workflow's runs, newest first, one page at a time. */
    List<WorkflowRun> findByOrgIdAndWorkflowIdOrderByCreatedAtDesc(UUID orgId, UUID workflowId, Pageable page);

    // @find: workflow run behind a goal
    /** The run that created a goal, if a workflow created it. */
    Optional<WorkflowRun> findByOrgIdAndGoalId(UUID orgId, UUID goalId);

    // @find: latest run of each workflow, workflows list last run
    /**
     * The newest run of each of the given workflows, in one query, so the list can show every
     * workflow's last run without a lookup per row.
     */
    @Query(
            value = "select distinct on (r.workflow_id) r.* from workflow_runs r"
                    + " where r.org_id = :orgId and r.workflow_id in (:workflowIds)"
                    + " order by r.workflow_id, r.created_at desc",
            nativeQuery = true)
    List<WorkflowRun> latestOf(@Param("orgId") UUID orgId, @Param("workflowIds") Collection<UUID> workflowIds);
}
