// @find: tests for workflows persistence, workflow graph jsonb round trip, optimistic version increments, workflow run snapshot, latest run per workflow, delete cascades runs, V31 migration, WorkflowsPersistenceTest, Workflows, WorkflowRuns
// @what: Tests for the Workflows and WorkflowRuns repositories against the real schema: JSONB columns, the version a save increments, the native latest-run query and the cascade on delete.
// @flow: Exercises Workflows, WorkflowRuns and the V31 migration
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.workflow.Workflow;
import os.aiworkforce.orchestrator.workflow.WorkflowGraph;
import os.aiworkforce.orchestrator.workflow.WorkflowRun;
import os.aiworkforce.orchestrator.workflow.WorkflowRuns;
import os.aiworkforce.orchestrator.workflow.Workflows;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;

/**
 * Workflows against the real schema. The service test uses in-memory stand-ins, so it cannot see
 * a JSONB column, a version Hibernate increments or a native query Postgres runs. Opt-in, because
 * it needs a database: run with {@code AIWOS_DATABASE_TESTS=true} (see {@link DatabaseForTests}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
@Import(JpaAuditingConfig.class)
class WorkflowsPersistenceTest {

    /** A fresh workspace per test: other database tests write to the shared test database too. */
    private final UUID ORG = UUID.randomUUID();
    /** The one agent every test canvas names. */
    private static final UUID CANVAS_AGENT = UUID.fromString("00000000-0000-7000-8000-0000000007c3");
    private final UUID OTHER_ORG = UUID.randomUUID();

    private static DatabaseForTests.Connection connection;

    @BeforeAll
    static void database() {
        connection = DatabaseForTests.connection();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> connection.urlInSchema());
        registry.add("spring.datasource.username", () -> connection.user());
        registry.add("spring.datasource.password", () -> connection.password());
    }

    @Autowired
    private Workflows workflows;

    @Autowired
    private WorkflowRuns runs;

    @Autowired
    private Goals goals;

    @Autowired
    private EntityManager entities;

    private static Map<String, Object> canvas() {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", "n1");
        // A fixed agent, so two calls describe the same canvas and can be compared.
        node.put("agentId", CANVAS_AGENT.toString());
        node.put("instruction", "Look it up.");
        node.put("connectors", List.of("github"));
        node.put("x", 40);
        node.put("y", 80);
        return WorkflowGraph.parse(Map.of("nodes", List.of(node))).toMap();
    }

    private Workflow workflow(UUID orgId, String name) {
        Workflow workflow = new Workflow();
        workflow.setOrgId(orgId);
        workflow.setName(name);
        workflow.setGraph(canvas());
        return workflows.saveAndFlush(workflow);
    }

    private Goal goal(UUID orgId) {
        Goal goal = new Goal();
        goal.setOrgId(orgId);
        goal.setTitle("A goal");
        goal.setStatus("running");
        return goals.saveAndFlush(goal);
    }

    private WorkflowRun run(Workflow workflow, Goal goal) {
        WorkflowRun run = new WorkflowRun();
        run.setOrgId(workflow.getOrgId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowName(workflow.getName());
        run.setWorkflowVersion(workflow.getVersion());
        run.setGoalId(goal.getId());
        run.setGraph(workflow.getGraph());
        run.setNodeIds(List.of("n1"));
        run.setInputs(Map.of("customer", "Acme"));
        return runs.saveAndFlush(run);
    }

    // @find: test workflow graph round trip and version, workflows persistence
    @Test
    @DisplayName("a workflow's graph is stored as JSON and read back whole, and each save moves its version on")
    void roundTripAndVersion() {
        Workflow saved = workflow(ORG, "Digest");
        entities.clear();

        Workflow read = workflows.findByIdAndOrgId(saved.getId(), ORG).orElseThrow();
        assertThat(WorkflowGraph.parse(read.getGraph())).isEqualTo(WorkflowGraph.parse(canvas()));
        assertThat(read.getVersion()).isZero();
        assertThat(read.getCreatedBy()).isEqualTo("system");
        assertThat(workflows.findByIdAndOrgId(saved.getId(), OTHER_ORG)).isEmpty();

        read.setName("Digest, renamed");
        workflows.saveAndFlush(read);
        assertThat(read.getVersion()).isEqualTo(1);
    }

    // @find: test latest run per workflow, delete keeps runs, workflows persistence
    @Test
    @DisplayName("the newest run of each workflow comes back in one query, and deleting a workflow keeps its runs, unlinked, for the replay")
    void latestRunAndDeleteKeepsRuns() {
        Workflow first = workflow(ORG, "First");
        Workflow second = workflow(ORG, "Second");
        run(first, goal(ORG));
        WorkflowRun newer = run(first, goal(ORG));
        WorkflowRun only = run(second, goal(ORG));
        entities.clear();

        List<WorkflowRun> latest = runs.latestOf(ORG, List.of(first.getId(), second.getId()));
        assertThat(latest).extracting(WorkflowRun::getId).containsExactlyInAnyOrder(newer.getId(), only.getId());
        WorkflowRun read = runs.findByOrgIdAndGoalId(ORG, only.getGoalId()).orElseThrow();
        assertThat(read.getNodeIds()).containsExactly("n1");
        assertThat(read.getInputs()).containsEntry("customer", "Acme");
        assertThat(runs.findByOrgIdAndWorkflowIdOrderByCreatedAtDesc(ORG, first.getId(), PageRequest.of(0, 10))).hasSize(2);

        workflows.delete(workflows.findById(first.getId()).orElseThrow());
        workflows.flush();
        entities.clear();
        assertThat(runs.findByOrgIdAndWorkflowIdOrderByCreatedAtDesc(ORG, first.getId(), PageRequest.of(0, 10))).isEmpty();
        WorkflowRun kept = runs.findByOrgIdAndGoalId(ORG, newer.getGoalId()).orElseThrow();
        assertThat(kept.getWorkflowId()).isNull();
        assertThat(kept.getWorkflowName()).isEqualTo("First");
        assertThat(kept.getNodeIds()).containsExactly("n1");
    }
}
