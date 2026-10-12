// @find: tests for workflow service, create workflow, update workflow, version conflict 409, stale version, lost race at flush, missing version, delete workflow, other workspace not found, invalid workflow refused with every problem, run creates goal, depends on from edges, task order, placeholders filled, run details, connectors named, run refused for missing grant, missing input, run history, palette, audit, WorkflowServiceTest, WorkflowService
// @what: Tests for WorkflowService against an in-memory stand-in for the repositories: saving with the version check, deleting, and running a workflow as one goal.
// @flow: Exercises WorkflowService with the real WorkflowValidator and ConnectorCatalog and a mocked GoalService
package os.aiworkforce.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * Saving, deleting and running workflows. The repositories are in-memory stand-ins that answer
 * like the real ones, including the version a flush increments; the JSON columns and the native
 * "latest run" query are read against Postgres in WorkflowsPersistenceTest.
 */
class WorkflowServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final String MAKER = "00000000-0000-7000-8000-0000000000a1";
    private static final String COLLEAGUE = "00000000-0000-7000-8000-0000000000a2";

    private final List<Workflow> rows = Collections.synchronizedList(new ArrayList<>());
    private final List<WorkflowRun> runRows = Collections.synchronizedList(new ArrayList<>());

    private Workflows workflows;
    private WorkflowRuns runs;
    private WorkflowReads reads;
    private GoalService goalService;
    private Goals goals;
    private Agents agents;
    private AuditClient audit;
    private WorkflowService service;

    private Agent rhea;
    private Agent mo;

    @BeforeEach
    void setUp() {
        workflows = mock(Workflows.class);
        runs = mock(WorkflowRuns.class);
        reads = mock(WorkflowReads.class);
        goalService = mock(GoalService.class);
        goals = mock(Goals.class);
        agents = mock(Agents.class);
        audit = mock(AuditClient.class);
        ConnectorCatalog catalog = new ConnectorCatalog();
        WorkflowValidator validator = new WorkflowValidator(agents, reads, catalog);
        service = new WorkflowService(workflows, runs, validator, reads, goalService, goals, agents, catalog, audit);
        signIn(MAKER, Permission.Codes.TASK_CREATE);

        rhea = WorkflowValidatorTest.agent("Rhea", "active");
        mo = WorkflowValidatorTest.agent("Mo", "active");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(mo, rhea));
        lenient().when(reads.enabledGrants(eq(ORG), any())).thenReturn(List.of());

        lenient().when(workflows.findByIdAndOrgId(any(), any())).thenAnswer(call -> rows.stream()
                .filter(w -> w.getId().equals(call.getArgument(0)) && w.getOrgId().equals(call.getArgument(1)))
                .findFirst());
        lenient().when(workflows.saveAndFlush(any())).thenAnswer(call -> store(call.getArgument(0)));
        lenient().doAnswer(call -> rows.remove(call.<Workflow>getArgument(0))).when(workflows).delete(any());
        lenient().when(runs.saveAndFlush(any())).thenAnswer(call -> {
            WorkflowRun run = call.getArgument(0);
            runRows.add(run);
            return run;
        });
        lenient().when(goalService.createGoal(eq(ORG), any(), eq(true))).thenAnswer(call -> {
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID());
            goal.setOrgId(ORG);
            goal.setTitle(call.<GoalService.NewGoal>getArgument(1).title());
            goal.setStatus("running");
            return goal;
        });
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static void signIn(String userId, String... permissions) {
        RequestContext.setActor(Actor.user(userId, ORG.toString(), "role", java.util.Set.of(permissions), 0L));
    }

    /**
     * Stores a workflow; storing one already held is an update, which a flush counts in its
     * version. A new one is stamped with its maker, as JPA auditing does.
     */
    private Workflow store(Workflow workflow) {
        if (rows.contains(workflow)) {
            setVersion(workflow, workflow.getVersion() + 1);
        } else {
            setCreatedBy(workflow, RequestContext.actor().map(Actor::humanId).orElse(null));
            rows.add(workflow);
        }
        return workflow;
    }

    private static void setCreatedBy(BaseEntity entity, String createdBy) {
        try {
            Field field = BaseEntity.class.getDeclaredField("createdBy");
            field.setAccessible(true);
            field.set(entity, createdBy);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setVersion(BaseEntity entity, long version) {
        try {
            Field field = BaseEntity.class.getDeclaredField("version");
            field.setAccessible(true);
            field.setLong(entity, version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException refused(Runnable action) {
        return catchThrowableOfType(action::run, ApiException.class);
    }

    private WorkflowGraphs twoSteps() {
        return WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "Research the customer.", 0, 0)
                .step("b", mo.getId(), "Draft the reply.", 300, 0)
                .edge("a", "b");
    }

    private Workflow saved(WorkflowGraphs canvas) {
        return service.create(ORG, new WorkflowService.WorkflowInput("Reply to a customer", "", canvas.map())).workflow();
    }

    // ---- Saving ---------------------------------------------------------------------------------

    // @find: test create workflow stores trimmed and canonical, workflow service
    @Test
    @DisplayName("a new workflow is stored trimmed with its canvas in the canonical form, and audited")
    void createsWorkflow() {
        WorkflowService.Saved saved = service.create(
                ORG, new WorkflowService.WorkflowInput("  Reply to a customer  ", "  Every morning  ", twoSteps().map()));

        Workflow workflow = saved.workflow();
        assertThat(workflow.getOrgId()).isEqualTo(ORG);
        assertThat(workflow.getName()).isEqualTo("Reply to a customer");
        assertThat(workflow.getDescription()).isEqualTo("Every morning");
        assertThat(WorkflowGraph.parse(workflow.getGraph()).nodes()).hasSize(2);
        assertThat(workflow.getGraph()).isEqualTo(twoSteps().graph().toMap());
        assertThat(saved.report().ok()).isTrue();
        verify(audit).record(eq(ORG), any(), eq("workflow.create"), eq("workflow"), eq(workflow.getId().toString()), eq("succeeded"), anyMap());
    }

    // @find: test name and description rules, workflow service
    @Test
    @DisplayName("a blank or over-long name and an over-long description are refused naming the field")
    void nameRules() {
        assertThat(refused(() -> service.create(ORG, new WorkflowService.WorkflowInput(" ", "", twoSteps().map())))
                        .details().get("field"))
                .isEqualTo("name");
        assertThat(refused(() -> service.create(ORG, new WorkflowService.WorkflowInput("x".repeat(121), "", twoSteps().map())))
                        .details().get("field"))
                .isEqualTo("name");
        assertThat(refused(() -> service.create(ORG, new WorkflowService.WorkflowInput("Ok", "d".repeat(1_001), twoSteps().map())))
                        .details().get("field"))
                .isEqualTo("description");
        assertThat(refused(() -> service.create(ORG, null)).details().get("field")).isEqualTo("name");
    }

    // @find: test invalid workflow refused with every problem, workflow service
    @Test
    @DisplayName("a workflow that breaks the rules is not saved, and the refusal lists every problem")
    void invalidRefused() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "", 0, 0)
                .step("b", mo.getId(), "Two.", 300, 0)
                .edge("a", "b")
                .edge("b", "a");

        ApiException refusal = refused(() -> service.create(ORG, new WorkflowService.WorkflowInput("Loop", "", canvas.map())));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal.getMessage())
                .startsWith("The workflow was not saved. The step for Rhea has no instruction.")
                .contains("wait for each other in a loop");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) refusal.details().get("problems");
        assertThat(problems).extracting(problem -> problem.get("code")).containsExactly("instruction_missing", "cycle");
        assertThat(refusal.details()).doesNotContainKey("field");
        assertThat(rows).isEmpty();
    }

    // @find: test save with warnings, missing grant saves, workflow service
    @Test
    @DisplayName("a missing grant does not stop a save; it comes back as a warning")
    void savesWithWarnings() {
        WorkflowGraphs canvas = WorkflowGraphs.canvas().step("a", rhea.getId(), "Send it.", 0, 0, "gmail");

        WorkflowService.Saved saved = service.create(ORG, new WorkflowService.WorkflowInput("Send", "", canvas.map()));

        assertThat(rows).hasSize(1);
        assertThat(saved.report().warnings()).extracting(WorkflowValidator.Problem::code).containsExactly("grant_missing");
    }

    // @find: test update with matching version, workflow service
    @Test
    @DisplayName("an update based on the stored version replaces the workflow and moves its version on")
    void updates() {
        Workflow workflow = saved(twoSteps());
        assertThat(workflow.getVersion()).isZero();

        WorkflowGraphs changed = twoSteps().step("c", rhea.getId(), "Check it.", 600, 0).edge("b", "c");
        WorkflowService.Saved updated = service.update(
                ORG, workflow.getId(), new WorkflowService.WorkflowInput("Reply, checked", null, changed.map()), 0L);

        assertThat(updated.workflow().getName()).isEqualTo("Reply, checked");
        assertThat(WorkflowGraph.parse(updated.workflow().getGraph()).nodes()).hasSize(3);
        assertThat(updated.workflow().getVersion()).isEqualTo(1);
        verify(audit).record(eq(ORG), any(), eq("workflow.update"), eq("workflow"), any(), eq("succeeded"), anyMap());
    }

    // @find: test version conflict 409, stale version, workflow service
    @Test
    @DisplayName("an update based on an older version is refused with 409 and changes nothing")
    void staleVersionConflict() {
        Workflow workflow = saved(twoSteps());
        setVersion(workflow, 3);

        ApiException refusal = refused(() -> service.update(
                ORG, workflow.getId(), new WorkflowService.WorkflowInput("Overwritten", "", twoSteps().map()), 2L));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(refusal.code().status()).isEqualTo(409);
        assertThat(refusal.getMessage()).startsWith("Someone else saved this workflow");
        assertThat(workflow.getName()).isEqualTo("Reply to a customer");
    }

    // @find: test lost race at flush is 409, workflow service
    @Test
    @DisplayName("a save that loses the race at flush time is refused with the same 409")
    void lostRaceConflict() {
        Workflow workflow = saved(twoSteps());
        doThrow(new ObjectOptimisticLockingFailureException(Workflow.class, workflow.getId()))
                .when(workflows)
                .saveAndFlush(workflow);

        ApiException refusal = refused(() -> service.update(
                ORG, workflow.getId(), new WorkflowService.WorkflowInput("Mine", "", twoSteps().map()), 0L));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
    }

    // @find: test update without version refused, workflow service
    @Test
    @DisplayName("an update that names no version is refused rather than overwriting blindly")
    void updateNeedsVersion() {
        Workflow workflow = saved(twoSteps());
        ApiException refusal = refused(() -> service.update(
                ORG, workflow.getId(), new WorkflowService.WorkflowInput("Mine", "", twoSteps().map()), null));
        assertThat(refusal.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal.details().get("field")).isEqualTo("version");
    }

    // @find: test other workspace not found and delete, workflow service
    @Test
    @DisplayName("another workspace's workflow is not found for reading, saving, deleting or running; a delete is audited")
    void workspaceScopeAndDelete() {
        Workflow workflow = saved(twoSteps());

        assertThat(refused(() -> service.get(OTHER_ORG, workflow.getId())).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refused(() -> service.delete(OTHER_ORG, workflow.getId())).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refused(() -> service.run(OTHER_ORG, workflow.getId(), null, null)).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refused(() -> service.update(
                                OTHER_ORG, workflow.getId(), new WorkflowService.WorkflowInput("X", "", twoSteps().map()), 0L))
                        .code())
                .isEqualTo(ErrorCode.NOT_FOUND);

        service.delete(ORG, workflow.getId());

        assertThat(rows).isEmpty();
        verify(audit).record(eq(ORG), any(), eq("workflow.delete"), eq("workflow"), any(), eq("succeeded"), anyMap());
    }

    // @find: test paused agent refused on save, workflow service
    @Test
    @DisplayName("a workflow with a paused agent is not saved; the refusal says to resume the agent or choose another")
    void pausedAgentRefusedOnSave() {
        mo.setStatus("paused");

        ApiException refusal = refused(() -> service.create(ORG, new WorkflowService.WorkflowInput("Paused", "", twoSteps().map())));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal.getMessage()).contains("Mo is paused. Resume the agent on the Agents page, or choose another agent");
        assertThat(rows).isEmpty();
    }

    // @find: test only the maker or a canceller changes a workflow, ownership, workflow service
    @Test
    @DisplayName("a colleague who did not make a workflow cannot change or delete it (403); someone who can cancel work can")
    void onlyMakerOrCancellerChanges() {
        Workflow workflow = saved(twoSteps());
        signIn(COLLEAGUE, Permission.Codes.TASK_CREATE);

        ApiException changed = refused(() -> service.update(
                ORG, workflow.getId(), new WorkflowService.WorkflowInput("Theirs now", "", twoSteps().map()), 0L));
        ApiException deleted = refused(() -> service.delete(ORG, workflow.getId()));

        assertThat(changed.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(changed.code().status()).isEqualTo(403);
        assertThat(changed.getMessage())
                .isEqualTo("Only the person who made this workflow, or someone who can cancel work, can change it.");
        assertThat(deleted.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(rows).containsExactly(workflow);
        assertThat(workflow.getName()).isEqualTo("Reply to a customer");
        assertThat(WorkflowService.canChange(workflow, RequestContext.actor().orElseThrow())).isFalse();

        signIn(COLLEAGUE, Permission.Codes.TASK_CREATE, Permission.Codes.TASK_CANCEL);
        assertThat(WorkflowService.canChange(workflow, RequestContext.actor().orElseThrow())).isTrue();
        service.update(ORG, workflow.getId(), new WorkflowService.WorkflowInput("Managed", "", twoSteps().map()), 0L);
        assertThat(workflow.getName()).isEqualTo("Managed");
        service.delete(ORG, workflow.getId());
        assertThat(rows).isEmpty();
    }

    // @find: test maker may change own workflow, can change flag, workflow service
    @Test
    @DisplayName("the maker may change their own workflow; nobody without task:create may change any")
    void makerChanges() {
        Workflow workflow = saved(twoSteps());

        assertThat(WorkflowService.canChange(workflow, RequestContext.actor().orElseThrow())).isTrue();
        signIn(MAKER, Permission.Codes.TASK_READ);
        assertThat(WorkflowService.canChange(workflow, RequestContext.actor().orElseThrow())).isFalse();
        assertThat(WorkflowService.canChange(workflow, null)).isFalse();
    }

    // ---- Running --------------------------------------------------------------------------------

    // @find: test run refused for a stale version, run version check 409, workflow service
    @Test
    @DisplayName("a run naming a version that is no longer stored is refused with 409 and creates no goal")
    void runStaleVersionConflict() {
        Workflow workflow = saved(twoSteps());
        setVersion(workflow, 2);

        ApiException refusal = refused(() -> service.run(ORG, workflow.getId(), null, 1L));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(refusal.getMessage()).startsWith("Someone else saved this workflow since you opened it.");
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());

        service.run(ORG, workflow.getId(), null, 2L);
        verify(goalService).createGoal(eq(ORG), any(), eq(true));
    }

    // @find: test run creates one goal with depends on from edges, workflow service
    @Test
    @DisplayName("a run creates one goal whose tasks follow the canvas: order, agents, dependsOn, titles and filled-in instructions")
    void runCreatesGoal() {
        when(reads.enabledGrants(eq(ORG), any())).thenReturn(List.of(WorkflowValidatorTest.grant(rhea, "gmail")));
        WorkflowGraphs canvas = WorkflowGraphs.canvas()
                .step("draft", mo.getId(), "Draft the reply for {{customer}}.", 400, 0)
                .titled("Draft the reply")
                .step("research", mo.getId(), "Read their last tickets.", 0, 100)
                .step("facts", rhea.getId(), "Find facts about {{ customer }}.", 0, 0)
                .step("review", rhea.getId(), "Review and send.", 800, 0, "gmail")
                .edge("research", "draft")
                .edge("facts", "draft")
                .edge("draft", "review");
        Workflow workflow = saved(canvas);

        WorkflowService.Started started = service.run(ORG, workflow.getId(), Map.of("customer", " Acme Pty Ltd "), null);

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        GoalService.NewGoal goal = spec.getValue();
        assertThat(goal.title()).isEqualTo("Reply to a customer");
        // The edges are the whole story: facts and research both start at once, beside each other.
        assertThat(goal.explicitDependencies()).isTrue();
        assertThat(goal.source()).isEqualTo("manual");
        assertThat(goal.description()).isEqualTo("Started from the workflow \"Reply to a customer\".");
        List<GoalService.NewTask> tasks = goal.tasks();
        assertThat(tasks).extracting(GoalService.NewTask::agentId)
                .containsExactly(rhea.getId(), mo.getId(), mo.getId(), rhea.getId());
        assertThat(tasks).extracting(GoalService.NewTask::dependsOnPositions)
                .containsExactly(List.of(), List.of(), List.of(0, 1), List.of(2));
        assertThat(tasks).extracting(GoalService.NewTask::title)
                .containsExactly("Rhea: Find facts about Acme Pty Ltd.", "Mo: Read their last tickets.", "Draft the reply",
                        "Rhea: Review and send.");
        assertThat(tasks.get(0).instruction())
                .isEqualTo("Find facts about Acme Pty Ltd.\n\nDetails for this run:\n- customer: Acme Pty Ltd");
        assertThat(tasks.get(1).instruction())
                .isEqualTo("Read their last tickets.\n\nDetails for this run:\n- customer: Acme Pty Ltd");
        assertThat(tasks.get(2).instruction()).isEqualTo("Draft the reply for Acme Pty Ltd.");
        assertThat(tasks.get(3).instruction()).isEqualTo("Review and send.\n\nUse Gmail for this step.");

        WorkflowRun run = started.run();
        assertThat(run.getWorkflowId()).isEqualTo(workflow.getId());
        assertThat(run.getWorkflowName()).isEqualTo("Reply to a customer");
        assertThat(run.getWorkflowVersion()).isEqualTo(workflow.getVersion());
        assertThat(run.getGoalId()).isEqualTo(started.goal().getId());
        assertThat(run.getNodeIds()).containsExactly("facts", "research", "draft", "review");
        assertThat(run.getGraph()).isEqualTo(workflow.getGraph());
        assertThat(run.getInputs()).containsExactly(Map.entry("customer", "Acme Pty Ltd"));
        assertThat(runRows).containsExactly(run);
        verify(audit).record(eq(ORG), any(), eq("workflow.run"), eq("workflow"), eq(workflow.getId().toString()), eq("succeeded"), anyMap());
    }

    // @find: test run refused for missing grant, names what is missing, workflow service
    @Test
    @DisplayName("a run is refused while a step's connector is not granted, naming the agent and the connector, and no goal is created")
    void runRefusedForMissingGrant() {
        Workflow workflow = saved(WorkflowGraphs.canvas().step("a", rhea.getId(), "Send it.", 0, 0, "gmail"));

        ApiException refusal = refused(() -> service.run(ORG, workflow.getId(), null, null));

        assertThat(refusal.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal.getMessage())
                .isEqualTo("This workflow cannot run yet. Rhea is not allowed to use Gmail. Give Rhea access to Gmail on the"
                        + " Connectors page before running this workflow.");
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
        assertThat(runRows).isEmpty();
    }

    // @find: test run refused for paused agent, workflow service
    @Test
    @DisplayName("a run is refused while an agent on it is paused")
    void runRefusedForPausedAgent() {
        Workflow workflow = saved(twoSteps());
        mo.setStatus("paused");

        ApiException refusal = refused(() -> service.run(ORG, workflow.getId(), null, null));

        assertThat(refusal.getMessage()).contains("Mo is paused.");
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
    }

    // @find: test run refused for missing input, placeholders, workflow service
    @Test
    @DisplayName("a run is refused until every placeholder has a value, naming the missing ones")
    void runNeedsInputs() {
        Workflow workflow = saved(WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "Look up {{customer}} for {{ month }}.", 0, 0));

        ApiException refusal = refused(() -> service.run(ORG, workflow.getId(), Map.of("customer", "Acme", "month", "  "), null));

        assertThat(refusal.getMessage()).isEqualTo("Fill in month before running this workflow.");
        assertThat(refusal.details().get("missing")).isEqualTo(List.of("month"));
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
    }

    // @find: test run input rules, workflow service
    @Test
    @DisplayName("run values with a bad name, an over-long value or too many are refused")
    void inputRules() {
        assertThat(refused(() -> WorkflowService.inputs(Map.of("1bad", "x"))).details().get("field")).isEqualTo("inputs");
        assertThat(refused(() -> WorkflowService.inputs(Map.of("ok", "x".repeat(2_001)))).details().get("field"))
                .isEqualTo("inputs");
        Map<String, String> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            many.put("value" + i, "x");
        }
        assertThat(refused(() -> WorkflowService.inputs(many)).details().get("field")).isEqualTo("inputs");
        assertThat(WorkflowService.inputs(null)).isEmpty();
    }

    // @find: test placeholders listed once in order, workflow service
    @Test
    @DisplayName("placeholders are listed once each, in canvas order, whatever the spacing inside the braces")
    void placeholders() {
        WorkflowGraph graph = WorkflowGraphs.canvas()
                .step("a", rhea.getId(), "{{customer}} and {{ month }}", 0, 0)
                .step("b", mo.getId(), "{{customer}} again, {{not closed", 0, 0)
                .graph();
        assertThat(WorkflowService.placeholders(graph)).containsExactly("customer", "month");
    }

    // ---- Reading --------------------------------------------------------------------------------

    // @find: test palette leaves out retired agents and lists grants, workflow service
    @Test
    @DisplayName("the palette offers every agent but retired ones, with their grants, and every connector")
    void palette() {
        Agent retired = WorkflowValidatorTest.agent("Old", "retired");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(mo, retired, rhea));
        when(reads.enabledGrantsInWorkspace(ORG)).thenReturn(List.of(WorkflowValidatorTest.grant(rhea, "github")));

        WorkflowService.Palette palette = service.palette(ORG, true);

        assertThat(palette.agents()).extracting(WorkflowService.PaletteAgent::name).containsExactly("Mo", "Rhea");
        assertThat(palette.agents().get(1).connectors()).containsExactly("github");
        assertThat(palette.agents().get(0).connectors()).isEmpty();
        assertThat(palette.connectors()).extracting(WorkflowService.PaletteConnector::id).contains("github", "gmail", "slack");
    }

    // @find: test palette without agent read leaves out grants and descriptions, workflow service
    @Test
    @DisplayName("without agent configuration the palette names the agents but carries no grants or descriptions, and reads no grants")
    void paletteWithoutConfiguration() {
        rhea.setDescription("Finds facts.");
        when(reads.enabledGrantsInWorkspace(ORG)).thenReturn(List.of(WorkflowValidatorTest.grant(rhea, "github")));

        WorkflowService.Palette palette = service.palette(ORG, false);

        assertThat(palette.agents()).extracting(WorkflowService.PaletteAgent::name).containsExactly("Mo", "Rhea");
        assertThat(palette.agents()).allSatisfy(agent -> {
            assertThat(agent.connectors()).isNull();
            assertThat(agent.description()).isNull();
        });
        assertThat(palette.agents().get(1).status()).isEqualTo("active");
        verify(reads, never()).enabledGrantsInWorkspace(any());
    }

    // @find: test history needs a visible workflow, workflow service
    @Test
    @DisplayName("run history is read for the caller's own workflow only")
    void history() {
        Workflow workflow = saved(twoSteps());
        when(runs.findByOrgIdAndWorkflowIdOrderByCreatedAtDesc(eq(ORG), eq(workflow.getId()), any())).thenReturn(List.of());

        assertThat(service.history(ORG, workflow.getId())).isEmpty();
        assertThat(refused(() -> service.history(OTHER_ORG, workflow.getId())).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
