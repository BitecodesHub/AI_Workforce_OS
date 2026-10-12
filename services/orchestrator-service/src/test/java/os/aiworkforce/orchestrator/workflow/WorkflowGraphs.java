// @find: tests for workflows, test fixture, canvas json builder, step map, connection map, WorkflowGraphs
// @what: Test helper that builds canvas JSON maps the way the console sends them.
// @flow: Used by the workflow tests
package os.aiworkforce.orchestrator.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the canvas JSON the console sends, as Jackson would decode it. */
final class WorkflowGraphs {

    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();

    private WorkflowGraphs() {}

    /** An empty canvas to add to. */
    static WorkflowGraphs canvas() {
        return new WorkflowGraphs();
    }

    /** Adds a step at a position with an agent, an instruction and connectors. */
    WorkflowGraphs step(String id, UUID agentId, String instruction, double x, double y, String... connectors) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "agent");
        node.put("agentId", agentId == null ? null : agentId.toString());
        node.put("instruction", instruction);
        node.put("connectors", List.of(connectors));
        node.put("x", x);
        node.put("y", y);
        nodes.add(node);
        return this;
    }

    /** Gives the last added step a title. */
    WorkflowGraphs titled(String title) {
        nodes.get(nodes.size() - 1).put("title", title);
        return this;
    }

    /** Adds a connection: {@code from} hands over to {@code to}. */
    WorkflowGraphs edge(String from, String to) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "e-" + from + "-" + to + "-" + edges.size());
        edge.put("from", from);
        edge.put("to", to);
        edges.add(edge);
        return this;
    }

    /** The canvas as a decoded JSON map. */
    Map<String, Object> map() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodes", new ArrayList<>(nodes));
        map.put("edges", new ArrayList<>(edges));
        return map;
    }

    /** The canvas parsed. */
    WorkflowGraph graph() {
        return WorkflowGraph.parse(map());
    }
}
