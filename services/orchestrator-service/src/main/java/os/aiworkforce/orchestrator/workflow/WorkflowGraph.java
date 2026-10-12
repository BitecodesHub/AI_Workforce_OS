// @find: workflow graph, canvas json, parse workflow graph, nodes and edges, step node, hand-off edge, cycle detection, loop in workflow, topological order, execution order, predecessors, graph limits, 40 steps, WorkflowGraph
// @what: The workflow canvas as typed records: strict parsing of the JSON the canvas sends, the stored map form, and the graph questions (predecessors, loops, execution order).
// @flow: Parsed by WorkflowService on save and run; checked by WorkflowValidator; compiled into goal tasks by WorkflowService.run
package os.aiworkforce.orchestrator.workflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import os.aiworkforce.platform.error.ApiException;

/**
 * The canvas: steps (nodes) and the hand-offs between them (edges).
 *
 * <p>{@link #parse} is deliberately strict about shape and lenient about meaning. A value of the
 * wrong type, an over-long text or more steps than a workflow may hold is refused here, so nothing
 * unbounded is ever stored. Whether the graph makes sense - an agent on every step, no loops, the
 * connectors granted - is {@link WorkflowValidator}'s question, answered with every problem at
 * once rather than the first one.
 *
 * @param nodes the steps, in the order the canvas sent them
 * @param edges the hand-offs; each says the step {@code from} hands its result to {@code to}
 */
public record WorkflowGraph(List<Node> nodes, List<Edge> edges) {

    /** The most steps one workflow may hold. GoalService allows fifty tasks; forty leaves room. */
    public static final int MAX_NODES = 40;
    /** The most hand-offs; four per step is far more than a readable canvas has. */
    public static final int MAX_EDGES = 160;
    /** A step title becomes a task title, which the goal endpoint caps at 200 characters. */
    public static final int MAX_TITLE = 200;
    /** A step instruction becomes a task instruction, which the goal endpoint caps at 10,000. */
    public static final int MAX_INSTRUCTION = 10_000;
    /** The most connectors one step can mark as required. */
    public static final int MAX_CONNECTORS = 10;
    /** Positions further out than this are a broken client, not a big canvas. */
    static final double MAX_COORDINATE = 100_000;

    /** The only kind of step there is today: an agent doing an instruction. */
    public static final String AGENT = "agent";

    /** Node and edge ids: short, and safe to echo back in a message or an element id. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Connector server ids, as the catalogue spells them. */
    private static final Pattern CONNECTOR = Pattern.compile("[a-z0-9_-]{1,64}");

    /**
     * One step: an agent and what it should do.
     *
     * @param id stable within the workflow; edges refer to it
     * @param type always {@link #AGENT} today
     * @param agentId the agent that does the step, or null while the person has not chosen one
     * @param title an optional short name; the task is named after the agent and instruction
     *     without one
     * @param instruction what the agent should do; may hold {@code {{name}}} placeholders
     * @param connectors connector server ids the step needs, lowercase and distinct
     * @param x the step's left edge on the canvas
     * @param y the step's top edge on the canvas
     */
    public record Node(
            String id,
            String type,
            UUID agentId,
            String title,
            String instruction,
            List<String> connectors,
            double x,
            double y) {

        /** The instruction, never null. */
        public String instructionText() {
            return instruction == null ? "" : instruction;
        }

        /** The title, trimmed, or empty when there is none. */
        public String titleText() {
            return title == null ? "" : title.strip();
        }
    }

    /**
     * One hand-off: when {@code from} completes, its result goes to {@code to}, which waits for it.
     *
     * @param id stable within the workflow
     * @param from the node id that hands over
     * @param to the node id that receives
     */
    public record Edge(String id, String from, String to) {}

    /** Copies the lists, so a parsed graph cannot be changed from outside. */
    public WorkflowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A canvas with nothing on it. */
    public static WorkflowGraph empty() {
        return new WorkflowGraph(List.of(), List.of());
    }

    // ---- Parsing ---------------------------------------------------------------------------------

    // @find: parse workflow graph json, graph shape errors
    /**
     * Reads the canvas JSON (as Jackson decoded it, or as it was stored) into typed records.
     *
     * <p>Refuses, with a {@code VALIDATION_FAILED} naming the field {@code graph}, anything of the
     * wrong shape: a non-object, steps or connections that are not lists, an id that is missing,
     * malformed or repeated, an agent id that is not an id, text over its limit, more than {@link
     * #MAX_NODES} steps or {@link #MAX_EDGES} connections. Trims texts, lowercases and
     * de-duplicates connectors, and clamps positions. A null graph is the empty canvas.
     *
     * @param raw the decoded JSON value
     * @return the parsed graph
     */
    public static WorkflowGraph parse(Object raw) {
        if (raw == null) {
            return empty();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw shape("The workflow could not be read. Reload the page and try again.");
        }
        List<?> rawNodes = list(map.get("nodes"), "steps");
        List<?> rawEdges = list(map.get("edges"), "connections");
        if (rawNodes.size() > MAX_NODES) {
            throw shape("A workflow can hold at most " + MAX_NODES + " steps.");
        }
        if (rawEdges.size() > MAX_EDGES) {
            throw shape("A workflow can hold at most " + MAX_EDGES + " connections.");
        }
        List<Node> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        for (Object item : rawNodes) {
            Node node = node(item);
            if (!nodeIds.add(node.id())) {
                throw shape("Two steps share the id \"" + node.id() + "\". Reload the page and try again.");
            }
            nodes.add(node);
        }
        List<Edge> edges = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>();
        for (Object item : rawEdges) {
            Edge edge = edge(item);
            if (!edgeIds.add(edge.id())) {
                throw shape("Two connections share the id \"" + edge.id() + "\". Reload the page and try again.");
            }
            edges.add(edge);
        }
        return new WorkflowGraph(nodes, edges);
    }

    /** Reads one step. */
    private static Node node(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw shape("A step on the canvas could not be read.");
        }
        String id = id(map.get("id"), "step");
        Object rawType = map.get("type");
        String type = rawType == null ? AGENT : String.valueOf(rawType).strip().toLowerCase(Locale.ROOT);
        if (!AGENT.equals(type)) {
            throw shape("Steps of the kind \"" + type + "\" are not supported.");
        }
        UUID agentId = agentId(map.get("agentId"));
        String title = text(map.get("title"), "A step title", MAX_TITLE);
        String instruction = text(map.get("instruction"), "An instruction", MAX_INSTRUCTION);
        List<String> connectors = connectors(map.get("connectors"));
        return new Node(
                id,
                AGENT,
                agentId,
                title.isEmpty() ? null : title,
                instruction,
                connectors,
                coordinate(map.get("x")),
                coordinate(map.get("y")));
    }

    /** Reads one connection. A missing id is made from its two ends, which are unique anyway. */
    private static Edge edge(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw shape("A connection on the canvas could not be read.");
        }
        String from = id(map.get("from"), "connection start");
        String to = id(map.get("to"), "connection end");
        Object rawId = map.get("id");
        String id = rawId == null ? "e-" + from + "-" + to : id(rawId, "connection");
        if (id.length() > 64) {
            id = id.substring(0, 64);
        }
        return new Edge(id, from, to);
    }

    /** A list value, or empty when absent; anything else is a shape error naming what it should hold. */
    private static List<?> list(Object value, String what) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> items) {
            return items;
        }
        throw shape("The workflow's " + what + " could not be read.");
    }

    /** A node or edge id: a string of letters, digits, dashes or underscores, at most 64 long. */
    private static String id(Object value, String what) {
        String id = value == null ? "" : String.valueOf(value).strip();
        if (!ID.matcher(id).matches()) {
            throw shape("A " + what + " on the canvas has no usable id.");
        }
        return id;
    }

    /** An agent id: null, an empty string (no agent yet) or a UUID. */
    private static UUID agentId(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            throw shape("A step names an agent that does not exist.");
        }
    }

    /** A trimmed text, empty when absent, refused when longer than the limit. */
    private static String text(Object value, String what, int max) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String string)) {
            throw shape(what + " could not be read.");
        }
        String text = string.strip();
        if (text.length() > max) {
            throw shape(what + " can be at most " + String.format(Locale.ROOT, "%,d", max) + " characters.");
        }
        return text;
    }

    /** Connector ids, trimmed, lowercased and de-duplicated in order; at most {@link #MAX_CONNECTORS}. */
    private static List<String> connectors(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw shape("A step's connectors could not be read.");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : items) {
            String id = item == null ? "" : String.valueOf(item).strip().toLowerCase(Locale.ROOT);
            if (!CONNECTOR.matcher(id).matches()) {
                throw shape("A step names a connector that could not be read.");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_CONNECTORS) {
            throw shape("A step can need at most " + MAX_CONNECTORS + " connectors.");
        }
        return List.copyOf(ids);
    }

    /** A finite number clamped to the canvas bounds; anything else is 0. */
    private static double coordinate(Object value) {
        if (!(value instanceof Number number)) {
            return 0;
        }
        double coordinate = number.doubleValue();
        if (!Double.isFinite(coordinate)) {
            return 0;
        }
        return Math.max(-MAX_COORDINATE, Math.min(MAX_COORDINATE, coordinate));
    }

    /** A shape refusal: 422 naming the field {@code graph}. */
    private static ApiException shape(String problem) {
        return ApiException.validation("graph", problem);
    }

    // ---- Stored form -----------------------------------------------------------------------------

    // @find: workflow graph to json map, stored canvas
    /**
     * The map stored in the JSON column and sent to the canvas: {@code nodes} and {@code edges},
     * each entry with every field present (null where unset), so the stored form is canonical.
     */
    public Map<String, Object> toMap() {
        List<Map<String, Object>> nodeMaps = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.id());
            entry.put("type", node.type());
            entry.put("agentId", node.agentId() == null ? null : node.agentId().toString());
            entry.put("title", node.title());
            entry.put("instruction", node.instructionText());
            entry.put("connectors", List.copyOf(node.connectors()));
            entry.put("x", node.x());
            entry.put("y", node.y());
            nodeMaps.add(entry);
        }
        List<Map<String, Object>> edgeMaps = new ArrayList<>();
        for (Edge edge : edges) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", edge.id());
            entry.put("from", edge.from());
            entry.put("to", edge.to());
            edgeMaps.add(entry);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodes", nodeMaps);
        map.put("edges", edgeMaps);
        return map;
    }

    // ---- Graph questions -------------------------------------------------------------------------

    /** The step with this id, if it is on the canvas. */
    public Optional<Node> node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    /**
     * The edges that join two different steps both on the canvas, each pair once. Dangling,
     * self-joining and repeated edges are the validator's to report; the graph questions below
     * ignore them so one bad edge does not hide every other problem.
     */
    public List<Edge> usableEdges() {
        Set<String> ids = new HashSet<>();
        for (Node node : nodes) {
            ids.add(node.id());
        }
        Set<String> seen = new HashSet<>();
        List<Edge> usable = new ArrayList<>();
        for (Edge edge : edges) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to()) || edge.from().equals(edge.to())) {
                continue;
            }
            if (seen.add(edge.from() + "\u0000" + edge.to())) {
                usable.add(edge);
            }
        }
        return usable;
    }

    // @find: predecessors of a step, which steps hand over to this one
    /** For every step, the steps that hand over to it, in canvas order. */
    public Map<String, List<String>> predecessors() {
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (Node node : nodes) {
            before.put(node.id(), new ArrayList<>());
        }
        for (Edge edge : usableEdges()) {
            before.get(edge.to()).add(edge.from());
        }
        return before;
    }

    /** For every step, the steps it hands over to, in canvas order. */
    public Map<String, List<String>> successors() {
        Map<String, List<String>> after = new LinkedHashMap<>();
        for (Node node : nodes) {
            after.put(node.id(), new ArrayList<>());
        }
        for (Edge edge : usableEdges()) {
            after.get(edge.from()).add(edge.to());
        }
        return after;
    }

    // @find: find loop in workflow, cycle detection, steps that wait for each other
    /**
     * One loop in the hand-offs, as the ids of the steps on it in order, or empty when there is
     * none. A loop means every step on it waits for another, so none of them could ever start.
     */
    public List<String> cycle() {
        Map<String, List<String>> after = successors();
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (Node node : nodes) {
            List<String> found = visit(node.id(), after, state, path);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    /**
     * Depth-first walk for {@link #cycle}: state 1 is "on the current path", 2 is "fully checked".
     * Meeting a step already on the path closes a loop, which is returned from that step on.
     */
    private static List<String> visit(
            String id, Map<String, List<String>> after, Map<String, Integer> state, List<String> path) {
        Integer seen = state.get(id);
        if (seen != null && seen == 2) {
            return List.of();
        }
        if (seen != null && seen == 1) {
            return List.copyOf(path.subList(path.indexOf(id), path.size()));
        }
        state.put(id, 1);
        path.add(id);
        for (String next : after.getOrDefault(id, List.of())) {
            List<String> found = visit(next, after, state, path);
            if (!found.isEmpty()) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
        return List.of();
    }

    // @find: execution order, topological order, task positions from canvas
    /**
     * The steps in the order their tasks are numbered: every step after all the steps that hand
     * over to it, and among steps that could go next, the one further left first, then the one
     * higher up, then canvas order. That is the order a person reads the canvas in, so "step 3" in
     * the goal is the third step they see. Must only be called on a graph without loops.
     *
     * @throws IllegalStateException when the graph has a loop
     */
    public List<Node> executionOrder() {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i).id(), i);
        }
        Map<String, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            byId.put(node.id(), node);
        }
        Map<String, Integer> waiting = new HashMap<>();
        predecessors().forEach((id, before) -> waiting.put(id, before.size()));
        Map<String, List<String>> after = successors();
        Comparator<Node> readingOrder = Comparator.comparingDouble(Node::x)
                .thenComparingDouble(Node::y)
                .thenComparingInt(node -> index.get(node.id()));
        PriorityQueue<Node> ready = new PriorityQueue<>(readingOrder);
        for (Node node : nodes) {
            if (waiting.get(node.id()) == 0) {
                ready.add(node);
            }
        }
        List<Node> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Node next = ready.poll();
            order.add(next);
            for (String successor : after.get(next.id())) {
                int remaining = waiting.merge(successor, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(byId.get(successor));
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException("The workflow graph has a loop");
        }
        return order;
    }

    /** The distinct agents the steps name, in canvas order. */
    public Set<UUID> agentIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (node.agentId() != null) {
                ids.add(node.agentId());
            }
        }
        return ids;
    }

    /** Whether any step needs a connector. */
    public boolean needsConnectors() {
        return nodes.stream().map(Node::connectors).anyMatch(list -> !list.isEmpty());
    }
}
