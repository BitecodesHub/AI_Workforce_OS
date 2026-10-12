// @find: tests for workflow controller, permissions per endpoint, task read reads, task create writes and runs, viewer refused changes, no task read refused, view field names, placeholders and warnings in view, update passes version, run view, WorkflowControllerTest, WorkflowController
// @what: Tests for WorkflowController: the permission each endpoint needs, enforced by the real interceptor, and the views it hands back.
// @flow: Exercises WorkflowController with PermissionInterceptor and a mocked WorkflowService
package os.aiworkforce.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** Who may call each workflow endpoint, and what each hands back, always for the caller's workspace. */
class WorkflowControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-00000000000a");

    private WorkflowService service;
    private WorkflowController controller;
    private GoalTimelineController timeline;
    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    @BeforeEach
    void setUp() {
        service = mock(WorkflowService.class);
        controller = new WorkflowController(service);
        timeline = new GoalTimelineController(mock(os.aiworkforce.orchestrator.repository.Goals.class), mock(GoalTimelineService.class));
        signIn(Permission.Codes.TASK_READ, Permission.Codes.TASK_CREATE);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static void signIn(String... permissions) {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(permissions), 0L));
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        workflow.setOrgId(ORG);
        workflow.setName("Reply to a customer");
        workflow.setGraph(WorkflowGraphs.canvas()
                .step("a", AGENT, "Look up {{customer}}.", 0, 0, "gmail")
                .map());
        return workflow;
    }

    /** Runs the interceptor for one controller method, as the dispatcher would before calling it. */
    private ApiException refusalFor(Object target, Method method) throws Exception {
        HandlerMethod handler = new HandlerMethod(target, method);
        try {
            interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler);
            return null;
        } catch (ApiException e) {
            return e;
        }
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        return WorkflowController.class.getMethod(name, parameters);
    }

    private static String[] permission(Method method) {
        return method.getAnnotation(RequiresPermission.class).value();
    }

    private static final Class<?> REQUEST = WorkflowController.WorkflowRequest.class;

    // ---- Permissions ---------------------------------------------------------------------------

    // @find: test permission per endpoint, workflow controller
    @Test
    @DisplayName("reading needs task:read; saving, deleting and running need task:create; the timeline needs task:read")
    void permissionPerEndpoint() throws Exception {
        assertThat(permission(method("list"))).containsExactly(Permission.Codes.TASK_READ);
        assertThat(permission(method("palette"))).containsExactly(Permission.Codes.TASK_READ);
        assertThat(permission(method("get", UUID.class))).containsExactly(Permission.Codes.TASK_READ);
        assertThat(permission(method("runs", UUID.class))).containsExactly(Permission.Codes.TASK_READ);
        assertThat(permission(method("create", REQUEST))).containsExactly(Permission.Codes.TASK_CREATE);
        assertThat(permission(method("update", UUID.class, REQUEST))).containsExactly(Permission.Codes.TASK_CREATE);
        assertThat(permission(method("delete", UUID.class))).containsExactly(Permission.Codes.TASK_CREATE);
        assertThat(permission(method("run", UUID.class, WorkflowController.RunRequest.class)))
                .containsExactly(Permission.Codes.TASK_CREATE);
        assertThat(GoalTimelineController.class.getMethod("timeline", UUID.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.TASK_READ);
    }

    // @find: test viewer refused changes, workflow controller
    @Test
    @DisplayName("someone who can only read tasks can read workflows and timelines, and is refused every change and run with 403")
    void readOnlyRole() throws Exception {
        signIn(Permission.Codes.TASK_READ);

        assertThat(refusalFor(controller, method("list"))).isNull();
        assertThat(refusalFor(controller, method("get", UUID.class))).isNull();
        assertThat(refusalFor(controller, method("palette"))).isNull();
        assertThat(refusalFor(timeline, GoalTimelineController.class.getMethod("timeline", UUID.class))).isNull();
        for (Method write : List.of(
                method("create", REQUEST),
                method("update", UUID.class, REQUEST),
                method("delete", UUID.class),
                method("run", UUID.class, WorkflowController.RunRequest.class))) {
            ApiException refused = refusalFor(controller, write);
            assertThat(refused).as(write.getName()).isNotNull();
            assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
            assertThat(refused.code().status()).isEqualTo(403);
        }
    }

    // @find: test no task read refused, workflow controller
    @Test
    @DisplayName("someone without task:read cannot even list workflows or read a timeline")
    void noTaskRead() throws Exception {
        signIn(Permission.Codes.CHAT_USE);

        assertThat(refusalFor(controller, method("list")).code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(refusalFor(controller, method("runs", UUID.class)).code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(refusalFor(timeline, GoalTimelineController.class.getMethod("timeline", UUID.class)).code())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    // ---- Views ---------------------------------------------------------------------------------

    // @find: test view field names, workflow controller
    @Test
    @DisplayName("a workflow serialises with the field names the console reads, its placeholders and its warnings")
    void viewFieldNames() {
        Workflow workflow = workflow();
        WorkflowValidator.Report report = new WorkflowValidator.Report(
                List.of(),
                List.of(new WorkflowValidator.Problem("grant_missing", "Rhea is not allowed to use Gmail.", "a", null)),
                Map.of());
        when(service.get(ORG, workflow.getId())).thenReturn(workflow);
        when(service.check(ORG, workflow)).thenReturn(report);

        WorkflowController.WorkflowView view = controller.get(workflow.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new ObjectMapper().registerModule(new JavaTimeModule()).convertValue(view, Map.class);

        assertThat(json)
                .containsOnlyKeys(
                        "id",
                        "name",
                        "description",
                        "graph",
                        "version",
                        "createdBy",
                        "updatedBy",
                        "createdAt",
                        "updatedAt",
                        "placeholders",
                        "errors",
                        "warnings",
                        "canChange");
        assertThat(json.get("placeholders")).isEqualTo(List.of("customer"));
        assertThat(view.warnings()).extracting(WorkflowController.ProblemView::nodeId).containsExactly("a");
        assertThat(view.errors()).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.graph().get("nodes");
        assertThat(nodes.get(0).get("connectors")).isEqualTo(List.of("gmail"));
    }

    // @find: test update passes version, requests use callers workspace, workflow controller
    @Test
    @DisplayName("saves and runs go to the service for the caller's own workspace, with the version and values as sent")
    void requestsPassThrough() {
        Workflow workflow = workflow();
        Map<String, Object> graph = WorkflowGraphs.canvas().step("a", AGENT, "Go.", 0, 0).map();
        WorkflowController.WorkflowRequest request = new WorkflowController.WorkflowRequest("Name", "Words", graph, 4L);
        WorkflowService.WorkflowInput input = new WorkflowService.WorkflowInput("Name", "Words", graph);
        WorkflowValidator.Report clean = new WorkflowValidator.Report(List.of(), List.of(), Map.of());
        when(service.create(ORG, input)).thenReturn(new WorkflowService.Saved(workflow, clean));
        when(service.update(ORG, workflow.getId(), input, 4L)).thenReturn(new WorkflowService.Saved(workflow, clean));
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setStatus("running");
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(workflow.getId());
        run.setGoalId(goal.getId());
        when(service.run(eq(ORG), eq(workflow.getId()), any(), any())).thenReturn(new WorkflowService.Started(run, goal));

        controller.create(request);
        controller.update(workflow.getId(), request);
        WorkflowController.RunView started =
                controller.run(workflow.getId(), new WorkflowController.RunRequest(Map.of("customer", "Acme"), 4L));
        controller.delete(workflow.getId());

        verify(service).create(ORG, input);
        verify(service).update(ORG, workflow.getId(), input, 4L);
        verify(service).run(ORG, workflow.getId(), Map.of("customer", "Acme"), 4L);
        verify(service).delete(ORG, workflow.getId());
        assertThat(started.goalId()).isEqualTo(goal.getId());
        assertThat(started.status()).isEqualTo("running");
    }

    // @find: test run without body, workflow controller
    @Test
    @DisplayName("a run with no body runs with no values")
    void runWithoutBody() {
        Workflow workflow = workflow();
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        WorkflowRun run = new WorkflowRun();
        run.setGoalId(goal.getId());
        when(service.run(ORG, workflow.getId(), null, null)).thenReturn(new WorkflowService.Started(run, goal));

        controller.run(workflow.getId(), null);

        verify(service).run(ORG, workflow.getId(), null, null);
    }

    // @find: test list summary with last run, workflow controller
    @Test
    @DisplayName("the list gives each workflow's size and its last run's goal and status")
    void listSummaries() {
        Workflow ran = workflow();
        Workflow fresh = workflow();
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setStatus("completed");
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(ran.getId());
        run.setGoalId(goal.getId());
        when(service.list(ORG)).thenReturn(List.of(ran, fresh));
        when(service.latestRuns(ORG, List.of(ran.getId(), fresh.getId()))).thenReturn(Map.of(ran.getId(), run));
        when(service.goalsOf(List.of(run))).thenReturn(Map.of(goal.getId(), goal));

        List<WorkflowController.WorkflowSummary> list = controller.list();

        assertThat(list).extracting(WorkflowController.WorkflowSummary::steps).containsExactly(1, 1);
        assertThat(list.get(0).lastRun().goalId()).isEqualTo(goal.getId());
        assertThat(list.get(0).lastRun().status()).isEqualTo("completed");
        assertThat(list.get(1).lastRun()).isNull();
    }

    // @find: test palette without agent read, grants hidden, workflow controller
    @Test
    @DisplayName("the palette asks for agent configuration only for a caller who holds agent:read")
    void paletteConfigurationNeedsAgentRead() {
        WorkflowService.Palette empty = new WorkflowService.Palette(List.of(), List.of());
        when(service.palette(eq(ORG), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(empty);

        signIn(Permission.Codes.TASK_READ);
        controller.palette();
        verify(service).palette(ORG, false);

        signIn(Permission.Codes.TASK_READ, Permission.Codes.AGENT_READ);
        controller.palette();
        verify(service).palette(ORG, true);
    }

    // @find: test grant warning does not name the connector without agent read, workflow controller
    @Test
    @DisplayName("without agent:read a missing grant is described without naming the connector; with it, as the check wrote it")
    void grantWarningRedactedWithoutAgentRead() {
        Workflow workflow = workflow();
        WorkflowValidator.Report report = new WorkflowValidator.Report(
                List.of(),
                List.of(new WorkflowValidator.Problem("grant_missing", "Rhea is not allowed to use Gmail.", "a", null)),
                Map.of());
        when(service.get(ORG, workflow.getId())).thenReturn(workflow);
        when(service.check(ORG, workflow)).thenReturn(report);

        signIn(Permission.Codes.TASK_READ);
        WorkflowController.ProblemView hidden = controller.get(workflow.getId()).warnings().get(0);
        signIn(Permission.Codes.TASK_READ, Permission.Codes.AGENT_READ);
        WorkflowController.ProblemView shown = controller.get(workflow.getId()).warnings().get(0);

        assertThat(hidden.code()).isEqualTo("grant_missing");
        assertThat(hidden.nodeId()).isEqualTo("a");
        assertThat(hidden.message()).isEqualTo(WorkflowController.GRANT_MISSING_UNNAMED).doesNotContain("Gmail");
        assertThat(shown.message()).isEqualTo("Rhea is not allowed to use Gmail.");
    }

    // @find: test can change flag in views, workflow controller
    @Test
    @DisplayName("each view says whether the caller may change the workflow: its maker or a canceller with task:create")
    void canChangeFlag() throws Exception {
        Workflow workflow = workflow();
        String maker = UUID.randomUUID().toString();
        java.lang.reflect.Field createdBy = os.aiworkforce.platform.web.persistence.BaseEntity.class.getDeclaredField("createdBy");
        createdBy.setAccessible(true);
        createdBy.set(workflow, maker);
        when(service.list(ORG)).thenReturn(List.of(workflow));
        when(service.latestRuns(eq(ORG), any())).thenReturn(Map.of());
        when(service.goalsOf(any())).thenReturn(Map.of());
        when(service.get(ORG, workflow.getId())).thenReturn(workflow);
        when(service.check(ORG, workflow)).thenReturn(new WorkflowValidator.Report(List.of(), List.of(), Map.of()));

        RequestContext.setActor(Actor.user(maker, ORG.toString(), "role", Set.of(Permission.Codes.TASK_CREATE), 0L));
        assertThat(controller.get(workflow.getId()).canChange()).isTrue();
        assertThat(controller.list().get(0).canChange()).isTrue();

        signIn(Permission.Codes.TASK_READ, Permission.Codes.TASK_CREATE);
        assertThat(controller.get(workflow.getId()).canChange()).isFalse();
        assertThat(controller.list().get(0).canChange()).isFalse();

        signIn(Permission.Codes.TASK_CREATE, Permission.Codes.TASK_CANCEL);
        assertThat(controller.get(workflow.getId()).canChange()).isTrue();
    }

    // @find: test another workspaces workflow is not found, workflow controller
    @Test
    @DisplayName("another workspace's workflow is a 404, as the service reports it")
    void otherWorkspaceNotFound() {
        UUID theirs = UUID.randomUUID();
        when(service.get(ORG, theirs)).thenThrow(ApiException.notFound("workflow", theirs));

        ApiException refused = org.assertj.core.api.Assertions.catchThrowableOfType(() -> controller.get(theirs), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
