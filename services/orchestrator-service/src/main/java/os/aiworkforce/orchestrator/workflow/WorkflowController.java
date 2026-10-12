// @find: workflows api, list workflows, get workflow, create workflow, update workflow, save workflow, delete workflow, run workflow, workflow runs, workflow palette, /api/workflows, GET /api/workflows, POST /api/workflows, PUT /api/workflows/{id}, DELETE /api/workflows/{id}, POST /api/workflows/{id}/run, GET /api/workflows/{id}/runs, GET /api/workflows/palette, Workflows page, workflow canvas, WorkflowController
// @what: REST endpoints behind the Workflows page and the canvas editor: list, read, save with a version check, delete, run, run history and the palette.
// @flow: Calls WorkflowService; views carry the check's errors and warnings so the canvas can mark steps
package os.aiworkforce.orchestrator.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Workflows: repeatable work drawn on a canvas.
 *
 * <p>Reading needs {@code task:read}, the permission that shows goals and tasks, since a workflow
 * is a goal waiting to be started. Saving, deleting and running need {@code task:create}, the
 * permission that starts work: a run creates a goal exactly as the goal endpoint does. Changing or
 * deleting someone else's workflow also needs {@code task:cancel}; each view says whether the
 * caller may change it. A workflow of another workspace is not found, so its existence is never
 * confirmed.
 *
 * <p>Which connectors each agent is granted is agent configuration, behind {@code agent:read}: a
 * caller without it gets a palette without grants or descriptions, and a missing grant described
 * without naming the connector.
 */
@RestController
@RequestMapping("/api/workflows")
@Tag(name = "Workflows")
public class WorkflowController {

    private final WorkflowService service;

    /** Creates the controller with the workflow service. */
    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    /**
     * A new workflow, or the whole new value of an existing one.
     *
     * @param graph the canvas: {@code {"nodes": [...], "edges": [...]}}
     * @param version for an update, the version the editor loaded; refused with 409 when stale
     */
    public record WorkflowRequest(String name, String description, Map<String, Object> graph, Long version) {}

    /**
     * The body of a run request.
     *
     * @param inputs the values for the instruction placeholders, by name
     * @param version the version the person is looking at; when given and no longer the stored
     *     one, the run is refused with 409 rather than running a canvas they have not seen
     */
    public record RunRequest(Map<String, String> inputs, Long version) {}

    /** One problem, as the canvas reads it. */
    public record ProblemView(String code, String message, String nodeId, String edgeId) {}

    /** A workflow's last run, for the list. */
    public record LastRunView(UUID goalId, String status, Instant startedAt, Instant completedAt) {}

    /**
     * A workflow as the list shows it: no graph, only its size and its last run.
     *
     * @param canChange whether the caller may change and delete it
     */
    public record WorkflowSummary(
            UUID id,
            String name,
            String description,
            int steps,
            int connections,
            long version,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt,
            LastRunView lastRun,
            boolean canChange) {}

    /**
     * A workflow as the editor reads it.
     *
     * @param placeholders the {@code {{names}}} the steps use, which a run asks values for
     * @param errors problems that would stop a save now (none for a stored workflow, unless the rules changed)
     * @param warnings problems a save tolerates and a run would not: a missing grant
     * @param canChange whether the caller may save over it and delete it
     */
    public record WorkflowView(
            UUID id,
            String name,
            String description,
            Map<String, Object> graph,
            long version,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt,
            List<String> placeholders,
            List<ProblemView> errors,
            List<ProblemView> warnings,
            boolean canChange) {}

    /** One run of a workflow, with the state of the goal it created. */
    public record RunView(
            UUID id,
            UUID workflowId,
            long workflowVersion,
            UUID goalId,
            String status,
            String startedBy,
            Instant startedAt,
            Instant completedAt) {}

    // @find: list workflows, GET /api/workflows, Workflows page
    /** Every workflow in the workspace, most recently changed first, each with its last run. */
    @GetMapping
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "Every workflow in this workspace, most recently changed first")
    public List<WorkflowSummary> list() {
        UUID orgId = orgId();
        Actor actor = RequestContext.actor().orElse(null);
        List<Workflow> found = service.list(orgId);
        Map<UUID, WorkflowRun> latest = service.latestRuns(orgId, found.stream().map(Workflow::getId).toList());
        Map<UUID, Goal> goals = service.goalsOf(List.copyOf(latest.values()));
        return found.stream()
                .map(workflow -> {
                    WorkflowGraph graph = WorkflowGraph.parse(workflow.getGraph());
                    WorkflowRun run = latest.get(workflow.getId());
                    Goal goal = run == null ? null : goals.get(run.getGoalId());
                    return new WorkflowSummary(
                            workflow.getId(),
                            workflow.getName(),
                            workflow.getDescription(),
                            graph.nodes().size(),
                            graph.edges().size(),
                            workflow.getVersion(),
                            workflow.getCreatedBy(),
                            workflow.getUpdatedBy(),
                            workflow.getCreatedAt(),
                            workflow.getUpdatedAt(),
                            run == null
                                    ? null
                                    : new LastRunView(
                                            run.getGoalId(),
                                            goal == null ? null : goal.getStatus(),
                                            run.getCreatedAt(),
                                            goal == null ? null : goal.getCompletedAt()),
                            WorkflowService.canChange(workflow, actor));
                })
                .toList();
    }

    // @find: workflow palette, GET /api/workflows/palette, agents and connectors to drag
    /** The agents and connectors the canvas palette offers; grants and descriptions only with {@code agent:read}. */
    @GetMapping("/palette")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "The agents and connectors a workflow can use")
    public WorkflowService.Palette palette() {
        return service.palette(orgId(), seesAgentConfiguration());
    }

    // @find: get workflow, GET /api/workflows/{id}, open workflow editor
    /** One workflow with its canvas and what is wrong with it now. */
    @GetMapping("/{workflowId}")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "One workflow and its canvas")
    public WorkflowView get(@PathVariable UUID workflowId) {
        UUID orgId = orgId();
        Workflow workflow = service.get(orgId, workflowId);
        return view(workflow, service.check(orgId, workflow));
    }

    // @find: create workflow, POST /api/workflows, save new workflow
    /** Saves a new workflow; 422 listing every problem when it cannot be saved. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Create a workflow")
    public WorkflowView create(@RequestBody WorkflowRequest request) {
        WorkflowService.Saved saved = service.create(orgId(), input(request));
        return view(saved.workflow(), saved.report());
    }

    // @find: update workflow, PUT /api/workflows/{id}, save workflow, version conflict 409
    /** Replaces a workflow; 409 when someone else saved it since the given version was read. */
    @PutMapping("/{workflowId}")
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Replace a workflow's name, description and canvas, if nobody saved it first")
    public WorkflowView update(@PathVariable UUID workflowId, @RequestBody WorkflowRequest request) {
        WorkflowService.Saved saved =
                service.update(orgId(), workflowId, input(request), request == null ? null : request.version());
        return view(saved.workflow(), saved.report());
    }

    // @find: delete workflow, DELETE /api/workflows/{id}
    /** Deletes a workflow; its runs and the goals they started stay, so they can still be replayed. */
    @DeleteMapping("/{workflowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Delete a workflow")
    public void delete(@PathVariable UUID workflowId) {
        service.delete(orgId(), workflowId);
    }

    // @find: run workflow, POST /api/workflows/{id}/run, start workflow
    /**
     * Runs a workflow as one goal and answers once it is saved; the work happens in the
     * background. 409 when the body names a version that is no longer the stored one.
     */
    @PostMapping("/{workflowId}/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.TASK_CREATE)
    @Operation(summary = "Run a workflow: one goal whose tasks are its steps")
    public RunView run(@PathVariable UUID workflowId, @RequestBody(required = false) RunRequest request) {
        WorkflowService.Started started =
                service.run(
                        orgId(),
                        workflowId,
                        request == null ? null : request.inputs(),
                        request == null ? null : request.version());
        return runView(started.run(), started.goal());
    }

    // @find: workflow run history, GET /api/workflows/{id}/runs
    /** A workflow's runs, newest first, each with its goal's state. */
    @GetMapping("/{workflowId}/runs")
    @RequiresPermission(Permission.Codes.TASK_READ)
    @Operation(summary = "A workflow's runs, newest first")
    public List<RunView> runs(@PathVariable UUID workflowId) {
        UUID orgId = orgId();
        List<WorkflowRun> history = service.history(orgId, workflowId);
        Map<UUID, Goal> goals = service.goalsOf(history);
        return history.stream().map(run -> runView(run, goals.get(run.getGoalId()))).toList();
    }

    /** Converts a request body to the service input; a missing body stays null so the service reports it. */
    private static WorkflowService.WorkflowInput input(WorkflowRequest request) {
        if (request == null) {
            return null;
        }
        return new WorkflowService.WorkflowInput(request.name(), request.description(), request.graph());
    }

    /** The editor's view of a workflow with what a check found, for the current caller. */
    static WorkflowView view(Workflow workflow, WorkflowValidator.Report report) {
        Actor actor = RequestContext.actor().orElse(null);
        boolean configuration = actor != null && actor.hasPermission(Permission.Codes.AGENT_READ);
        WorkflowGraph graph = WorkflowGraph.parse(workflow.getGraph());
        return new WorkflowView(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                graph.toMap(),
                workflow.getVersion(),
                workflow.getCreatedBy(),
                workflow.getUpdatedBy(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                WorkflowService.placeholders(graph),
                problems(report.errors(), configuration),
                problems(report.warnings(), configuration),
                WorkflowService.canChange(workflow, actor));
    }

    /** What a missing grant says to a caller who may not read agent configuration. */
    static final String GRANT_MISSING_UNNAMED =
            "The agent on this step may not be allowed to use every connector it needs. Someone who manages agents"
                    + " can check its connectors before this workflow runs.";

    /**
     * Problems as views. Without agent configuration ({@code agent:read}) a missing grant does not
     * say which connector the agent lacks.
     */
    private static List<ProblemView> problems(List<WorkflowValidator.Problem> problems, boolean configuration) {
        return problems.stream()
                .map(problem -> new ProblemView(
                        problem.code(),
                        !configuration && "grant_missing".equals(problem.code()) ? GRANT_MISSING_UNNAMED : problem.message(),
                        problem.nodeId(),
                        problem.edgeId()))
                .toList();
    }

    /** Whether the caller may read agent configuration, such as which connectors an agent is granted. */
    private static boolean seesAgentConfiguration() {
        return RequestContext.actor().map(actor -> actor.hasPermission(Permission.Codes.AGENT_READ)).orElse(false);
    }

    /** One run with its goal's status; the goal may be missing only if it was removed since. */
    static RunView runView(WorkflowRun run, Goal goal) {
        return new RunView(
                run.getId(),
                run.getWorkflowId(),
                run.getWorkflowVersion(),
                run.getGoalId(),
                goal == null ? null : goal.getStatus(),
                run.getCreatedBy(),
                run.getCreatedAt(),
                goal == null ? null : goal.getCompletedAt());
    }

    /** The workspace of the current request. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
