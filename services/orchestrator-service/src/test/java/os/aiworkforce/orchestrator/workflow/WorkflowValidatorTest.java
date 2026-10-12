// @find: tests for workflow validation, empty workflow, missing agent, retired agent, paused agent refused on save and run, missing instruction, unknown connector, missing grant warns on save refuses on run, too many placeholders, agent repeats, dangling connection, self connection, duplicate connection, loop, cycle, two queries, WorkflowValidatorTest, WorkflowValidator
// @what: Tests for WorkflowValidator: every rule a workflow is held to, the save and run modes, and that it reads agents and grants in batches.
// @flow: Exercises WorkflowValidator with mocked Agents and WorkflowReads and the real ConnectorCatalog
package os.aiworkforce.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.repository.Agents;

/** Every rule, reported all at once; a paused agent is refused on save and run, a missing grant warns on save and refuses a run. */
class WorkflowValidatorTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Agents agents;
    private WorkflowReads reads;
    private WorkflowValidator validator;
    private Agent rhea;
    private Agent mo;

    @BeforeEach
    void setUp() {
        agents = mock(Agents.class);
        reads = mock(WorkflowReads.class);
        validator = new WorkflowValidator(agents, reads, new ConnectorCatalog());
        rhea = agent("Rhea", "active");
        mo = agent("Mo", "active");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(mo, rhea));
        when(reads.enabledGrants(eq(ORG), any())).thenReturn(List.of());
    }

    static Agent agent(String name, String status) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey(name.toLowerCase());
        agent.setName(name);
        agent.setStatus(status);
        return agent;
    }

    static AgentToolGrant grant(Agent agent, String server) {
        AgentToolGrant grant = new AgentToolGrant();
        grant.setOrgId(ORG);
        grant.setAgentId(agent.getId());
        grant.setServer(server);
        return grant;
    }

    private WorkflowValidator.Report save(WorkflowGraphs canvas) {
        return validator.check(ORG, canvas.graph(), WorkflowValidator.Mode.SAVE);
    }

    private WorkflowValidator.Report run(WorkflowGraphs canvas) {
        return validator.check(ORG, canvas.graph(), WorkflowValidator.Mode.RUN);
    }

    private static List<String> codes(List<WorkflowValidator.Problem> problems) {
        return problems.stream().map(WorkflowValidator.Problem::code).toList();
    }

    // @find: test valid workflow passes, workflow validator
    @Test
    @DisplayName("two active agents with instructions and one hand-off pass both checks, with the agents named")
    void validPasses() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "Research the customer.", 0, 0)
                .step("b", mo.getId(), "Draft the reply.", 300, 0)
                .edge("a", "b");

        WorkflowValidator.Report saved = save(canvas);
        WorkflowValidator.Report ran = run(canvas);

        assertThat(saved.ok()).isTrue();
        assertThat(saved.warnings()).isEmpty();
        assertThat(ran.ok()).isTrue();
        assertThat(saved.agents()).containsKeys(rhea.getId(), mo.getId());
        // No step needs a connector, so no grant is read.
        verify(reads, never()).enabledGrants(any(), any());
    }

    // @find: test empty workflow refused, workflow validator
    @Test
    @DisplayName("a canvas with no steps is refused")
    void emptyRefused() {
        WorkflowValidator.Report report = save(WorkflowGraphs.canvas());
        assertThat(codes(report.errors())).containsExactly("empty");
        assertThat(report.errors().get(0).message()).isEqualTo("Add at least one step to the workflow.");
    }

    // @find: test every step problem reported at once, workflow validator
    @Test
    @DisplayName("a step with no agent, one with a removed agent, a retired agent and a blank instruction are all reported")
    void stepProblems() {
        Agent retired = agent("Old", "retired");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(mo, rhea, retired));
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("none", null, "Do it.", 0, 0)
                .titled("Unassigned")
                .step("gone", UUID.randomUUID(), "Do it.", 0, 100)
                .step("old", retired.getId(), "Do it.", 0, 200)
                .step("blank", rhea.getId(), "   ", 0, 300);

        WorkflowValidator.Report report = save(canvas);

        assertThat(codes(report.errors()))
                .containsExactly("agent_missing", "agent_missing", "agent_retired", "instruction_missing");
        assertThat(report.errors().get(0).message()).isEqualTo("The step \"Unassigned\" has no agent. Choose who does this step.");
        assertThat(report.errors().get(0).nodeId()).isEqualTo("none");
        assertThat(report.errors().get(1).message()).contains("no longer exists");
        assertThat(report.errors().get(2).message()).isEqualTo("Old is retired and cannot take work. Choose another agent for this step.");
        assertThat(report.errors().get(3).message()).isEqualTo("The step for Rhea has no instruction. Write what Rhea should do.");
        assertThat(report.errors().get(3).nodeId()).isEqualTo("blank");
    }

    // @find: test paused agent refused on save and on run, workflow validator
    @Test
    @DisplayName("a paused agent is an error when saving and when running (every agent must be active), reported once per agent")
    void pausedAgent() {
        mo.setStatus("paused");
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", mo.getId(), "One.", 0, 0)
                .step("b", mo.getId(), "Two.", 300, 0);

        WorkflowValidator.Report saved = save(canvas);
        WorkflowValidator.Report ran = run(canvas);

        assertThat(saved.ok()).isFalse();
        assertThat(codes(saved.errors())).containsExactly("agent_paused");
        assertThat(saved.warnings()).isEmpty();
        assertThat(codes(ran.errors())).containsExactly("agent_paused");
        assertThat(saved.errors().get(0).message())
                .isEqualTo("Mo is paused. Resume the agent on the Agents page, or choose another agent for this step.");
        assertThat(saved.errors().get(0).nodeId()).isEqualTo("a");
    }

    // @find: test too many placeholders refused on save, workflow validator
    @Test
    @DisplayName("more distinct placeholders than a run can fill in are refused on save, since such a workflow could never run")
    void tooManyPlaceholders() {
        StringBuilder twenty = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            twenty.append("{{value ").append(i).append("}} ");
        }
        WorkflowValidator.Report atLimit = save(WorkflowGraphs.canvas().step("a", rhea.getId(), twenty.toString(), 0, 0));
        WorkflowValidator.Report over = save(WorkflowGraphs.canvas()
                .step("a", rhea.getId(), twenty.toString(), 0, 0)
                .step("b", mo.getId(), "And {{value 20}}, then {{value 1}} again.", 300, 0));

        assertThat(atLimit.ok()).isTrue();
        assertThat(codes(over.errors())).containsExactly("placeholders_limit");
        assertThat(over.errors().get(0).message())
                .isEqualTo("The steps ask for 21 different values in double braces. A run can fill in at most 20; use fewer names.");
    }

    // @find: test missing grant warns on save refuses on run, workflow validator
    @Test
    @DisplayName("a connector the agent is not granted warns on save and refuses a run, naming the agent and the connector")
    void missingGrant() {
        when(reads.enabledGrants(eq(ORG), any())).thenReturn(List.of(grant(rhea, "github")));
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "Read the issues.", 0, 0, "github", "gmail");

        WorkflowValidator.Report saved = save(canvas);
        WorkflowValidator.Report ran = run(canvas);

        assertThat(saved.ok()).isTrue();
        assertThat(codes(saved.warnings())).containsExactly("grant_missing");
        assertThat(codes(ran.errors())).containsExactly("grant_missing");
        assertThat(ran.errors().get(0).message())
                .isEqualTo("Rhea is not allowed to use Gmail. Give Rhea access to Gmail on the Connectors page before running this workflow.");
        // One query for every agent's grants, not one per step.
        verify(reads, times(2)).enabledGrants(eq(ORG), any());
    }

    // @find: test unknown connector refused, workflow validator
    @Test
    @DisplayName("a connector the catalogue does not know is refused even on save")
    void unknownConnector() {
        WorkflowValidator.Report report = save(WorkflowGraphs.canvas().step("a", rhea.getId(), "Go.", 0, 0, "fax"));
        assertThat(codes(report.errors())).containsExactly("connector_unknown");
        assertThat(report.errors().get(0).message()).startsWith("There is no connector called \"fax\".");
    }

    // @find: test agent repeats more than twice refused, workflow validator
    @Test
    @DisplayName("an agent on three steps is refused, as GoalService would refuse the goal")
    void agentRepeats() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "One.", 0, 0)
                .step("b", rhea.getId(), "Two.", 100, 0)
                .step("c", rhea.getId(), "Three.", 200, 0);

        WorkflowValidator.Report report = save(canvas);

        assertThat(codes(report.errors())).containsExactly("agent_repeats");
        assertThat(report.errors().get(0).nodeId()).isEqualTo("c");
        assertThat(report.errors().get(0).message()).startsWith("Rhea does 3 steps.");
    }

    // @find: test dangling self and duplicate connections, workflow validator
    @Test
    @DisplayName("a connection to a missing step, one from a step to itself and a repeated one are each refused")
    void badConnections() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "One.", 0, 0)
                .step("b", mo.getId(), "Two.", 300, 0)
                .edge("a", "b")
                .edge("a", "b")
                .edge("b", "b")
                .edge("a", "ghost");

        WorkflowValidator.Report report = save(canvas);

        assertThat(codes(report.errors())).containsExactly("edge_duplicate", "edge_self", "edge_dangling");
        assertThat(report.errors().get(2).edgeId()).isNotNull();
    }

    // @find: test loop refused, cycle, workflow validator
    @Test
    @DisplayName("a loop is refused, naming the steps on it")
    void loopRefused() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "One.", 0, 0)
                .titled("Research")
                .step("b", mo.getId(), "Two.", 300, 0)
                .titled("Draft")
                .edge("a", "b")
                .edge("b", "a");

        WorkflowValidator.Report report = save(canvas);

        assertThat(codes(report.errors())).containsExactly("cycle");
        assertThat(report.errors().get(0).message())
                .isEqualTo("These steps wait for each other in a loop, so none of them could ever start: the step \"Research\""
                        + " to the step \"Draft\" to the step \"Research\". Remove one of those connections.");
        assertThat(report.errors().get(0).nodeId()).isEqualTo("a");
    }

    // @find: test describe joins messages, workflow validator
    @Test
    @DisplayName("problems describe as one paragraph")
    void describe() {
        String text = WorkflowValidator.describe(List.of(
                new WorkflowValidator.Problem("a", "First.", null, null),
                new WorkflowValidator.Problem("b", "Second.", null, null)));
        assertThat(text).isEqualTo("First. Second.");
    }
}
