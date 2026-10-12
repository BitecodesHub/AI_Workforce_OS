// @find: workflow service, list workflows, create workflow, save workflow, update workflow, delete workflow, run workflow, start workflow, workflow to goal, compile workflow to tasks, depends on from edges, run inputs, placeholders, version conflict, workflow palette, workflow run history, WorkflowService
// @what: Business logic for workflows: listing, saving with an optimistic version check, deleting, the canvas palette, run history, and running a workflow as one goal whose tasks are its steps.
// @flow: Called by WorkflowController; parses with WorkflowGraph, checks with WorkflowValidator, starts work through GoalService.createGoal, stores WorkflowRun rows, audits after commit
package os.aiworkforce.orchestrator.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.catalog.ConnectorInfo;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Keeps a workspace's workflows and runs them.
 *
 * <p>Running a workflow creates exactly one goal through {@link GoalService#createGoal}, the path
 * chat, schedules and the goal endpoint use, so approvals, traces, hand-offs, the concurrency cap
 * and the Orchestrator board treat it like any other goal. Each step becomes one task; each
 * connection becomes a dependency, so a step waits for every step that hands over to it and is
 * given their results. Nothing here calls a tool or a model.
 *
 * <p>Saving is checked against the version the editor loaded: a save based on an older version is
 * refused with {@code VERSION_CONFLICT} (409) rather than silently replacing somebody else's work.
 * A run can name the version on screen too, so it never quietly runs a newer one.
 *
 * <p>Changing or deleting a workflow is for the person who made it, or for someone who can cancel
 * work ({@code task:cancel}), the rule schedules follow: a run acts as whoever starts it, so what a
 * workflow does is not for any colleague to rewrite.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 1_000;
    /** The most workflows the list returns; a workspace with more needs search, not a longer page. */
    static final int LIST_LIMIT = 200;
    /** How many of a workflow's runs its history shows. */
    static final int HISTORY_LIMIT = 20;
    static final int MAX_INPUTS = 20;
    static final int MAX_INPUT_VALUE = 2_000;
    /** A task instruction after the run's values are filled in; the template itself is capped at 10,000. */
    static final int MAX_TASK_INSTRUCTION = 14_000;
    /** A task title, as the goal endpoint caps it. */
    static final int MAX_TASK_TITLE = 200;

    /** A placeholder in an instruction, {@code {{customer name}}}: a letter, then letters, digits, spaces, dashes or underscores. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9 _-]{0,39}?)\\s*\\}\\}");
    /** What a run value may be called: the same rule as the placeholder's name. */
    private static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,39}");

    private static final String RESOURCE = "workflow";
    private static final String VERSION_CONFLICT_MESSAGE =
            "Someone else saved this workflow while you were editing it. Reload it to see their changes, then make yours again.";
    private static final String RUN_VERSION_CONFLICT_MESSAGE =
            "Someone else saved this workflow since you opened it. Reload it to see what would run, then run it again.";

    private final Workflows workflows;
    private final WorkflowRuns runs;
    private final WorkflowValidator validator;
    private final WorkflowReads reads;
    private final GoalService goalService;
    private final Goals goals;
    private final Agents agents;
    private final ConnectorCatalog catalog;
    private final AuditClient audit;

    /** Wires the repositories, the validator, the goal service that starts the work, the catalogue and the audit client. */
    public WorkflowService(
            Workflows workflows,
            WorkflowRuns runs,
            WorkflowValidator validator,
            WorkflowReads reads,
            GoalService goalService,
            Goals goals,
            Agents agents,
            ConnectorCatalog catalog,
            AuditClient audit) {
        this.workflows = workflows;
        this.runs = runs;
        this.validator = validator;
        this.reads = reads;
        this.goalService = goalService;
        this.goals = goals;
        this.agents = agents;
        this.catalog = catalog;
        this.audit = audit;
    }

    /**
     * What a person sends to create or replace a workflow. Every field is the whole new value.
     *
     * @param graph the canvas JSON, as decoded; parsed by {@link WorkflowGraph#parse}
     */
    public record WorkflowInput(String name, String description, Object graph) {}

    /** A workflow as saved, with what the check found, so the editor can show the warnings. */
    public record Saved(Workflow workflow, WorkflowValidator.Report report) {}

    /** A run that has started: the workflow run row and the goal it created. */
    public record Started(WorkflowRun run, Goal goal) {}

    /**
     * One agent on the palette: who it is and the connectors it may use.
     *
     * @param description null for a caller who may not read agent configuration
     * @param connectors the connectors it is granted; null for a caller who may not read agent
     *     configuration ({@code agent:read})
     */
    public record PaletteAgent(
            UUID id,
            String name,
            String category,
            String status,
            boolean fallback,
            String description,
            List<String> connectors) {}

    /** One connector on the palette, from the catalogue. */
    public record PaletteConnector(String id, String name, String category, String description) {}

    /** What the canvas can place: the workspace's agents (retired ones left out) and every connector. */
    public record Palette(List<PaletteAgent> agents, List<PaletteConnector> connectors) {}

    // ---- Reading --------------------------------------------------------------------------------

    // @find: list workflows of workspace
    /** The workspace's workflows, most recently changed first, at most {@link #LIST_LIMIT}. */
    @Transactional(readOnly = true)
    public List<Workflow> list(UUID orgId) {
        return workflows.findByOrgIdOrderByUpdatedAtDesc(orgId, PageRequest.of(0, LIST_LIMIT));
    }

    // @find: get one workflow
    /** One workflow of this workspace, or {@code NOT_FOUND}, also when it belongs to another workspace. */
    @Transactional(readOnly = true)
    public Workflow get(UUID orgId, UUID id) {
        return workflows.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound(RESOURCE, id));
    }

    // @find: check stored workflow, problems shown in the editor
    /**
     * What is wrong with a stored workflow now, without anybody saving again: an agent paused
     * since it was saved shows up as an error (a save would refuse it), a grant removed since as a
     * warning.
     */
    @Transactional(readOnly = true)
    public WorkflowValidator.Report check(UUID orgId, Workflow workflow) {
        return validator.check(orgId, WorkflowGraph.parse(workflow.getGraph()), WorkflowValidator.Mode.SAVE);
    }

    // @find: latest run of each workflow, workflows list
    /** The newest run of each given workflow, by workflow id; one query. */
    @Transactional(readOnly = true)
    public Map<UUID, WorkflowRun> latestRuns(UUID orgId, List<UUID> workflowIds) {
        if (workflowIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, WorkflowRun> latest = new HashMap<>();
        for (WorkflowRun run : runs.latestOf(orgId, workflowIds)) {
            latest.putIfAbsent(run.getWorkflowId(), run);
        }
        return latest;
    }

    // @find: workflow run history
    /** One workflow's runs, newest first, at most {@link #HISTORY_LIMIT}; {@code NOT_FOUND} for a workflow of another workspace. */
    @Transactional(readOnly = true)
    public List<WorkflowRun> history(UUID orgId, UUID workflowId) {
        get(orgId, workflowId);
        return runs.findByOrgIdAndWorkflowIdOrderByCreatedAtDesc(orgId, workflowId, PageRequest.of(0, HISTORY_LIMIT));
    }

    // @find: goals of workflow runs, run status
    /** The goals of the given runs, by goal id; one query. */
    @Transactional(readOnly = true)
    public Map<UUID, Goal> goalsOf(List<WorkflowRun> workflowRuns) {
        if (workflowRuns.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = workflowRuns.stream().map(WorkflowRun::getGoalId).toList();
        return goals.findAllById(ids).stream().collect(Collectors.toMap(Goal::getId, Function.identity()));
    }

    // @find: workflow palette, agents and connectors for the canvas
    /**
     * What the canvas palette offers: every agent that is not retired, with the connectors it is
     * granted, and every connector in the catalogue. Two queries, whatever the size (one without
     * the configuration).
     *
     * @param withConfiguration whether the caller may read agent configuration ({@code
     *     agent:read}); without it each agent carries only its name, category and status, and
     *     neither its description nor its grants
     */
    @Transactional(readOnly = true)
    public Palette palette(UUID orgId, boolean withConfiguration) {
        Map<UUID, Set<String>> granted = new HashMap<>();
        if (withConfiguration) {
            for (AgentToolGrant grant : reads.enabledGrantsInWorkspace(orgId)) {
                granted.computeIfAbsent(grant.getAgentId(), id -> new LinkedHashSet<>()).add(grant.getServer());
            }
        }
        List<PaletteAgent> paletteAgents = agents.findByOrgIdOrderByName(orgId).stream()
                .filter(agent -> !agent.isRetired())
                .map(agent -> new PaletteAgent(
                        agent.getId(),
                        agent.getName(),
                        agent.getCategory(),
                        agent.getStatus(),
                        agent.isFallback(),
                        withConfiguration ? agent.getDescription() : null,
                        withConfiguration ? List.copyOf(granted.getOrDefault(agent.getId(), Set.of())) : null))
                .toList();
        List<PaletteConnector> connectors = catalog.all().stream()
                .map(info -> new PaletteConnector(info.server(), info.displayName(), info.category(), info.description()))
                .toList();
        return new Palette(paletteAgents, connectors);
    }

    // ---- Changing -------------------------------------------------------------------------------

    // @find: who may change a workflow, workflow owner, maker or task:cancel
    /**
     * Whether the actor may change or delete this workflow: they can start work ({@code
     * task:create}) and either made it or can cancel work ({@code task:cancel}).
     */
    public static boolean canChange(Workflow workflow, Actor actor) {
        if (actor == null || !actor.hasPermission(Permission.Codes.TASK_CREATE)) {
            return false;
        }
        return isMaker(workflow, actor) || actor.hasPermission(Permission.Codes.TASK_CANCEL);
    }

    /** Whether the actor is the person who made the workflow. */
    private static boolean isMaker(Workflow workflow, Actor actor) {
        return actor != null && workflow.getCreatedBy() != null && workflow.getCreatedBy().equals(actor.humanId());
    }

    /**
     * Refuses a change to someone else's workflow from an actor who cannot cancel work; the
     * caller has already been checked for {@code task:create}.
     */
    private static void requireMakerOrCanceller(Workflow workflow, String refusal) {
        Actor actor = RequestContext.actor().orElse(null);
        if (!isMaker(workflow, actor) && (actor == null || !actor.hasPermission(Permission.Codes.TASK_CANCEL))) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, refusal)
                    .with("requiredPermission", Permission.Codes.TASK_CANCEL);
        }
    }

    // @find: create workflow, new workflow, POST /api/workflows
    /**
     * Saves a new workflow.
     *
     * <p>Flow: checks the name and description, parses the graph, checks it in {@link
     * WorkflowValidator.Mode#SAVE} (any error refuses with 422 listing every problem), writes the
     * row and records {@code workflow.create} once it commits.
     */
    @Transactional
    public Saved create(UUID orgId, WorkflowInput input) {
        Valid valid = validate(orgId, input);
        Workflow workflow = new Workflow();
        workflow.setId(UuidV7.generate());
        workflow.setOrgId(orgId);
        apply(workflow, valid);
        workflows.saveAndFlush(workflow);
        log.info("Workflow {} created in workspace {} with {} step(s)", workflow.getId(), orgId, valid.graph().nodes().size());
        recordAfterCommit(orgId, "workflow.create", workflow, Map.of("steps", valid.graph().nodes().size()));
        return new Saved(workflow, valid.report());
    }

    // @find: update workflow, save workflow, PUT /api/workflows/{id}, version check, 409 conflict
    /**
     * Replaces a workflow's name, description and canvas, if nobody else saved it first.
     *
     * <p>{@code expectedVersion} is the version the editor loaded. When the stored one differs,
     * the save is refused with {@code VERSION_CONFLICT} (409) and nothing is written; a save that
     * loses the race to a concurrent one at flush time is refused the same way.
     *
     * @throws ApiException {@code VALIDATION_FAILED} without a version, {@code NOT_FOUND} for a
     *     workflow of another workspace, {@code PERMISSION_DENIED} for someone else's workflow
     *     without {@code task:cancel}, {@code VERSION_CONFLICT} for a stale version
     */
    @Transactional
    public Saved update(UUID orgId, UUID id, WorkflowInput input, Long expectedVersion) {
        if (expectedVersion == null) {
            throw ApiException.validation("version", "Reload the workflow and save again.");
        }
        Workflow workflow = get(orgId, id);
        requireMakerOrCanceller(
                workflow, "Only the person who made this workflow, or someone who can cancel work, can change it.");
        if (workflow.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Valid valid = validate(orgId, input);
        String previousName = workflow.getName();
        apply(workflow, valid);
        try {
            workflows.saveAndFlush(workflow);
        } catch (OptimisticLockingFailureException lostRace) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        recordAfterCommit(
                orgId,
                "workflow.update",
                workflow,
                Map.of("previousName", previousName, "steps", valid.graph().nodes().size()));
        return new Saved(workflow, valid.report());
    }

    // @find: delete workflow, remove workflow, DELETE /api/workflows/{id}
    /**
     * Deletes a workflow. Its runs stay, unlinked from it (the database clears their workflow id)
     * but keeping the name and the canvas they ran, so the goals they created can still be
     * replayed; those goals are the work itself, with their own traces and audit entries.
     *
     * @throws ApiException {@code NOT_FOUND} for a workflow of another workspace, {@code
     *     PERMISSION_DENIED} for someone else's workflow without {@code task:cancel}
     */
    @Transactional
    public void delete(UUID orgId, UUID id) {
        Workflow workflow = get(orgId, id);
        requireMakerOrCanceller(
                workflow, "Only the person who made this workflow, or someone who can cancel work, can delete it.");
        workflows.delete(workflow);
        recordAfterCommit(orgId, "workflow.delete", workflow, Map.of());
    }

    // ---- Running --------------------------------------------------------------------------------

    // @find: run workflow, start workflow, POST /api/workflows/{id}/run, compile steps to goal tasks
    /**
     * Runs a workflow as one goal.
     *
     * <p>Flow: checks the stored graph in {@link WorkflowValidator.Mode#RUN} (a paused agent or a
     * missing grant now refuses, naming what is missing), checks the run's values and that every
     * {@code {{placeholder}}} has one, orders the steps ({@link WorkflowGraph#executionOrder}),
     * and turns each into a task: its agent, a title, its instruction with the values filled in
     * (steps nothing hands over to also get the values listed, and a step with connectors gets a
     * line naming them), and the positions of the steps that hand over to it as {@code dependsOn}.
     * Then {@link GoalService#createGoal} saves the goal and starts its first tasks after commit,
     * as the caller; the workflow run row records the version, a copy of the graph and which node
     * each task came from. Records {@code workflow.run} once it commits.
     *
     * <p>The goal's dependencies are explicit: a step nothing hands over to starts at once, beside
     * the others, and a step that fails stops only the steps that wait on it.
     *
     * @param rawInputs the values the person filled in, by name; may be null
     * @param expectedVersion the version the person was looking at, or null to run whatever is
     *     stored; a different stored version is refused with {@code VERSION_CONFLICT}
     * @return the run and its goal
     * @throws ApiException {@code VALIDATION_FAILED} with every problem in one sentence when the
     *     workflow cannot run; {@code VERSION_CONFLICT} when it changed since the given version
     */
    @Transactional
    public Started run(UUID orgId, UUID workflowId, Map<String, String> rawInputs, Long expectedVersion) {
        Workflow workflow = get(orgId, workflowId);
        if (expectedVersion != null && workflow.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, RUN_VERSION_CONFLICT_MESSAGE);
        }
        WorkflowGraph graph = WorkflowGraph.parse(workflow.getGraph());
        WorkflowValidator.Report report = validator.check(orgId, graph, WorkflowValidator.Mode.RUN);
        if (!report.ok()) {
            throw refusal("This workflow cannot run yet. ", report.errors());
        }
        Map<String, String> inputs = inputs(rawInputs);
        List<String> missing = missingInputs(graph, inputs);
        if (!missing.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Fill in " + String.join(", ", missing) + " before running this workflow.")
                    .with("missing", missing);
        }

        List<WorkflowGraph.Node> order = graph.executionOrder();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            positions.put(order.get(i).id(), i);
        }
        Map<String, List<String>> before = graph.predecessors();
        List<GoalService.NewTask> tasks = new ArrayList<>();
        for (WorkflowGraph.Node node : order) {
            Agent agent = report.agents().get(node.agentId());
            List<Integer> dependsOn = before.get(node.id()).stream()
                    .map(positions::get)
                    .sorted()
                    .toList();
            tasks.add(new GoalService.NewTask(
                    node.agentId(),
                    taskTitle(node, agent, inputs),
                    taskInstruction(node, inputs, dependsOn.isEmpty()),
                    dependsOn));
        }

        String description = workflow.getDescription().isBlank()
                ? "Started from the workflow \"" + workflow.getName() + "\"."
                : workflow.getDescription() + "\n\nStarted from the workflow \"" + workflow.getName() + "\".";
        Goal goal = goalService.createGoal(
                orgId,
                new GoalService.NewGoal(workflow.getName(), description, null, "manual", null, null, tasks, true),
                true);

        WorkflowRun run = new WorkflowRun();
        run.setId(UuidV7.generate());
        run.setOrgId(orgId);
        run.setWorkflowId(workflow.getId());
        run.setWorkflowName(workflow.getName());
        run.setWorkflowVersion(workflow.getVersion());
        run.setGoalId(goal.getId());
        run.setGraph(graph.toMap());
        run.setNodeIds(order.stream().map(WorkflowGraph.Node::id).toList());
        run.setInputs(new LinkedHashMap<>(inputs));
        runs.saveAndFlush(run);
        log.info("Workflow {} ran as goal {} with {} task(s)", workflowId, goal.getId(), tasks.size());
        recordAfterCommit(
                orgId,
                "workflow.run",
                workflow,
                Map.of("goalId", goal.getId().toString(), "version", workflow.getVersion(), "steps", tasks.size()));
        return new Started(run, goal);
    }

    /** The run's values, trimmed, blanks dropped; refuses a bad name, an over-long value or too many. */
    static Map<String, String> inputs(Map<String, String> raw) {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (raw == null) {
            return inputs;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().strip();
            if (!INPUT_NAME.matcher(name).matches()) {
                throw ApiException.validation("inputs", "A value for this run has a name that could not be read.");
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            if (value.length() > MAX_INPUT_VALUE) {
                throw ApiException.validation(
                        "inputs", "The value for " + name + " can be at most " + MAX_INPUT_VALUE + " characters.");
            }
            if (!value.isEmpty()) {
                inputs.put(name, value);
            }
        }
        if (inputs.size() > MAX_INPUTS) {
            throw ApiException.validation("inputs", "A run can carry at most " + MAX_INPUTS + " values.");
        }
        return inputs;
    }

    // @find: placeholders in instructions, values a workflow needs
    /** Every placeholder name the steps use, distinct, in canvas order. */
    public static List<String> placeholders(WorkflowGraph graph) {
        Set<String> names = new LinkedHashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            Matcher matcher = PLACEHOLDER.matcher(node.instructionText());
            while (matcher.find()) {
                names.add(matcher.group(1).strip());
            }
        }
        return List.copyOf(names);
    }

    // @find: fill in placeholders, replace {{name}} with its value
    /** The text with every placeholder that has a value replaced by it; one without a value is left as written. */
    static String fill(String template, Map<String, String> inputs) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String value = inputs.getOrDefault(matcher.group(1).strip(), matcher.group());
            matcher.appendReplacement(text, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(text);
        return text.toString();
    }

    /** The placeholders no value was given for. */
    private static List<String> missingInputs(WorkflowGraph graph, Map<String, String> inputs) {
        return placeholders(graph).stream().filter(name -> !inputs.containsKey(name)).toList();
    }

    /**
     * The task's title: the step's own, or the agent's name and the first words of the instruction,
     * with the run's values filled in either way.
     */
    static String taskTitle(WorkflowGraph.Node node, Agent agent, Map<String, String> inputs) {
        String title = fill(node.titleText(), inputs);
        if (title.isEmpty()) {
            String firstLine = fill(node.instructionText(), inputs).strip().lines().findFirst().orElse("").strip();
            if (firstLine.length() > 80) {
                firstLine = firstLine.substring(0, 79).strip() + "…";
            }
            String who = agent == null ? "Step" : agent.getName();
            title = firstLine.isEmpty() ? who : who + ": " + firstLine;
        }
        return title.length() > MAX_TASK_TITLE ? title.substring(0, MAX_TASK_TITLE) : title;
    }

    /**
     * The task's instruction: the step's, with every placeholder replaced by its value; a step
     * nothing hands over to also lists the run's values, and a step that needs connectors names
     * them, so the agent reaches for the right tool.
     */
    String taskInstruction(WorkflowGraph.Node node, Map<String, String> inputs, boolean firstStep) {
        StringBuilder text = new StringBuilder(fill(node.instructionText(), inputs));
        if (firstStep && !inputs.isEmpty()) {
            text.append("\n\nDetails for this run:");
            inputs.forEach((name, value) -> text.append("\n- ").append(name).append(": ").append(value));
        }
        if (!node.connectors().isEmpty()) {
            List<String> names = node.connectors().stream()
                    .map(id -> catalog.find(id).map(ConnectorInfo::displayName).orElse(id))
                    .toList();
            text.append("\n\nUse ").append(String.join(", ", names)).append(" for this step.");
        }
        if (text.length() > MAX_TASK_INSTRUCTION) {
            throw ApiException.validation(
                    "inputs", "The values for this run make an instruction too long. Shorten them and try again.");
        }
        return text.toString();
    }

    // ---- Validation -----------------------------------------------------------------------------

    /** A request after trimming and checking, with its parsed graph and what the check found. */
    private record Valid(String name, String description, WorkflowGraph graph, WorkflowValidator.Report report) {}

    /** Checks a request, refusing the first broken field, then every graph error at once. */
    private Valid validate(UUID orgId, WorkflowInput input) {
        if (input == null) {
            throw ApiException.validation("name", "Give the workflow a name.");
        }
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty()) {
            throw ApiException.validation("name", "Give the workflow a name.");
        }
        if (name.length() > MAX_NAME) {
            throw ApiException.validation("name", "A workflow name can be at most " + MAX_NAME + " characters.");
        }
        String description = input.description() == null ? "" : input.description().strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw ApiException.validation(
                    "description", "A description can be at most " + String.format("%,d", MAX_DESCRIPTION) + " characters.");
        }
        WorkflowGraph graph = WorkflowGraph.parse(input.graph());
        WorkflowValidator.Report report = validator.check(orgId, graph, WorkflowValidator.Mode.SAVE);
        if (!report.ok()) {
            throw refusal("The workflow was not saved. ", report.errors());
        }
        return new Valid(name, description, graph, report);
    }

    /**
     * A 422 whose message lists every problem, with the problems as structured detail. The
     * detail carries no plain string entries, so the console shows the sentence rather than
     * reading it as one form field.
     */
    private static ApiException refusal(String lead, List<WorkflowValidator.Problem> problems) {
        List<Map<String, Object>> detail = problems.stream()
                .map(problem -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("code", problem.code());
                    entry.put("message", problem.message());
                    entry.put("nodeId", problem.nodeId());
                    entry.put("edgeId", problem.edgeId());
                    return Collections.unmodifiableMap(entry);
                })
                .toList();
        return new ApiException(ErrorCode.VALIDATION_FAILED, lead + WorkflowValidator.describe(problems))
                .with("problems", detail);
    }

    /** Copies the validated fields onto the entity. */
    private static void apply(Workflow workflow, Valid valid) {
        workflow.setName(valid.name());
        workflow.setDescription(valid.description());
        workflow.setGraph(valid.graph().toMap());
    }

    // ---- Audit ----------------------------------------------------------------------------------

    /** Records an audit event for a workflow action once the surrounding transaction commits, so a rolled-back change is never reported. */
    private void recordAfterCommit(UUID orgId, String action, Workflow workflow, Map<String, Object> extra) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", workflow.getName());
        detail.putAll(extra);
        Map<String, Object> frozen = Collections.unmodifiableMap(detail);
        String workflowId = workflow.getId().toString();
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, action, RESOURCE, workflowId, "succeeded", frozen));
    }
}
