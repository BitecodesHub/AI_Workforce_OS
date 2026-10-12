// @find: tests for workflow graph, parse canvas json, shape errors, too many steps, duplicate step id, bad agent id, long instruction, connectors normalised, stored map round trip, cycle detection, execution order, reading order, predecessors, WorkflowGraphTest, WorkflowGraph
// @what: Tests for WorkflowGraph: strict parsing of the canvas JSON, its stored form, and the loop and execution-order questions.
// @flow: Exercises WorkflowGraph directly
package os.aiworkforce.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The canvas JSON is read strictly, stored canonically, and answers loop and order questions. */
class WorkflowGraphTest {

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private static String problem(Object raw) {
        ApiException refused = catchThrowableOfType(() -> WorkflowGraph.parse(raw), ApiException.class);
        assertThat(refused).as("expected a refusal").isNotNull();
        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused.details().get("field")).isEqualTo("graph");
        return String.valueOf(refused.details().get("problem"));
    }

    // ---- Parsing ---------------------------------------------------------------------------------

    // @find: test parse trims and normalises, workflow graph
    @Test
    @DisplayName("a step is read with its text trimmed, connectors lowercased and de-duplicated, and type defaulted")
    void parsesAndNormalises() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "n1");
        node.put("agentId", A.toString());
        node.put("title", "  Draft  ");
        node.put("instruction", "  Write the reply.  ");
        node.put("connectors", List.of(" Gmail", "gmail", "SLACK"));
        node.put("x", 120);
        node.put("y", 40.5);

        WorkflowGraph graph = WorkflowGraph.parse(Map.of("nodes", List.of(node)));

        WorkflowGraph.Node parsed = graph.nodes().get(0);
        assertThat(parsed.type()).isEqualTo("agent");
        assertThat(parsed.agentId()).isEqualTo(A);
        assertThat(parsed.title()).isEqualTo("Draft");
        assertThat(parsed.instruction()).isEqualTo("Write the reply.");
        assertThat(parsed.connectors()).containsExactly("gmail", "slack");
        assertThat(parsed.x()).isEqualTo(120);
        assertThat(parsed.y()).isEqualTo(40.5);
        assertThat(graph.edges()).isEmpty();
    }

    // @find: test null graph is empty canvas, workflow graph
    @Test
    @DisplayName("a missing graph is the empty canvas, and an empty title or agent reads as none")
    void emptyValues() {
        assertThat(WorkflowGraph.parse(null).nodes()).isEmpty();
        WorkflowGraph graph = WorkflowGraphs.canvas().step("n1", null, "", 0, 0).titled("   ").graph();
        assertThat(graph.nodes().get(0).agentId()).isNull();
        assertThat(graph.nodes().get(0).title()).isNull();
    }

    // @find: test shape errors refused, workflow graph
    @Test
    @DisplayName("anything of the wrong shape is refused naming the graph field, with a sentence a person can read")
    void refusesBadShapes() {
        assertThat(problem("nodes")).contains("could not be read");
        assertThat(problem(Map.of("nodes", "n1"))).contains("steps could not be read");
        assertThat(problem(Map.of("nodes", List.of(Map.of("agentId", A.toString()))))).contains("no usable id");
        assertThat(problem(Map.of("nodes", List.of(Map.of("id", "bad id with spaces"))))).contains("no usable id");
        assertThat(problem(Map.of("nodes", List.of(Map.of("id", "n1", "agentId", "not-an-id")))))
                .contains("agent that does not exist");
        assertThat(problem(Map.of("nodes", List.of(Map.of("id", "n1", "type", "connector")))))
                .contains("not supported");
        assertThat(problem(Map.of("nodes", List.of(Map.of("id", "n1", "instruction", "x".repeat(10_001))))))
                .contains("at most 10,000 characters");
        assertThat(problem(Map.of("nodes", List.of(Map.of("id", "n1", "connectors", List.of("bad server!"))))))
                .contains("connector");
        assertThat(problem(Map.of("edges", List.of(Map.of("from", "n1")))))
                .contains("no usable id");
    }

    // @find: test duplicate step id refused, workflow graph
    @Test
    @DisplayName("two steps or two connections with one id are refused")
    void refusesDuplicateIds() {
        Map<String, Object> twice = WorkflowGraphs.canvas()
                .step("n1", A, "One", 0, 0)
                .step("n1", B, "Two", 0, 0)
                .map();
        assertThat(problem(twice)).contains("Two steps share the id \"n1\"");

        Map<String, Object> edgesTwice = new LinkedHashMap<>(WorkflowGraphs.canvas()
                .step("n1", A, "One", 0, 0)
                .step("n2", B, "Two", 0, 0)
                .map());
        edgesTwice.put("edges", List.of(Map.of("id", "e1", "from", "n1", "to", "n2"), Map.of("id", "e1", "from", "n2", "to", "n1")));
        assertThat(problem(edgesTwice)).contains("Two connections share the id");
    }

    // @find: test size limits, 40 steps, workflow graph
    @Test
    @DisplayName("forty steps are accepted and forty-one refused; too many connections are refused")
    void sizeLimits() {
        WorkflowGraphs forty = WorkflowGraphs.canvas();
        for (int i = 0; i < WorkflowGraph.MAX_NODES; i++) {
            forty.step("n" + i, A, "Step", i * 10, 0);
        }
        assertThat(forty.graph().nodes()).hasSize(40);
        forty.step("n40", A, "One more", 0, 0);
        assertThat(problem(forty.map())).contains("at most 40 steps");

        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i <= WorkflowGraph.MAX_EDGES; i++) {
            edges.add(Map.of("id", "e" + i, "from", "a", "to", "b"));
        }
        assertThat(problem(Map.of("edges", edges))).contains("at most 160 connections");
    }

    // @find: test coordinates clamped, workflow graph
    @Test
    @DisplayName("positions far off the canvas are clamped and a non-number position is 0")
    void clampsPositions() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "n1");
        node.put("x", 1e12);
        node.put("y", "high");
        WorkflowGraph.Node parsed = WorkflowGraph.parse(Map.of("nodes", List.of(node))).nodes().get(0);
        assertThat(parsed.x()).isEqualTo(WorkflowGraph.MAX_COORDINATE);
        assertThat(parsed.y()).isZero();
    }

    // @find: test stored map round trip, workflow graph
    @Test
    @DisplayName("the stored map holds every field and parses back to the same graph")
    void roundTrip() {
        WorkflowGraph graph = WorkflowGraphs.canvas()
                .step("n1", A, "Research", 0, 0, "github")
                .titled("Research")
                .step("n2", null, "Draft", 300, 0)
                .edge("n1", "n2")
                .graph();

        Map<String, Object> stored = graph.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> second = ((List<Map<String, Object>>) stored.get("nodes")).get(1);

        assertThat(second).containsOnlyKeys("id", "type", "agentId", "title", "instruction", "connectors", "x", "y");
        assertThat(second.get("agentId")).isNull();
        assertThat(WorkflowGraph.parse(stored)).isEqualTo(graph);
    }

    // ---- Graph questions -------------------------------------------------------------------------

    // @find: test cycle detection, loop, workflow graph
    @Test
    @DisplayName("a loop is found with the steps on it in order; a branching graph without one has none")
    void findsLoops() {
        WorkflowGraph loop = WorkflowGraphs.canvas()
                .step("a", A, "1", 0, 0)
                .step("b", A, "2", 100, 0)
                .step("c", B, "3", 200, 0)
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", "b")
                .graph();
        assertThat(loop.cycle()).containsExactly("b", "c");

        WorkflowGraph diamond = WorkflowGraphs.canvas()
                .step("a", A, "1", 0, 0)
                .step("b", A, "2", 100, 0)
                .step("c", B, "3", 100, 100)
                .step("d", B, "4", 200, 0)
                .edge("a", "b")
                .edge("a", "c")
                .edge("b", "d")
                .edge("c", "d")
                .graph();
        assertThat(diamond.cycle()).isEmpty();
    }

    // @find: test execution order, topological order, reading order, workflow graph
    @Test
    @DisplayName("steps run after everything that hands over to them, and otherwise in reading order")
    void executionOrder() {
        WorkflowGraph graph = WorkflowGraphs.canvas()
                .step("draft", A, "Draft", 400, 0)
                .step("research", A, "Research", 0, 100)
                .step("facts", B, "Facts", 0, 0)
                .step("review", B, "Review", 800, 0)
                .edge("research", "draft")
                .edge("facts", "draft")
                .edge("draft", "review")
                .graph();

        assertThat(graph.executionOrder()).extracting(WorkflowGraph.Node::id)
                .containsExactly("facts", "research", "draft", "review");
        assertThat(graph.predecessors().get("draft")).containsExactly("research", "facts");
    }

    // @find: test execution order refuses loop, workflow graph
    @Test
    @DisplayName("execution order is never computed for a graph with a loop")
    void orderRefusesLoops() {
        WorkflowGraph loop = WorkflowGraphs.canvas()
                .step("a", A, "1", 0, 0)
                .step("b", B, "2", 100, 0)
                .edge("a", "b")
                .edge("b", "a")
                .graph();
        assertThatThrownBy(loop::executionOrder).isInstanceOf(IllegalStateException.class);
    }

    // @find: test dangling and repeated edges ignored by graph questions, workflow graph
    @Test
    @DisplayName("connections to missing steps, to themselves or repeated are left out of the graph questions")
    void usableEdgesOnly() {
        WorkflowGraph graph = WorkflowGraphs.canvas()
                .step("a", A, "1", 0, 0)
                .step("b", B, "2", 100, 0)
                .edge("a", "b")
                .edge("a", "b")
                .edge("a", "a")
                .edge("a", "ghost")
                .graph();
        assertThat(graph.usableEdges()).hasSize(1);
        assertThat(graph.predecessors().get("b")).containsExactly("a");
        assertThat(graph.cycle()).isEmpty();
        assertThat(graph.executionOrder()).extracting(WorkflowGraph.Node::id).containsExactly("a", "b");
    }
}
