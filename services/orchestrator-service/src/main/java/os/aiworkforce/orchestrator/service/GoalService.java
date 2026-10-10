// @find: goals, goal service, create goal, tasks, task graph, dependencies, hand-off between agents, chain of agents, retry goal, cancel goal, stop goal, run next task, claim task, concurrency cap, multi-agent plan, workspace tasks, requester actor
// @what: Turns a goal into a dependency graph of tasks and drives it to completion, handing each step's results to the next agent and capping concurrent work per workspace.
// @flow: Called by GoalController and chat; hands claimed tasks to RunExecutor, which calls AgentRunner
package os.aiworkforce.orchestrator.service;

import java.time.Instant;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Turns a goal into a graph of tasks, and drives that graph to completion.
 *
 * <p>The graph is a DAG, and the two rules enforced here are the ones that stop it becoming a
 * source of runaway work:
 *
 * <ul>
 *   <li><b>No cycles.</b> A dependency loop would leave every task in it permanently waiting for
 *       another, and the goal would sit in {@code running} forever with nothing happening.
 *   <li><b>Bounded retries.</b> A task that fails is retried up to its own limit and then fails
 *       the goal, rather than being re-queued indefinitely.
 * </ul>
 *
 * <p>Decomposition is currently explicit: a caller supplies the tasks. Automatic decomposition by
 * a planning agent is a later step, and it will produce exactly this shape, so nothing downstream
 * changes when it arrives.
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private static final int MAX_TASKS_PER_GOAL = 50;
    /** Chat starts a chain rather than a plan; a longer one is not something a person typed. */
    private static final int MAX_CHAT_TASKS = 5;
    /** A chain that revisits one agent more than this is a loop, not a hand-off. */
    private static final int MAX_AGENT_REPEATS = 2;
    /** More predecessors than this would make the prepended instruction unreadable. */
    private static final int MAX_HANDOFFS = 3;
    /** The most of one predecessor's result a later step is given. */
    private static final int MAX_HANDOFF_RESULT_CHARS = 5_000;
    /** What every predecessor's result together may take, shared out when there are several. */
    private static final int HANDOFF_BUDGET_CHARS = 10_000;

    /**
     * How a step of a chain carries the person's own words: their request whole, then the part of
     * it this step does. The coordinator writes it; {@link #withHandoffPreamble} reads it back, so a
     * later step's hand-off sits between the two rather than repeating either.
     */
    public static final String REQUEST_HEADING = "The person's request (verbatim):\n";

    public static final String PART_HEADING = "Your part: ";

    /**
     * The first sentence of the block of document passages the coordinator puts at the very start of
     * a first step's instruction, which is how a later step learns which passages that was.
     */
    public static final String PASSAGES_HEADING = "Passages from the workspace's documents that bear on this request.";

    private static final String PART_SEPARATOR = "\n\n" + PART_HEADING;
    private static final Pattern PASSAGE_TITLE = Pattern.compile("^\\[(\\d+)] (.+)$");
    /** How far the claim window reaches past tasks whose agent is paused, so they cannot starve it. */
    private static final int CLAIM_WINDOW = 100;

    /**
     * How many goal tasks one workspace may have running at once. More wait as pending and start
     * as soon as one settles: every run calls a model provider, and a burst of them - a morning's
     * schedules firing together - is how a workspace meets the provider's rate limit.
     */
    static final ConfigKey MAX_CONCURRENT_RUNS = ConfigKey.integer(
            "runs.maxConcurrentPerWorkspace",
            ConfigKey.Scope.WORKSPACE,
            4,
            1,
            50,
            "How many tasks one workspace may have running at the same time. Others wait their turn.");

    static final String GOAL_CANCELLED = "The goal this run belonged to was cancelled.";
    static final String PERSON_CANCELLED = "A person cancelled this goal.";
    static final String TASK_RUN_STOPPED = "The run for this task was stopped before it finished.";
    /** The steps a retry starts again from: the first that did not finish. */
    private static final Set<String> RETRY_FROM = Set.of("failed", "cancelled", "skipped");

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final RunSteps steps;
    private final AgentRunner runner;
    private final ApprovalService approvals;
    private final TaskProgress progress;
    private final Agents agents;
    private final RunExecutor runExecutor;
    private final QuestionService questions;
    private final LifecycleAnnouncer announcer;
    private final AuditClient audit;
    /** Where the per-workspace cap is read. Null in unit tests, which use the default. */
    private RuntimeConfigService runtimeConfig;

    public GoalService(
            Goals goals,
            Tasks tasks,
            Runs runs,
            RunSteps steps,
            AgentRunner runner,
            ApprovalService approvals,
            TaskProgress progress,
            Agents agents,
            @Lazy RunExecutor runExecutor,
            QuestionService questions,
            LifecycleAnnouncer announcer,
            AuditClient audit) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.steps = steps;
        this.runner = runner;
        this.approvals = approvals;
        this.progress = progress;
        this.agents = agents;
        this.runExecutor = runExecutor;
        this.questions = questions;
        this.announcer = announcer;
        this.audit = audit;
    }

    @Autowired(required = false)
    void setRuntimeConfig(RuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        if (runtimeConfig != null) {
            runtimeConfig.register(List.of(MAX_CONCURRENT_RUNS));
        }
    }

    /** How much a stop withdrew, for a caller that reports it. */
    public record CancelCounts(int tasks, int runs, int approvals, int questions) {}

    /** The goal as it stands after a retry, and the step it starts again from. */
    public record RetryResult(Goal goal, Task fromTask) {}

    /**
     * @param title what the person asked for
     * @param instruction the full request
     * @param agentId the agent to do it, for a single-step goal
     * @param dependsOn positions this task waits for, by index within the same request
     */
    public record TaskRequest(String title, String instruction, UUID agentId, List<Integer> dependsOn) {}

    /**
     * One task in a goal being created, before it has an id.
     *
     * @param dependsOnPositions positions within the same {@link NewGoal#tasks()} this one waits
     *     for; empty means the old, simpler rule instead - wait for every earlier position
     */
    public record NewTask(UUID agentId, String title, String instruction, List<Integer> dependsOnPositions) {}

    /**
     * @param requestedBy who this is for, when it is known ahead of the goal's own default (the
     *     acting person, read from the request context)
     * @param source {@code manual}, {@code chat} or {@code schedule}
     * @param conversationId the chat conversation this goal answers, when {@code source} is
     *     {@code chat}
     * @param scheduleId the schedule that fired this goal, when {@code source} is {@code schedule}
     */
    public record NewGoal(
            String title,
            String description,
            UUID requestedBy,
            String source,
            UUID conversationId,
            UUID scheduleId,
            List<NewTask> tasks) {}

    // @find: create goal simple, record a goal with tasks
    /**
     * The old shape, kept for callers that only record a goal: it is not dispatched here, so its
     * first task starts on the goal sweep's next tick. Use {@link #createGoal} to start it at once.
     */
    @Transactional
    public Goal create(UUID orgId, String title, String description, List<TaskRequest> taskRequests) {
        List<NewTask> newTasks = taskRequests == null
                ? null
                : taskRequests.stream()
                        .map(request -> new NewTask(
                                request.agentId(), request.title(), request.instruction(), request.dependsOn()))
                        .toList();
        return createGoal(orgId, new NewGoal(title, description, null, "manual", null, null, newTasks), false);
    }

    // @find: validate goal, dry run check, dependency cycle check
    /**
     * Checks a goal can be created, without writing anything.
     *
     * <p>Public and outside any transaction, so a caller such as the chat coordinator can refuse a
     * request before it opens its own write, rather than having a refusal mark that write
     * rollback-only. {@link #createGoal} makes the same checks first.
     */
    public void validate(NewGoal spec) {
        List<NewTask> taskSpecs = spec.tasks();
        if (taskSpecs == null || taskSpecs.isEmpty()) {
            throw ApiException.validation("tasks", "a goal needs at least one task");
        }
        if (taskSpecs.size() > MAX_TASKS_PER_GOAL) {
            // An unbounded graph is an unbounded spend, and a person cannot read a plan of two
            // hundred steps well enough to approve it anyway.
            throw ApiException.validation("tasks", "a goal may hold at most " + MAX_TASKS_PER_GOAL + " tasks");
        }
        String source = spec.source() == null ? "manual" : spec.source();
        if ("chat".equals(source) && taskSpecs.size() > MAX_CHAT_TASKS) {
            // A chat reply chains agents; a chain longer than this is not one a person composed
            // by mentioning or answering - it is a routing mistake, and refusing it here is safer
            // than running five minutes of work nobody asked for.
            throw ApiException.validation("tasks", "a chat message may start at most " + MAX_CHAT_TASKS + " tasks");
        }
        validateAgentRepeats(taskSpecs);
        validateNoCycles(taskSpecs);
    }

    // @find: create goal and tasks, start goal, POST /api/goals, run async
    /**
     * Turns a request into a goal and its tasks, and optionally starts it without waiting.
     *
     * <p>Every caller that starts work - the goal endpoint, chat, schedules - passes {@code
     * runAsync} true and answers before any of the work is done; the person follows the goal or its
     * run, which update as the agent works. When it is true the workspace's next tasks are handed
     * to {@link RunExecutor} once this commits, rather than left for the next sweep to notice.
     */
    @Transactional
    public Goal createGoal(UUID orgId, NewGoal spec, boolean runAsync) {
        validate(spec);
        List<NewTask> taskSpecs = spec.tasks();
        String source = spec.source() == null ? "manual" : spec.source();

        Goal goal = new Goal();
        goal.setId(UuidV7.generate());
        goal.setOrgId(orgId);
        goal.setTitle(spec.title());
        goal.setDescription(spec.description() == null ? "" : spec.description());
        goal.setStatus("planning");
        goal.setSource(source);
        goal.setConversationId(spec.conversationId());
        goal.setScheduleId(spec.scheduleId());
        goal.setRequestedBy(spec.requestedBy() != null ? spec.requestedBy() : requestedByFromContext());
        goals.save(goal);

        List<Task> created = new ArrayList<>();
        for (int index = 0; index < taskSpecs.size(); index++) {
            NewTask request = taskSpecs.get(index);
            Task task = new Task();
            task.setId(UuidV7.generate());
            task.setOrgId(orgId);
            task.setGoalId(goal.getId());
            task.setAgentId(request.agentId());
            task.setTitle(request.title());
            task.setInstruction(request.instruction());
            task.setPosition(index);
            task.setStatus("pending");
            created.add(task);
        }
        // A second pass, once every task has an id: a dependency can point anywhere in the
        // request, including a task defined after it.
        for (int index = 0; index < taskSpecs.size(); index++) {
            List<Integer> dependsOnPositions = taskSpecs.get(index).dependsOnPositions();
            if (dependsOnPositions != null && !dependsOnPositions.isEmpty()) {
                created.get(index)
                        .setDependsOn(dependsOnPositions.stream()
                                .map(position -> created.get(position).getId())
                                .toList());
            }
        }
        tasks.saveAll(created);

        goal.setStatus("running");
        goals.save(goal);
        log.info("Goal {} created with {} task(s)", goal.getId(), created.size());

        if (runAsync) {
            submitNextTasksAfterCommit(orgId);
        }
        return goal;
    }

    /**
     * Hands this workspace's next tasks to {@link RunExecutor} only once the goal and its tasks
     * are actually visible to another connection.
     *
     * <p>{@code createGoal} is itself {@code @Transactional} and, whenever a caller such as the
     * chat coordinator or the schedule sweep already has a transaction open, joins that one rather
     * than opening its own - so the physical commit does not happen until well after this method
     * returns. Submitting the executor's work here directly, before that commit, would have it
     * read for a task on a different connection that cannot yet see the row this same request just
     * wrote: the goal would sit untouched until the next periodic sweep noticed it minutes later,
     * silently defeating the entire point of running it asynchronously. Registering the submission
     * to run after the transaction commits - falling back to running it immediately when, for a
     * caller or a test, no transaction is open at all - is what actually gets it seen.
     */
    private void submitNextTasksAfterCommit(UUID orgId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            runExecutor.submitNextTasks(orgId);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runExecutor.submitNextTasks(orgId);
                    }
                });
    }

    /** The acting person, when there is a request in flight and its id is an actual identity. */
    private static UUID requestedByFromContext() {
        return RequestContext.actor().map(actor -> parseUuidOrNull(actor.id())).orElse(null);
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            // The platform actor's id is the literal string "system", not a UUID; a goal it
            // starts (a schedule sweep, for one) simply has no requester.
            return null;
        }
    }

    /** An agent revisited more than twice in one chain is a loop rather than a hand-off. */
    private void validateAgentRepeats(List<NewTask> requests) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (NewTask request : requests) {
            if (request.agentId() == null) {
                continue;
            }
            int seen = counts.merge(request.agentId(), 1, Integer::sum);
            if (seen > MAX_AGENT_REPEATS) {
                throw ApiException.validation("tasks", "an agent may appear at most twice in one goal");
            }
        }
    }

    /**
     * Refuses a dependency graph that cannot complete.
     *
     * <p>A cycle is easy to introduce when a plan is generated rather than typed, and the symptom
     * - a goal that stays "running" with no task ever becoming ready - is very hard to read from
     * the interface. Catching it at creation turns it into a clear validation error.
     */
    private void validateNoCycles(List<NewTask> requests) {
        Set<Integer> visiting = new HashSet<>();
        Set<Integer> done = new HashSet<>();
        for (int index = 0; index < requests.size(); index++) {
            walk(index, requests, visiting, done);
        }
    }

    private void walk(int index, List<NewTask> requests, Set<Integer> visiting, Set<Integer> done) {
        if (done.contains(index)) {
            return;
        }
        if (!visiting.add(index)) {
            throw ApiException.validation("tasks", "the task dependencies form a cycle");
        }
        List<Integer> dependencies = requests.get(index).dependsOnPositions();
        if (dependencies != null) {
            for (Integer dependency : dependencies) {
                if (dependency == null || dependency < 0 || dependency >= requests.size()) {
                    throw ApiException.validation("tasks", "a task depends on a position that does not exist");
                }
                if (dependency == index) {
                    throw ApiException.validation("tasks", "a task cannot depend on itself");
                }
                walk(dependency, requests, visiting, done);
            }
        }
        visiting.remove(index);
        done.add(index);
    }

    // @find: run next task, process ready task, synchronous task run
    /**
     * Runs the next task that is ready, if there is one, on the calling thread.
     *
     * <p>One task per call rather than a loop, so a caller - a test, a manual nudge - decides the
     * pace. Draining the whole graph inside one transaction would hold a database connection for
     * the length of every model call in it. {@link RunExecutor} uses the two halves, {@link
     * #claimNextTask} and {@link #startClaimed}, so that each claimed task runs on its own thread.
     *
     * <p>What the run's outcome means for the task and its goal is decided by {@link TaskProgress},
     * which the runner reports to as the run finishes or parks.
     *
     * @return whether a task was claimed
     */
    public boolean runNextTask(UUID orgId) {
        Optional<TaskStart> start = claimNextTask(orgId);
        if (start.isEmpty()) {
            return false;
        }
        startClaimed(orgId, start.get());
        return true;
    }

    /**
     * A task that has been claimed and marked running, with the completed predecessors it hands
     * off from.
     */
    public record TaskStart(Task task, List<Task> predecessors) {}

    // @find: claim next ready task, mark running, task queue, concurrency cap
    /**
     * Claims the workspace's next ready task and marks it running, or finds none - nothing ready,
     * or the workspace already at its cap of running tasks.
     *
     * <p>The claim is a short transaction of its own; the run happens after it commits. Holding
     * one transaction across the whole agent run would keep a pooled connection and the task's
     * row lock for minutes, and several chat messages or schedules firing together would exhaust
     * the pool.
     */
    public Optional<TaskStart> claimNextTask(UUID orgId) {
        Optional<TaskStart> start =
                claimTransaction == null ? claimNext(orgId) : claimTransaction.execute(status -> claimNext(orgId));
        return start == null ? Optional.empty() : start;
    }

    // @find: start run for claimed task, run as requester
    /**
     * Starts the run for a claimed task, as the person who asked for its goal.
     *
     * <p>Whoever claimed it - the executor after a goal was created or a task settled, a test, a
     * sweep - the run is attributed to the goal's own requester: its audit entries name them, and
     * the tokens it fetches carry their identity. A goal nobody is on record for runs as the
     * platform. A start refused before anything was written counts as a failed attempt.
     */
    public void startClaimed(UUID orgId, TaskStart claimed) {
        Task task = claimed.task();
        Goal goal = goals.findById(task.getGoalId()).orElse(null);
        Actor actor = goal == null ? Actor.SYSTEM : requesterActor(goal);
        // The run does not exist yet, so its id cannot be here; the task, goal, agent and workspace
        // are, and they tie every line the run writes - the runner's, the router's, a tool's - to
        // this task until it parks or ends.
        try (RunLogContext ignored = RunLogContext.run(orgId, task.getGoalId(), null, task.getAgentId())
                .with(RunLogContext.TASK_ID, task.getId())) {
            RequestContext.as(actor, () -> {
                try {
                    if (claimed.predecessors().isEmpty()) {
                        runner.start(orgId, task.getAgentId(), task.getId(), task.getInstruction(), "task");
                    } else {
                        runner.start(
                                orgId,
                                task.getAgentId(),
                                task.getId(),
                                withHandoffPreamble(
                                        task.getInstruction(),
                                        goal == null ? null : goal.getDescription(),
                                        claimed.predecessors(),
                                        passageTitles(firstStepInstruction(task, claimed.predecessors())),
                                        this::agentNameOf),
                                "task",
                                handoffDetails(task, claimed.predecessors()));
                    }
                } catch (ApiException e) {
                    if (claimTransaction == null) {
                        progress.onStartFailed(task, e.getMessage());
                    } else {
                        claimTransaction.executeWithoutResult(status -> progress.onStartFailed(task, e.getMessage()));
                    }
                }
                return null;
            });
        }
    }

    // @find: who a goal runs as, requester or platform actor
    /**
     * The person a goal's work runs as: its requester, or the platform when nobody is on record.
     * The one rule for every path that runs or resumes a goal's task, so they cannot disagree.
     */
    public static Actor requesterActor(Goal goal) {
        UUID requestedBy = goal.getRequestedBy();
        if (requestedBy == null) {
            return Actor.SYSTEM;
        }
        String orgId = goal.getOrgId() == null ? null : goal.getOrgId().toString();
        return Actor.user(requestedBy.toString(), orgId, null, Set.of(), 0L);
    }

    private org.springframework.transaction.support.TransactionTemplate claimTransaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.claimTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Claims the next ready task and marks it running, skipping blocked ones on the way. */
    private Optional<TaskStart> claimNext(UUID orgId) {
        int cap = maxConcurrentRuns(orgId);
        long running = tasks.countByOrgIdAndStatus(orgId, "running");
        if (running >= cap) {
            // Left pending, not refused: the next task to settle in this workspace frees a place
            // and dispatches again, and the goal sweep checks every few seconds besides.
            log.debug("Workspace {} has {} task(s) running, its limit of {}; the rest wait", orgId, running, cap);
            return Optional.empty();
        }
        List<Task> candidates = tasks.findClaimable(orgId, PageRequest.of(0, CLAIM_WINDOW));

        // One query for every candidate's siblings, keyed by goal, rather than one query per
        // candidate: the claim window is 100 tasks wide, and candidates routinely share a goal
        // (a chat chain's later tasks are all still pending together), so fetching per-candidate
        // would otherwise repeat the same goal's task list many times over in the same call.
        Set<UUID> goalIds = candidates.stream()
                .map(Task::getGoalId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<Task>> siblingsByGoal = goalIds.isEmpty()
                ? Map.of()
                : tasks.findByGoalIdInOrderByPositionAsc(goalIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                Task::getGoalId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        for (Task candidate : candidates) {
            List<Task> siblings = siblingsByGoal.getOrDefault(candidate.getGoalId(), List.of());
            Readiness readiness = readinessOf(candidate, siblings);
            if (readiness == Readiness.WAITING) {
                continue;
            }
            // The request that created a goal and the goal sweep both advance it. The claim
            // makes sure only one of them runs a given task.
            Optional<Task> claimed = tasks.claim(candidate.getId());
            if (claimed.isEmpty()) {
                continue;
            }
            Task task = claimed.get();

            if (readiness == Readiness.BLOCKED) {
                // A predecessor this task actually depends on did not complete. There is nothing
                // left for it to do with what it was given, so it is skipped rather than left
                // pending for a dependency that will never finish.
                task.setStatus("skipped");
                task.setFailureReason(TaskProgress.EARLIER_TASK_UNFINISHED);
                task.setCompletedAt(Instant.now());
                tasks.save(task);
                progress.closeGoalIfFinished(task.getGoalId());
                continue;
            }

            if (task.getAgentId() == null) {
                task.setStatus("skipped");
                task.setFailureReason("No agent was assigned to this task.");
                task.setCompletedAt(Instant.now());
                tasks.save(task);
                progress.closeGoalIfFinished(task.getGoalId());
                continue;
            }

            task.setStatus("running");
            task.setAttempt(task.getAttempt() + 1);
            task.setStartedAt(Instant.now());
            tasks.save(task);

            return Optional.of(new TaskStart(task, completedPredecessors(task, siblings)));
        }
        return Optional.empty();
    }

    /** The workspace's cap on running tasks; the default when the setting cannot be read. */
    private int maxConcurrentRuns(UUID orgId) {
        int fallback = (Integer) MAX_CONCURRENT_RUNS.defaultValue();
        if (runtimeConfig == null) {
            return fallback;
        }
        try {
            return Math.max(1, runtimeConfig.getInt(MAX_CONCURRENT_RUNS, orgId.toString()));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read {}; using {}: {}", MAX_CONCURRENT_RUNS.name(), fallback, unreadable.getMessage());
            return fallback;
        }
    }

    private enum Readiness {
        READY,
        WAITING,
        BLOCKED
    }

    /**
     * Whether a task can start yet, is still waiting on something unfinished, or is blocked for
     * good because a dependency it actually needs did not complete.
     *
     * <p>A task with an explicit {@code dependsOn} waits for exactly those tasks; one without it
     * falls back to the old rule and waits for every earlier position in the goal, so a plan
     * built without dependencies still runs strictly in order.
     */
    private Readiness readinessOf(Task task, List<Task> siblings) {
        List<Task> predecessors = predecessorsOf(task, siblings);
        if (predecessors.isEmpty()) {
            return Readiness.READY;
        }
        if (!predecessors.stream().allMatch(Task::isTerminal)) {
            return Readiness.WAITING;
        }
        boolean everyPredecessorCompleted = predecessors.stream().allMatch(p -> "completed".equals(p.getStatus()));
        return everyPredecessorCompleted ? Readiness.READY : Readiness.BLOCKED;
    }

    private static List<Task> predecessorsOf(Task task, List<Task> siblings) {
        List<UUID> dependsOn = task.getDependsOn();
        if (!dependsOn.isEmpty()) {
            return siblings.stream()
                    .filter(sibling -> dependsOn.contains(sibling.getId()))
                    .toList();
        }
        return siblings.stream()
                .filter(sibling -> sibling.getPosition() < task.getPosition())
                .toList();
    }

    /**
     * The completed predecessors this task hands off from, oldest first and capped at {@value
     * #MAX_HANDOFFS} - the most recent ones, when there are more than that, since those are what
     * the task actually needs to build on.
     */
    private static List<Task> completedPredecessors(Task task, List<Task> siblings) {
        List<Task> completed = predecessorsOf(task, siblings).stream()
                .filter(sibling -> "completed".equals(sibling.getStatus()))
                .sorted(Comparator.comparingInt(Task::getPosition))
                .toList();
        if (completed.size() <= MAX_HANDOFFS) {
            return completed;
        }
        return completed.subList(completed.size() - MAX_HANDOFFS, completed.size());
    }

    /**
     * The instruction for a step that follows others: the person's request, what the earlier agents
     * already produced, which document passages the first step was given, and then this step's own
     * part - so it builds on that work rather than repeating it, and still knows what was asked.
     *
     * <p>A step the coordinator wrote as {@link #REQUEST_HEADING} and {@link #PART_HEADING} keeps
     * those two halves, with the hand-off placed between them, so neither heading appears twice. Any
     * other step is given the goal's own description as the original request, unless that is
     * exactly its instruction already.
     *
     * @param goalDescription what the person asked for the goal as a whole; may be null
     * @param passageTitles the numbered passage titles the first step was given, if any
     * @param agentName an agent's display name by id, for naming who did each piece of work
     */
    static String withHandoffPreamble(
            String instruction,
            String goalDescription,
            List<Task> predecessors,
            List<String> passageTitles,
            Function<UUID, String> agentName) {
        String body = instruction == null ? "" : instruction;
        String request = null;
        String part = body;
        boolean ownPart = false;
        int split = body.lastIndexOf(PART_SEPARATOR);
        if (body.startsWith(REQUEST_HEADING) && split >= REQUEST_HEADING.length()) {
            request = body.substring(0, split);
            part = body.substring(split + PART_SEPARATOR.length());
            ownPart = true;
        } else if (goalDescription != null
                && !goalDescription.isBlank()
                && !goalDescription.strip().equals(body.strip())) {
            request = "Original request: " + goalDescription;
        }

        StringBuilder preamble = new StringBuilder();
        if (request != null) {
            preamble.append(request).append("\n\n");
        }
        preamble.append("Work already done for this request:\n");
        List<String> results = handoffResults(predecessors);
        for (int index = 0; index < predecessors.size(); index++) {
            preamble.append("- ")
                    .append(agentName.apply(predecessors.get(index).getAgentId()))
                    .append(": ")
                    .append(results.get(index))
                    .append("\n");
        }
        if (passageTitles != null && !passageTitles.isEmpty()) {
            preamble.append("\nThe first step was given these passages from the workspace's documents, and a "
                            + "number such as [1] in the work above refers to them: ")
                    .append(String.join("; ", passageTitles))
                    .append(".\n");
        }
        // One "Your part:" heading: on the same line as a part the coordinator wrote, or above an
        // instruction that is the whole of what this step was asked.
        preamble.append('\n').append(ownPart ? PART_HEADING : PART_HEADING.strip() + "\n").append(part);
        return preamble.toString();
    }

    /**
     * Each predecessor's result as a later step is given it: up to {@value #MAX_HANDOFF_RESULT_CHARS}
     * characters each, within {@value #HANDOFF_BUDGET_CHARS} for all of them together. A short
     * result leaves its unused share to the others, and a result that is cut says so.
     */
    static List<String> handoffResults(List<Task> predecessors) {
        int count = predecessors.size();
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingInt(index -> lengthOf(predecessors.get(index).getResult())));
        int[] allowance = new int[count];
        int remaining = HANDOFF_BUDGET_CHARS;
        for (int rank = 0; rank < count; rank++) {
            int index = order.get(rank);
            int share = Math.min(MAX_HANDOFF_RESULT_CHARS, remaining / (count - rank));
            allowance[index] = Math.min(lengthOf(predecessors.get(index).getResult()), share);
            remaining -= allowance[index];
        }
        List<String> results = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String result = predecessors.get(index).getResult();
            results.add(result == null ? "" : cutWithMarker(result, Math.max(allowance[index], 1)));
        }
        return results;
    }

    private static int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    // @find: truncate hand-off text, omitted marker, predecessor result cap
    /**
     * The start of a text, kept exactly as written - line breaks, lists and tables included - and
     * cut, when it is longer than {@code max} characters, at the last line break or space in the
     * final stretch, with a line saying how much was left out. Never cut silently: an agent given a
     * fragment presented as the whole would act on it as if it were complete.
     */
    public static String cutWithMarker(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int floor = Math.max(1, max - Math.min(200, max / 5));
        int cut = max;
        for (int index = max; index >= floor; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                cut = index;
                break;
            }
        }
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--; // never leave half of a character at the end of what the agent reads
        }
        String head = text.substring(0, cut).stripTrailing();
        int omitted = text.length() - head.length();
        return head + "\n" + omittedMarker(omitted);
    }

    /** The line that stands in for text left out, naming how much. */
    public static String omittedMarker(int omittedCharacters) {
        return String.format(Locale.ROOT, "[... %d more characters not shown]", omittedCharacters);
    }

    /** How a chain step's instruction reads: the person's whole request, then this step's part of it. */
    public static String chainStepInstruction(String request, String part) {
        return REQUEST_HEADING + (request == null ? "" : request) + PART_SEPARATOR + (part == null ? "" : part);
    }

    /** The instruction of the goal's first step, from the predecessors when it is among them. */
    private String firstStepInstruction(Task task, List<Task> predecessors) {
        for (Task predecessor : predecessors) {
            if (predecessor.getPosition() == 0) {
                return predecessor.getInstruction();
            }
        }
        return tasks.findByGoalIdOrderByPosition(task.getGoalId()).stream()
                .filter(sibling -> sibling.getPosition() == 0)
                .map(Task::getInstruction)
                .findFirst()
                .orElse(null);
    }

    /**
     * The numbered passage titles at the start of an instruction, as the coordinator writes them
     * after {@link #PASSAGES_HEADING}: one {@code [n] Title, page p} line per passage, then that
     * passage's text on one line. Empty when the instruction does not start with passages.
     */
    public static List<String> passageTitles(String instruction) {
        if (instruction == null || !instruction.startsWith(PASSAGES_HEADING)) {
            return List.of();
        }
        String[] lines = instruction.split("\n", -1);
        List<String> titles = new ArrayList<>();
        int expected = 1;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                continue;
            }
            Matcher title = PASSAGE_TITLE.matcher(line);
            if (!title.matches() || Integer.parseInt(title.group(1)) != expected) {
                break;
            }
            titles.add(line);
            expected++;
            index++; // the passage's own text, always on the one line after its title
        }
        return titles;
    }

    /** One {@code handoff} step's detail per predecessor, in the shape the run trace records. */
    private List<Map<String, Object>> handoffDetails(Task task, List<Task> predecessors) {
        List<Map<String, Object>> handoffs = new ArrayList<>();
        List<String> results = handoffResults(predecessors);
        for (int index = 0; index < predecessors.size(); index++) {
            Task predecessor = predecessors.get(index);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromTaskId", predecessor.getId().toString());
            detail.put(
                    "fromAgentId",
                    predecessor.getAgentId() == null
                            ? null
                            : predecessor.getAgentId().toString());
            detail.put("fromAgentName", agentNameOf(predecessor.getAgentId()));
            detail.put("toAgentId", task.getAgentId().toString());
            detail.put("toAgentName", agentNameOf(task.getAgentId()));
            detail.put("summary", results.get(index));
            handoffs.add(detail);
        }
        return handoffs;
    }

    private String agentNameOf(UUID agentId) {
        if (agentId == null) {
            return "";
        }
        return agents.findById(agentId).map(Agent::getName).orElse("");
    }

    /** Workspaces with at least one task waiting to start, for the goal sweep. */
    @Transactional(readOnly = true)
    public List<UUID> workspacesWithWaitingTasks() {
        return tasks.findOrgIdsWithClaimableTasks();
    }

    /**
     * Repairs tasks still shown as in progress after their run has ended.
     *
     * <p>Each is given the outcome its latest run actually had, exactly as if that run had just
     * reported it. This is what cleans up a task left "waiting for approval" by a run that was
     * approved, rejected, expired or cancelled before those paths reported to the task.
     *
     * @return how many tasks were repaired
     */
    @Transactional
    public int reconcileStrandedTasks(int limit) {
        int repaired = 0;
        for (Task task : tasks.findStranded(PageRequest.of(0, limit))) {
            Run run = runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId()).orElse(null);
            // Active includes a run parked for an approval or an answer: that task is waiting,
            // not stranded.
            if (run == null || run.isActive()) {
                continue;
            }
            // A run from an earlier attempt says nothing about this one. A retried or re-claimed
            // task is running from its claim before its new run exists, and settling it from the
            // old run would put it back to pending while the new run starts.
            if (run.getStartedAt() != null
                    && task.getStartedAt() != null
                    && run.getStartedAt().isBefore(task.getStartedAt())) {
                continue;
            }
            String was = task.getStatus();
            progress.onRunFinished(run, run.getStatus(), answerOf(run), run.getFailureReason());
            log.info(
                    "Task {} was {} although its run {} had ended as {}; now {}",
                    task.getId(),
                    was,
                    run.getId(),
                    run.getStatus(),
                    task.getStatus());
            repaired++;
        }
        return repaired;
    }

    /**
     * What a run that ended on its own leaves on its task: its answer when it completed, and, when
     * it failed at its step or output limit, whatever it had written by then - which the task
     * shows as an incomplete answer, exactly as it would had the runner reported the failure
     * itself. Any other failure leaves no text.
     */
    private String answerOf(Run run) {
        if ("completed".equals(run.getStatus())) {
            return lastReply(steps.findByRunIdOrderByPosition(run.getId()));
        }
        if ("failed".equals(run.getStatus())) {
            List<RunStep> trace = steps.findByRunIdOrderByPosition(run.getId());
            return endedAtALimit(trace) ? lastReply(trace) : null;
        }
        return null;
    }

    /** Whether the trace's last error is the run reaching its step limit or being cut off by its output limit. */
    private static boolean endedAtALimit(List<RunStep> trace) {
        for (int index = trace.size() - 1; index >= 0; index--) {
            RunStep step = trace.get(index);
            if ("error".equals(step.getKind())) {
                Object code = step.getDetail() == null ? null : step.getDetail().get("code");
                return TaskProgress.STEP_LIMIT.equals(code) || TaskProgress.OUTPUT_LIMIT.equals(code);
            }
        }
        return false;
    }

    /** The last thing the model said, which is the answer a completed run finished with. */
    private static String lastReply(List<RunStep> trace) {
        for (int index = trace.size() - 1; index >= 0; index--) {
            RunStep step = trace.get(index);
            if ("model_call".equals(step.getKind())
                    && step.getDetail().get("content") instanceof String text
                    && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /**
     * Cancels a goal and everything still open under it, for the default reason.
     *
     * <p>Kept with its signature so every existing caller stays as it is; see {@link
     * #cancel(UUID, UUID, String)}.
     */
    @Transactional
    public void cancel(UUID orgId, UUID goalId) {
        cancel(orgId, goalId, PERSON_CANCELLED);
    }

    /**
     * Cancels a goal and everything still open under it.
     *
     * <p>A task's run is stopped too, and any approval or question it was waiting on is withdrawn.
     * Otherwise the run would stay parked, the approval or question would stay in the queue, and
     * deciding or answering it would resume work for a goal the person had cancelled.
     *
     * <p>The runs are locked first, before the tasks are read, following the one lock order every
     * writer uses (run, then question or approval, then task, then goal). A resume claiming a run
     * at the same moment waits behind the lock and then finds it cancelled; a run finishing at the
     * same moment commits first, and its task is then read as already settled and left alone.
     * The withdrawals are conditional bulk updates, so an answer or a decision committing at the
     * same moment wins or loses cleanly and never fails the cancel.
     *
     * @param reason shown on every task this cancels, written for a person to read
     */
    @Transactional
    public CancelCounts cancel(UUID orgId, UUID goalId, String reason) {
        Goal goal = goals.findByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId));
        if (goal.isFinished()) {
            throw new ApiException(ErrorCode.CONFLICT, "That goal has already finished.");
        }
        List<Run> active = runs.lockActiveByGoal(goalId);
        List<Task> all = tasks.findByGoalIdOrderByPosition(goalId);

        int approvalsWithdrawn = 0;
        int questionsWithdrawn = 0;
        for (Run run : active) {
            run.finish("cancelled", GOAL_CANCELLED);
            runs.save(run);
            approvalsWithdrawn += approvals.withdrawForRun(run.getId());
            questionsWithdrawn += questions.cancelForRun(run.getId(), GOAL_CANCELLED);
        }

        Instant now = Instant.now();
        int tasksCancelled = 0;
        for (Task task : all) {
            if (task.isTerminal()) {
                continue;
            }
            // Marked directly rather than through TaskProgress, and before the goal is closed,
            // so the goal ends as cancelled rather than as the sum of its tasks.
            task.setStatus("cancelled");
            task.setFailureReason(reason);
            task.setCompletedAt(now);
            tasks.save(task);
            tasksCancelled++;
        }
        goal.setStatus("cancelled");
        goal.setCompletedAt(now);
        goals.save(goal);
        log.info("Goal {} cancelled: {} task(s), {} run(s)", goalId, tasksCancelled, active.size());

        CancelCounts counts =
                new CancelCounts(tasksCancelled, active.size(), approvalsWithdrawn, questionsWithdrawn);
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = stopDetail(reason, counts);
        LifecycleAnnouncer.afterCommit(() -> announcer.goalCancelled(goalId, reason));
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "goal.cancel", "goal", goalId.toString(), "succeeded", detail));
        return counts;
    }

    /** What a stop's audit entry carries: why, and how much it withdrew. */
    private static Map<String, Object> stopDetail(String reason, CancelCounts counts) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (reason != null) {
            detail.put("reason", reason);
        }
        detail.put("tasks", counts.tasks());
        detail.put("runs", counts.runs());
        detail.put("approvals", counts.approvals());
        detail.put("questions", counts.questions());
        return detail;
    }

    /**
     * Cancels a goal when it is still in progress, in a transaction of its own, and does nothing
     * when it is missing or has already finished. For callers that stop many goals in turn - Stop
     * everything, deleting a conversation - so one goal finishing meanwhile is not an error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CancelCounts> cancelIfActive(UUID orgId, UUID goalId, String reason) {
        Goal goal = goals.findByIdAndOrgId(goalId, orgId).orElse(null);
        if (goal == null || goal.isFinished()) {
            return Optional.empty();
        }
        return Optional.of(cancel(orgId, goalId, reason));
    }

    /**
     * Stops one run: the single way to do it, for the run page and for Stop everything.
     *
     * <p>The run is locked first, so a resume claiming it at the same moment waits and then loses.
     * Its approvals and question are withdrawn with conditional bulk updates, and a task's run
     * settles its task as cancelled.
     */
    @Transactional
    public CancelCounts stopRun(UUID orgId, UUID runId, String reason) {
        Run run = runs.lockByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        if (!run.isActive()) {
            throw new ApiException(ErrorCode.CONFLICT, "That run has already finished.");
        }
        run.finish("cancelled", reason);
        runs.save(run);
        int approvalsWithdrawn = approvals.withdrawForRun(runId);
        int questionsWithdrawn = questions.cancelForRun(runId, reason);
        int tasksCancelled = 0;
        if (run.getTaskId() != null) {
            progress.onRunFinished(run, "cancelled", null, TASK_RUN_STOPPED);
            tasksCancelled = 1;
        }
        log.info("Run {} stopped: {}", runId, reason);
        CancelCounts counts = new CancelCounts(tasksCancelled, 1, approvalsWithdrawn, questionsWithdrawn);
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = stopDetail(reason, counts);
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "run.stop", "run", runId.toString(), "succeeded", detail));
        return counts;
    }

    /** Stops a run when it is still active, in a transaction of its own; nothing when it is not. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CancelCounts> stopRunIfActive(UUID orgId, UUID runId, String reason) {
        Run run = runs.lockByIdAndOrgId(runId, orgId).orElse(null);
        if (run == null || !run.isActive()) {
            return Optional.empty();
        }
        // Re-reads the row this transaction already holds locked, so there is no second wait.
        return Optional.of(stopRun(orgId, runId, reason));
    }

    /**
     * Refuses a stop the actor may not make, and a stop of a goal that has already finished.
     *
     * <p>Stopping is open to the person who asked for the work - who can always change their mind
     * about it - and to anyone who can cancel work in general. It is the same rule retrying
     * follows, held in one place so the goal endpoint, chat and the board cannot drift apart.
     *
     * @throws ApiException {@code PERMISSION_DENIED} naming {@code task:cancel} when the actor is
     *     neither; {@code CONFLICT} when the goal has already finished
     */
    public void requireCanStop(Goal goal, Actor actor) {
        requireRequesterOrCanceller(
                goal, actor, "Only the person who asked for this work, or someone who can cancel work, can stop it.");
        if (goal.isFinished()) {
            throw new ApiException(ErrorCode.CONFLICT, "That goal has already finished.");
        }
    }

    private static void requireRequesterOrCanceller(Goal goal, Actor actor, String refusal) {
        boolean requester = actor != null
                && goal.getRequestedBy() != null
                && goal.getRequestedBy().toString().equals(actor.humanId());
        if (!requester && (actor == null || !actor.hasPermission(Permission.Codes.TASK_CANCEL))) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, refusal)
                    .with("requiredPermission", Permission.Codes.TASK_CANCEL);
        }
    }

    /**
     * Tries a failed or stopped goal again, from its first step that did not finish.
     *
     * <p>Completed steps keep their results, and earlier runs stay in the history. Restarting
     * someone else's work repeats its side effects, so it takes the same authority as stopping
     * it: the person who asked for it, or someone who can cancel work.
     *
     * <p>The goal is locked here, the one exception to locking runs first: a goal that failed or
     * was stopped has no active run, so nothing can hold a run or task lock and wait for this one.
     * A second retry at the same moment waits, then finds the goal running and gets a conflict.
     */
    @Transactional
    public RetryResult retry(UUID orgId, UUID goalId, Actor actor) {
        Goal goal = goals.lockByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId));
        requireRequesterOrCanceller(
                goal,
                actor,
                "Only the person who asked for this work, or someone who can cancel work, can try it again.");
        if (!"failed".equals(goal.getStatus()) && !"cancelled".equals(goal.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only a goal that failed or was stopped can be tried again.");
        }
        List<Task> all = tasks.findByGoalIdOrderByPosition(goalId);
        Task from = all.stream()
                .filter(task -> RETRY_FROM.contains(task.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONFLICT, "Every step of this goal completed, so there is nothing to try again."));
        for (Task task : all) {
            if (task.getPosition() >= from.getPosition() && !"completed".equals(task.getStatus())) {
                task.setStatus("pending");
                task.setAttempt(0);
                task.setResult(null);
                task.setFailureReason(null);
                task.setStartedAt(null);
                task.setCompletedAt(null);
                tasks.save(task);
            }
        }
        goal.setStatus("running");
        goal.setCompletedAt(null);
        goals.save(goal);
        log.info("Goal {} tried again from task {}", goalId, from.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromTaskId", from.getId().toString());
        detail.put("fromPosition", from.getPosition());
        UUID fromTaskId = from.getId();
        // Three separate actions, so one failing after the commit never stops the others.
        LifecycleAnnouncer.afterCommit(() -> runExecutor.submitNextTasks(orgId));
        LifecycleAnnouncer.afterCommit(() -> announcer.goalRetried(goalId, fromTaskId));
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "goal.retry", "goal", goalId.toString(), "succeeded", detail));
        return new RetryResult(goal, from);
    }
}
