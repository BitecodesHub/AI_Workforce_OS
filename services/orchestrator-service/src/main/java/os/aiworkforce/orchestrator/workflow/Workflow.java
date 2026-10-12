// @find: workflow entity, canvas workflow, repeatable work, saved graph, workflow name, workflow description, workflow version, optimistic lock, workflows table, Workflow
// @what: JPA entity for one workflow: a named canvas of agent steps and the hand-offs between them, stored as JSON.
// @flow: Stored in the workflows table by WorkflowService; its graph is parsed by WorkflowGraph and checked by WorkflowValidator
package os.aiworkforce.orchestrator.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A workflow: repeatable work a person drew on the canvas.
 *
 * <p>The graph is kept as the JSON map the canvas sends, after {@link WorkflowGraph#parse} has
 * checked and normalised it, rather than as rows per node and edge. A canvas is always read and
 * written whole, never queried across, and a JSON column keeps a save to one row. The inherited
 * {@code version} is the optimistic lock a save is checked against.
 */
@Entity
@Table(name = "workflows")
public class Workflow extends OrgScopedEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description = "";

    /** The canvas, in the shape {@link WorkflowGraph#toMap()} writes. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> graph = new LinkedHashMap<>();

    /** The workflow's name, as the list and the editor show it. */
    public String getName() {
        return name;
    }

    /** Sets the name; WorkflowService trims and checks it first. */
    public void setName(String name) {
        this.name = name;
    }

    /** The one-line description, empty when there is none. */
    public String getDescription() {
        return description;
    }

    /** Sets the description; null is stored as empty. */
    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    /** The stored canvas map; parse it with {@link WorkflowGraph#parse} before reading it. */
    public Map<String, Object> getGraph() {
        return graph;
    }

    /** Replaces the canvas with an already validated graph map. */
    public void setGraph(Map<String, Object> graph) {
        this.graph = graph == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graph);
    }
}
