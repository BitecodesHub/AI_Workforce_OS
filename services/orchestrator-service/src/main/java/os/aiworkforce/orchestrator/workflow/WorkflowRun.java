// @find: workflow run, run of a workflow, goal created by workflow, graph snapshot, version snapshot, node ids by task position, run inputs, workflow_runs table, WorkflowRun
// @what: JPA entity linking one run of a workflow to the goal it created, with the version and a copy of the graph it ran.
// @flow: Written by WorkflowService.run; read by the run history and by GoalTimelineService for the live view and the replay
package os.aiworkforce.orchestrator.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One time a workflow ran.
 *
 * <p>The goal does the work; this row remembers where it came from. The graph is copied, not
 * referenced, so a replay draws the canvas the run actually followed even after the workflow has
 * been edited, and {@code nodeIds} maps each task position in the goal back to its node on that
 * copy. Who started it is the inherited {@code createdBy}. Deleting the workflow keeps the row,
 * with {@code workflowId} cleared and the name it ran under kept, so the replay still works.
 */
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun extends OrgScopedEntity {

    /** The workflow that ran; null once that workflow has been deleted, since the run itself is kept. */
    @Column(name = "workflow_id")
    private UUID workflowId;

    /** The workflow's name when it ran, for a run whose workflow has since been renamed or deleted. */
    @Column(name = "workflow_name", nullable = false)
    private String workflowName = "";

    /** The workflow's optimistic-lock version when it ran. */
    @Column(name = "workflow_version", nullable = false)
    private long workflowVersion;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    /** The canvas as it was when the run started. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> graph = new LinkedHashMap<>();

    /** Node id of each task, by the task's position in the goal. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_ids", nullable = false)
    private List<String> nodeIds = new ArrayList<>();

    /** The values the person filled in when starting the run, by name. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> inputs = new LinkedHashMap<>();

    /** The workflow this run belongs to; null when it has been deleted since. */
    public UUID getWorkflowId() {
        return workflowId;
    }

    /** Sets the workflow this run belongs to. */
    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    /** The workflow's name when it ran. */
    public String getWorkflowName() {
        return workflowName;
    }

    /** Sets the workflow's name as it was when it ran; null is stored as empty. */
    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName == null ? "" : workflowName;
    }

    /** The workflow version that ran. */
    public long getWorkflowVersion() {
        return workflowVersion;
    }

    /** Sets the workflow version that ran. */
    public void setWorkflowVersion(long workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    /** The goal this run created. */
    public UUID getGoalId() {
        return goalId;
    }

    /** Sets the goal this run created. */
    public void setGoalId(UUID goalId) {
        this.goalId = goalId;
    }

    /** The canvas copy, in the shape {@link WorkflowGraph#toMap()} writes. */
    public Map<String, Object> getGraph() {
        return graph;
    }

    /** Stores a copy of the canvas that ran. */
    public void setGraph(Map<String, Object> graph) {
        this.graph = graph == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graph);
    }

    /** Node ids by task position. */
    public List<String> getNodeIds() {
        return nodeIds;
    }

    /** Sets the node ids by task position. */
    public void setNodeIds(List<String> nodeIds) {
        this.nodeIds = nodeIds == null ? new ArrayList<>() : new ArrayList<>(nodeIds);
    }

    /** The values filled in at the start, by name. */
    public Map<String, Object> getInputs() {
        return inputs;
    }

    /** Stores the values filled in at the start. */
    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputs);
    }
}
