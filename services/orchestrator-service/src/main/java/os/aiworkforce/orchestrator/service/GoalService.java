package os.aiworkforce.orchestrator.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
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
    static final String GOAL_CANCELLED = "The goal this run belonged to was cancelled.";

    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final RunSteps steps;
    private final AgentRunner runner;
    private final ApprovalService approvals;
    private final TaskProgress progress;

    public GoalService(
            Goals goals,
            Tasks tasks,
            Runs runs,
            RunSteps steps,
            AgentRunner runner,
            ApprovalService approvals,
            TaskProgress progress) {
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.steps = steps;
        this.runner = runner;
        this.approvals = approvals;
        this.progress = progress;
    }

    /**
     * @param title what the person asked for
     * @param instruction the full request
     * @param agentId the agent to do it, for a single-step goal
     * @param dependsOn positions this task waits for, by index within the same request
     */
    public record TaskRequest(String title, String instruction, UUID agentId, List<Integer> dependsOn) {}

    @Transactional
    public Goal create(UUID orgId, String title, String description, List<TaskRequest> taskRequests) {
        if (taskRequests == null || taskRequests.isEmpty()) {
            throw ApiException.validation("tasks", "a goal needs at least one task");
        }
        if (taskRequests.size() > MAX_TASKS_PER_GOAL) {
            // An unbounded graph is an unbounded spend, and a person cannot read a plan of two
            // hundred steps well enough to approve it anyway.
            throw ApiException.validation(
                    "tasks", "a goal may hold at most " + MAX_TASKS_PER_GOAL + " tasks");
        }

        Goal goal = new Goal();
        goal.setId(UuidV7.generate());
        goal.setOrgId(orgId);
        goal.setTitle(title);
        goal.setDescription(description == null ? "" : description);
        goal.setStatus("planning");
        RequestContext.actor().ifPresent(actor -> goal.setRequestedBy(UUID.fromString(actor.id())));
        goals.save(goal);

        List<Task> created = new ArrayList<>();
        for (int index = 0; index < taskRequests.size(); index++) {
            TaskRequest request = taskRequests.get(index);
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

        validateNoCycles(taskRequests);
        tasks.saveAll(created);

        goal.setStatus("running");
        goals.save(goal);
        log.info("Goal {} created with {} task(s)", goal.getId(), created.size());
        return goal;
    }

    /**
     * Refuses a dependency graph that cannot complete.
     *
     * <p>A cycle is easy to introduce when a plan is generated rather than typed, and the symptom
     * - a goal that stays "running" with no task ever becoming ready - is very hard to read from
     * the interface. Catching it at creation turns it into a clear validation error.
     */
    private void validateNoCycles(List<TaskRequest> requests) {
        Set<Integer> visiting = new HashSet<>();
        Set<Integer> done = new HashSet<>();
        for (int index = 0; index < requests.size(); index++) {
            walk(index, requests, visiting, done);
        }
    }

    private void walk(int index, List<TaskRequest> requests, Set<Integer> visiting, Set<Integer> done) {
        if (done.contains(index)) {
            return;
        }
        if (!visiting.add(index)) {
            throw ApiException.validation("tasks", "the task dependencies form a cycle");
        }
        List<Integer> dependencies = requests.get(index).dependsOn();
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
    @Transactional
    public boolean runNextTask(UUID orgId) {
        List<Task> candidates = tasks.findClaimable(orgId, PageRequest.of(0, 10));

        for (Task candidate : candidates) {
            if (!dependenciesMet(candidate)) {
                continue;
            }
            // The request that created a goal and the goal sweep both advance it. The claim
            // makes sure only one of them runs a given task.
            Optional<Task> claimed = tasks.claim(candidate.getId());
            if (claimed.isEmpty()) {
                continue;
            }
            Task task = claimed.get();
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

            try {
                runner.start(orgId, task.getAgentId(), task.getId(), task.getInstruction(), "task");
            } catch (ApiException e) {
                progress.onStartFailed(task, e.getMessage());
            }
            return true;
        }
        return false;
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

    private boolean dependenciesMet(Task task) {
        List<Task> siblings = tasks.findByGoalIdOrderByPosition(task.getGoalId());
        return siblings.stream()
                .filter(sibling -> sibling.getPosition() < task.getPosition())
                .allMatch(Task::isTerminal);
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
