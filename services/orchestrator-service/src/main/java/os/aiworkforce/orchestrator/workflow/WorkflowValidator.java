// @find: workflow validation, check workflow, can this workflow run, missing agent, retired agent, paused agent, missing instruction, unknown connector, connector not granted, missing grant, loop, cycle, dangling connection, duplicate connection, agent repeats, warn on save refuse on run, WorkflowValidator
// @what: Checks a workflow graph against the product rules and reports every problem at once, as errors and warnings; a missing connector grant warns on save and refuses a run.
// @flow: Called by WorkflowService on save, on read (to show what is wrong) and before a run; reads agents and their connector grants in two queries
package os.aiworkforce.orchestrator.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.catalog.ConnectorInfo;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.repository.Agents;

/**
 * The rules a workflow is held to.
 *
 * <p>Every problem is reported, not only the first, so the canvas can mark each step that needs
 * attention. One kind of problem is softer while a person is still building: a connector the
 * agent has not been granted. Saving with it is allowed, with a warning, because the fix is
 * elsewhere (grant the connector) and should not hold the canvas hostage. Running with it is
 * refused: the run would fail at that step. Every agent must exist and be active on save and on
 * run alike; a paused agent is resumed, or its steps given to another agent.
 *
 * <p>An agent may do at most two steps, the same limit GoalService puts on every goal, so a
 * workflow that saves is one that can become a goal.
 */
@Component
public class WorkflowValidator {

    /** When the check runs: a save tolerates what only a run cannot. */
    public enum Mode {
        SAVE,
        RUN
    }

    /** The most steps one agent may take, matching GoalService's limit for any goal. */
    static final int MAX_STEPS_PER_AGENT = 2;

    /**
     * One problem with the workflow.
     *
     * @param code a stable machine name, such as {@code cycle} or {@code grant_missing}
     * @param message one plain sentence a person can act on
     * @param nodeId the step it concerns, when it concerns one
     * @param edgeId the connection it concerns, when it concerns one
     */
    public record Problem(String code, String message, String nodeId, String edgeId) {}

    /**
     * What a check found.
     *
     * @param errors problems that stop this save or run
     * @param warnings problems a save tolerates and a run would not
     * @param agents the workspace's agents by id, as the check read them, so a caller can name them
     */
    public record Report(List<Problem> errors, List<Problem> warnings, Map<UUID, Agent> agents) {

        /** Whether nothing stops the save or run. */
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final Agents agents;
    private final WorkflowReads reads;
    private final ConnectorCatalog catalog;

    /** Creates the validator with the agent repository, the batched grant query and the connector catalogue. */
    public WorkflowValidator(Agents agents, WorkflowReads reads, ConnectorCatalog catalog) {
        this.agents = agents;
        this.reads = reads;
        this.catalog = catalog;
    }

    // @find: check workflow graph, validate workflow on save or run
    /**
     * Checks a parsed graph for one workspace.
     *
     * <p>Flow: reads the workspace's agents (one query) and, when any step needs a connector, the
     * enabled grants of the agents on the canvas (one more query); then checks the steps, the
     * connections, loops and the per-agent step limit. Writes nothing.
     *
     * @param mode {@link Mode#SAVE} reports a missing grant as a warning, {@link Mode#RUN} as an
     *     error
     * @return every problem found, errors and warnings apart
     */
    public Report check(UUID orgId, WorkflowGraph graph, Mode mode) {
        Map<UUID, Agent> byId = new LinkedHashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            byId.put(agent.getId(), agent);
        }
        List<Problem> errors = new ArrayList<>();
        List<Problem> warnings = new ArrayList<>();
        List<Problem> soft = mode == Mode.RUN ? errors : warnings;

        if (graph.nodes().isEmpty()) {
            errors.add(new Problem("empty", "Add at least one step to the workflow.", null, null));
            return new Report(List.copyOf(errors), List.copyOf(warnings), byId);
        }

        Map<UUID, Set<String>> granted = graph.needsConnectors() ? grants(orgId, graph.agentIds()) : Map.of();
        Set<UUID> reportedAgents = new HashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            Agent agent = node.agentId() == null ? null : byId.get(node.agentId());
            String label = label(node, agent);
            if (node.agentId() == null) {
                errors.add(new Problem(
                        "agent_missing", sentence(label) + " has no agent. Choose who does this step.", node.id(), null));
            } else if (agent == null) {
                errors.add(new Problem(
                        "agent_missing",
                        sentence(label) + " is assigned to an agent that no longer exists. Choose another agent.",
                        node.id(),
                        null));
            } else if (agent.isRetired()) {
                errors.add(new Problem(
                        "agent_retired",
                        agent.getName() + " is retired and cannot take work. Choose another agent for this step.",
                        node.id(),
                        null));
            } else if (!agent.isActive() && reportedAgents.add(agent.getId())) {
                errors.add(new Problem(
                        "agent_paused",
                        agent.getName() + " is paused. Resume the agent on the Agents page, or choose another agent for"
                                + " this step.",
                        node.id(),
                        null));
            }
            if (node.instructionText().isBlank()) {
                errors.add(new Problem(
                        "instruction_missing",
                        sentence(label) + " has no instruction. Write what "
                                + (agent == null ? "the agent" : agent.getName()) + " should do.",
                        node.id(),
                        null));
            }
            for (String connector : node.connectors()) {
                ConnectorInfo info = catalog.find(connector).orElse(null);
                if (info == null) {
                    errors.add(new Problem(
                            "connector_unknown",
                            "There is no connector called \"" + connector + "\". Remove it from " + label + ".",
                            node.id(),
                            null));
                } else if (agent != null
                        && !agent.isRetired()
                        && !granted.getOrDefault(agent.getId(), Set.of()).contains(connector)) {
                    soft.add(new Problem(
                            "grant_missing",
                            agent.getName() + " is not allowed to use " + info.displayName() + ". Give "
                                    + agent.getName() + " access to " + info.displayName()
                                    + " on the Connectors page before running this workflow.",
                            node.id(),
                            null));
                }
            }
        }

        checkAgentRepeats(graph, byId, errors);
        checkPlaceholders(graph, errors);
        checkEdges(graph, byId, errors);
        return new Report(List.copyOf(errors), List.copyOf(warnings), byId);
    }

    /** An agent named on more steps than {@link #MAX_STEPS_PER_AGENT}: refused, once per agent. */
    private static void checkAgentRepeats(WorkflowGraph graph, Map<UUID, Agent> byId, List<Problem> errors) {
        Map<UUID, List<WorkflowGraph.Node>> steps = new LinkedHashMap<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (node.agentId() != null) {
                steps.computeIfAbsent(node.agentId(), id -> new ArrayList<>()).add(node);
            }
        }
        steps.forEach((agentId, nodes) -> {
            if (nodes.size() > MAX_STEPS_PER_AGENT) {
                Agent agent = byId.get(agentId);
                String name = agent == null ? "One agent" : agent.getName();
                errors.add(new Problem(
                        "agent_repeats",
                        name + " does " + nodes.size() + " steps. An agent can take at most "
                                + MAX_STEPS_PER_AGENT + " steps in one workflow; give the others to another agent.",
                        nodes.get(MAX_STEPS_PER_AGENT).id(),
                        null));
            }
        });
    }

    /**
     * More distinct {@code {{placeholders}}} than a run can carry values for: such a workflow could
     * be saved but never run, so it is refused.
     */
    private static void checkPlaceholders(WorkflowGraph graph, List<Problem> errors) {
        int count = WorkflowService.placeholders(graph).size();
        if (count > WorkflowService.MAX_INPUTS) {
            errors.add(new Problem(
                    "placeholders_limit",
                    "The steps ask for " + count + " different values in double braces. A run can fill in at most "
                            + WorkflowService.MAX_INPUTS + "; use fewer names.",
                    null,
                    null));
        }
    }

    /** Connections that point nowhere, join a step to itself or repeat another, then loops. */
    private static void checkEdges(WorkflowGraph graph, Map<UUID, Agent> byId, List<Problem> errors) {
        Set<String> ids = graph.nodes().stream().map(WorkflowGraph.Node::id).collect(Collectors.toSet());
        Set<String> pairs = new HashSet<>();
        for (WorkflowGraph.Edge edge : graph.edges()) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                errors.add(new Problem(
                        "edge_dangling",
                        "A connection points to a step that is not on the canvas. Remove it.",
                        null,
                        edge.id()));
            } else if (edge.from().equals(edge.to())) {
                errors.add(new Problem(
                        "edge_self", "A step cannot hand over to itself. Remove that connection.", edge.from(), edge.id()));
            } else if (!pairs.add(edge.from() + "\u0000" + edge.to())) {
                errors.add(new Problem(
                        "edge_duplicate", "Two connections join the same two steps. Remove one.", edge.to(), edge.id()));
            }
        }
        List<String> loop = graph.cycle();
        if (!loop.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String id : loop) {
                WorkflowGraph.Node node = graph.node(id).orElseThrow();
                names.add(label(node, node.agentId() == null ? null : byId.get(node.agentId())));
            }
            names.add(names.get(0));
            errors.add(new Problem(
                    "cycle",
                    "These steps wait for each other in a loop, so none of them could ever start: "
                            + String.join(" to ", names) + ". Remove one of those connections.",
                    loop.get(0),
                    null));
        }
    }

    /** Enabled connector grants of the given agents, as connector ids per agent. */
    private Map<UUID, Set<String>> grants(UUID orgId, Set<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> granted = new HashMap<>();
        for (AgentToolGrant grant : reads.enabledGrants(orgId, agentIds)) {
            granted.computeIfAbsent(grant.getAgentId(), id -> new HashSet<>()).add(grant.getServer());
        }
        return granted;
    }

    /**
     * How a message names a step: its title in quotes when it has one, otherwise "the step for"
     * its agent, otherwise "a step".
     */
    static String label(WorkflowGraph.Node node, Agent agent) {
        if (!node.titleText().isEmpty()) {
            return "the step \"" + node.titleText() + "\"";
        }
        if (agent != null) {
            return "the step for " + agent.getName();
        }
        return "a step";
    }

    /** A label at the start of a sentence: its first letter capitalised. */
    private static String sentence(String label) {
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    // @find: workflow problems as one sentence, refusal message
    /** Every problem's message, joined, for a refusal a person reads as one paragraph. */
    public static String describe(List<Problem> problems) {
        return problems.stream().map(Problem::message).collect(Collectors.joining(" "));
    }
}
