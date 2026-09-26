package os.aiworkforce.orchestrator.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
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

    private final Goals goals;
    private final Tasks tasks;
    private final AgentRunner runner;

    public GoalService(
            Goals goals,
            Tasks tasks,
            AgentRunner runner) {
        this.goals = goals;
        this.tasks = tasks;
        this.runner = runner;
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
     */
    @Transactional
    public boolean runNextTask(UUID orgId) {
        List<Task> claimable = tasks.findClaimable(
                orgId, org.springframework.data.domain.PageRequest.of(0, 10));

        for (Task task : claimable) {
            if (!dependenciesMet(task)) {
                continue;
            }
            if (task.getAgentId() == null) {
                task.setStatus("skipped");
                task.setFailureReason("No agent was assigned to this task.");
                tasks.save(task);
                continue;
            }

            task.setStatus("running");
            task.setAttempt(task.getAttempt() + 1);
            task.setStartedAt(java.time.Instant.now());
            tasks.save(task);

            try {
                AgentRunner.Outcome outcome =
                        runner.start(orgId, task.getAgentId(), task.getId(), task.getInstruction(), "task");
                applyOutcome(task, outcome);
            } catch (ApiException e) {
                failTask(task, e.getMessage());
            }
            closeGoalIfFinished(task.getGoalId());
            return true;
        }
        return false;
    }

    private void applyOutcome(Task task, AgentRunner.Outcome outcome) {
        switch (outcome.status()) {
            case "completed" -> {
                task.setStatus("completed");
                task.setResult(outcome.answer());
                task.setCompletedAt(java.time.Instant.now());
                tasks.save(task);
            }
            case "waiting_approval" -> {
                // The task stays open. It resumes when the approval is decided, so it must not
                // be counted as finished or as failed in the meantime.
                task.setStatus("waiting_approval");
                tasks.save(task);
            }
            default -> failTask(task, "The agent did not complete this task.");
        }
    }

    private void failTask(Task task, String reason) {
        if (task.canRetry()) {
            // Back to pending so the scheduler picks it up again, with the attempt already
            // counted so it cannot loop forever.
            task.setStatus("pending");
            task.setFailureReason(reason);
        } else {
            task.setStatus("failed");
            task.setFailureReason(reason);
            task.setCompletedAt(java.time.Instant.now());
        }
        tasks.save(task);
    }

    private boolean dependenciesMet(Task task) {
        List<Task> siblings = tasks.findByGoalIdOrderByPosition(task.getGoalId());
        return siblings.stream()
                .filter(sibling -> sibling.getPosition() < task.getPosition())
                .allMatch(Task::isTerminal);
    }

    private void closeGoalIfFinished(UUID goalId) {
        if (tasks.countUnfinished(goalId) > 0) {
            return;
        }
        goals.findById(goalId).ifPresent(goal -> {
            List<Task> all = tasks.findByGoalIdOrderByPosition(goalId);
            boolean anyFailed = all.stream().anyMatch(task -> "failed".equals(task.getStatus()));
            goal.setStatus(anyFailed ? "failed" : "completed");
            goal.setCompletedAt(java.time.Instant.now());
            goals.save(goal);
            log.info("Goal {} finished as {}", goalId, goal.getStatus());
        });
    }

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
                    task.setStatus("cancelled");
                    task.setCompletedAt(java.time.Instant.now());
                    tasks.save(task);
                });
        goal.setStatus("cancelled");
        goal.setCompletedAt(java.time.Instant.now());
        goals.save(goal);
    }
}
