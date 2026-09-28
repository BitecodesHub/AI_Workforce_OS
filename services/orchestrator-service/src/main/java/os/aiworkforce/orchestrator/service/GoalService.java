package os.aiworkforce.orchestrator.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
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
    /** How far the claim window reaches past tasks whose agent is paused, so they cannot starve it. */
    private static final int CLAIM_WINDOW = 100;
    static final String GOAL_CANCELLED = "The goal this run belonged to was cancelled.";

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final RunSteps steps;
    private final AgentRunner runner;
    private final ApprovalService approvals;
    private final TaskProgress progress;
    private final Agents agents;
    private final RunExecutor runExecutor;

    public GoalService(
            Goals goals,
            Tasks tasks,
            Runs runs,
            RunSteps steps,
            AgentRunner runner,
            ApprovalService approvals,
            TaskProgress progress,
            Agents agents,
            @Lazy RunExecutor runExecutor) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.steps = steps;
        this.runner = runner;
        this.approvals = approvals;
        this.progress = progress;
        this.agents = agents;
        this.runExecutor = runExecutor;
    }

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

    /** The old, single-caller shape, kept so existing callers are unaffected. */
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

    /**
     * Turns a request into a goal and its tasks, and optionally starts it without waiting.
     *
     * <p>{@code runAsync} exists because the callers need different things from the same method:
     * the goal endpoint runs a few tasks synchronously itself, so its own request is the answer a
     * person is waiting on, while chat and schedules must answer before any of the work is done.
     * When {@code runAsync} is true this goal's next tasks are handed to {@link RunExecutor}
     * before this method returns, rather than left for the next sweep to notice.
     */
    @Transactional
    public Goal createGoal(UUID orgId, NewGoal spec, boolean runAsync) {
        List<NewTask> taskSpecs = spec.tasks();
        if (taskSpecs == null || taskSpecs.isEmpty()) {
            throw ApiException.validation("tasks", "a goal needs at least one task");
        }
        if (taskSpecs.size() > MAX_TASKS_PER_GOAL) {
            // An unbounded graph is an unbounded spend, and a person cannot read a plan of two
            // hundred steps well enough to approve it anyway.
            throw ApiException.validation(
                    "tasks", "a goal may hold at most " + MAX_TASKS_PER_GOAL + " tasks");
        }
        String source = spec.source() == null ? "manual" : spec.source();
        if ("chat".equals(source) && taskSpecs.size() > MAX_CHAT_TASKS) {
            // A chat reply chains agents; a chain longer than this is not one a person composed
            // by mentioning or answering - it is a routing mistake, and refusing it here is safer
            // than running five minutes of work nobody asked for.
            throw ApiException.validation(
                    "tasks", "a chat message may start at most " + MAX_CHAT_TASKS + " tasks");
        }
        validateAgentRepeats(taskSpecs);
        validateNoCycles(taskSpecs);

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
                created.get(index).setDependsOn(
                        dependsOnPositions.stream().map(position -> created.get(position).getId()).toList());
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

    /**
     * Runs the next task that is ready, if there is one.
     *
     * <p>One task per call rather than a loop, so a caller - the scheduler, a test, a manual
     * nudge - decides the pace. Draining the whole graph inside one transaction would hold a
     * database connection for the length of every model call in it.
     *
     * <p>What the run's outcome means for the task and its goal is decided by {@link TaskProgress},
     * which the runner reports to as the run finishes or parks.
     */
    public boolean runNextTask(UUID orgId) {
        // The claim is a short transaction of its own; the run happens after it commits. Holding
        // one transaction across the whole agent run would keep a pooled connection and the
        // task's row lock for minutes, and several chat messages or schedules firing together
        // would exhaust the pool.
        Optional<TaskStart> start = claimTransaction == null
                ? claimNext(orgId)
                : claimTransaction.execute(status -> claimNext(orgId));
        if (start == null || start.isEmpty()) {
            return false;
        }
        TaskStart claimed = start.get();
        Task task = claimed.task();
        try {
            if (claimed.predecessors().isEmpty()) {
                runner.start(orgId, task.getAgentId(), task.getId(), task.getInstruction(), "task");
            } else {
                runner.start(orgId, task.getAgentId(), task.getId(),
                        withHandoffPreamble(task.getInstruction(), claimed.predecessors()),
                        "task", handoffDetails(task, claimed.predecessors()));
            }
        } catch (ApiException e) {
            if (claimTransaction == null) {
                progress.onStartFailed(task, e.getMessage());
            } else {
                claimTransaction.executeWithoutResult(status -> progress.onStartFailed(task, e.getMessage()));
            }
        }
        return true;
    }

    private record TaskStart(Task task, List<Task> predecessors) {}

    private org.springframework.transaction.support.TransactionTemplate claimTransaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.claimTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Claims the next ready task and marks it running, skipping blocked ones on the way. */
    private Optional<TaskStart> claimNext(UUID orgId) {
        List<Task> candidates = tasks.findClaimable(orgId, PageRequest.of(0, CLAIM_WINDOW));

        // One query for every candidate's siblings, keyed by goal, rather than one query per
        // candidate: the claim window is 100 tasks wide, and candidates routinely share a goal
        // (a chat chain's later tasks are all still pending together), so fetching per-candidate
        // would otherwise repeat the same goal's task list many times over in the same call.
        Set<UUID> goalIds = candidates.stream().map(Task::getGoalId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<Task>> siblingsByGoal = goalIds.isEmpty()
                ? Map.of()
                : tasks.findByGoalIdInOrderByPositionAsc(goalIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(Task::getGoalId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

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

    private enum Readiness { READY, WAITING, BLOCKED }

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
            return siblings.stream().filter(sibling -> dependsOn.contains(sibling.getId())).toList();
        }
        return siblings.stream().filter(sibling -> sibling.getPosition() < task.getPosition()).toList();
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

    /** Prepends what earlier agents already produced, so this one builds on it rather than repeating it. */
    private String withHandoffPreamble(String instruction, List<Task> predecessors) {
        StringBuilder preamble = new StringBuilder("Work already done for this request:\n");
        for (Task predecessor : predecessors) {
            preamble.append("- ")
                    .append(agentNameOf(predecessor.getAgentId()))
                    .append(": ")
                    .append(truncateResult(predecessor.getResult()))
                    .append("\n");
        }
        preamble.append("\nYour part:\n").append(instruction);
        return preamble.toString();
    }

    /** One {@code handoff} step's detail per predecessor, in the shape the run trace records. */
    private List<Map<String, Object>> handoffDetails(Task task, List<Task> predecessors) {
        List<Map<String, Object>> handoffs = new ArrayList<>();
        for (Task predecessor : predecessors) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromTaskId", predecessor.getId().toString());
            detail.put("fromAgentId", predecessor.getAgentId() == null ? null : predecessor.getAgentId().toString());
            detail.put("fromAgentName", agentNameOf(predecessor.getAgentId()));
            detail.put("toAgentId", task.getAgentId().toString());
            detail.put("toAgentName", agentNameOf(task.getAgentId()));
            detail.put("summary", truncateResult(predecessor.getResult()));
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

    /** Kept short enough that a chain of hand-offs does not itself become the run's whole budget. */
    private static String truncateResult(String result) {
        if (result == null) {
            return "";
        }
        return result.length() <= 1_500 ? result : result.substring(0, 1_500) + "…";
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
            if (run == null || run.isActive()) {
                continue;
            }
            String was = task.getStatus();
            String answer = "completed".equals(run.getStatus()) ? finalAnswer(run) : null;
            progress.onRunFinished(run, run.getStatus(), answer, run.getFailureReason());
            log.info("Task {} was {} although its run {} had ended as {}; now {}",
                    task.getId(), was, run.getId(), run.getStatus(), task.getStatus());
            repaired++;
        }
        return repaired;
    }

    /** The last thing the model said, which is the answer a completed run finished with. */
    private String finalAnswer(Run run) {
        List<RunStep> trace = steps.findByRunIdOrderByPosition(run.getId());
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
     * Cancels a goal and everything still open under it.
     *
     * <p>A task's run is stopped too, and any approval it was waiting on is withdrawn. Otherwise
     * the run would stay "waiting for approval", the approval would stay in the queue, and
     * approving it would resume work for a goal the person had cancelled.
     */
    @Transactional
    public void cancel(UUID orgId, UUID goalId) {
        Goal goal = goals.findByIdAndOrgId(goalId, orgId)
                .orElseThrow(() -> ApiException.notFound("goal", goalId));
        if (goal.isFinished()) {
            throw new ApiException(ErrorCode.CONFLICT, "That goal has already finished.");
        }
        tasks.findByGoalIdOrderByPosition(goalId).stream()
                .filter(task -> !task.isTerminal())
                .forEach(task -> {
                    runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())
                            .filter(Run::isActive)
                            .ifPresent(run -> {
                                run.finish("cancelled", GOAL_CANCELLED);
                                runs.save(run);
                                approvals.cancelForRun(run.getId());
                            });
                    // Marked directly rather than through TaskProgress, and before the goal is
                    // closed, so the goal ends as cancelled rather than as the sum of its tasks.
                    task.setStatus("cancelled");
                    task.setCompletedAt(Instant.now());
                    tasks.save(task);
                });
        goal.setStatus("cancelled");
        goal.setCompletedAt(Instant.now());
        goals.save(goal);
    }
}
