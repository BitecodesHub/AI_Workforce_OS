// @find: goal timeline, replay timeline, live workflow view, when each task started waited finished, approval waits, question waits, task runs timestamps, workflow snapshot for replay, GoalTimelineService
// @what: Builds a goal's timeline - its tasks, every run's start and end, every wait for an approval or an answer, and the workflow canvas it came from - for the live view and the replay.
// @flow: Called by GoalTimelineController; reads goals, tasks, runs, agents, approvals and questions in a fixed number of queries
package os.aiworkforce.orchestrator.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;

/**
 * A goal's history as timestamps, for watching it live and replaying it later.
 *
 * <p>Nothing here is new data: tasks already record when they started and finished, runs when
 * each attempt did, and approvals and questions when they were raised and settled. This gathers
 * them in one answer, a fixed handful of queries however many tasks the goal holds, so a page can
 * poll it while the goal runs. The replay itself - what state each step was in at a given moment
 * - is worked out by the console from these timestamps.
 *
 * <p>Only timing and status leave here: no instruction, result, tool argument or answer. Those
 * stay behind the run trace and its own permission.
 */
@Service
public class GoalTimelineService {

    private final Tasks tasks;
    private final Runs runs;
    private final Agents agents;
    private final WorkflowReads reads;
    private final WorkflowRuns workflowRuns;
    private final Workflows workflows;

    /** Wires the repositories the timeline reads. */
    public GoalTimelineService(
            Tasks tasks, Runs runs, Agents agents, WorkflowReads reads, WorkflowRuns workflowRuns, Workflows workflows) {
        this.tasks = tasks;
        this.runs = runs;
        this.agents = agents;
        this.reads = reads;
        this.workflowRuns = workflowRuns;
        this.workflows = workflows;
    }

    /**
     * One task: who does it, what it waits for, and when it started and ended.
     *
     * @param explicitDependencies whether {@code dependsOn} is complete (a workflow's task), so an
     *     empty list means it waits for nothing rather than for every earlier position
     */
    public record TimelineTask(
            UUID id,
            String title,
            UUID agentId,
            String agentName,
            String agentCategory,
            int position,
            List<UUID> dependsOn,
            boolean explicitDependencies,
            String status,
            Instant startedAt,
            Instant completedAt,
            UUID runId) {}

    /** One attempt at a task. */
    public record TimelineRun(UUID id, UUID taskId, String status, Instant startedAt, Instant completedAt) {}

    /**
     * One wait on a person.
     *
     * @param kind {@code approval} or {@code question}
     * @param endedAt when it was decided, answered, withdrawn or expired; null while it still waits
     * @param outcome the approval's or question's own status
     */
    public record TimelineWait(UUID runId, UUID taskId, String kind, Instant startedAt, Instant endedAt, String outcome) {}

    /**
     * The workflow canvas a goal was started from, as it was when it ran.
     *
     * @param workflowId null when the workflow has been deleted since
     * @param name the workflow's current name, or the name it ran under once deleted
     */
    public record TimelineWorkflow(
            UUID workflowId, UUID workflowRunId, String name, long version, Map<String, Object> graph, List<String> nodeIds) {}

    /**
     * Everything the live view and the replay need about one goal.
     *
     * @param now the server's clock when this was read, so a wait still open is measured against the
     *     same clock that stamped its start
     * @param workflow the canvas the goal came from, or null for a goal no workflow started
     */
    public record Timeline(
            UUID goalId,
            String title,
            String status,
            Instant createdAt,
            Instant completedAt,
            Instant now,
            List<TimelineTask> tasks,
            List<TimelineRun> runs,
            List<TimelineWait> waits,
            TimelineWorkflow workflow) {}

    // @find: build goal timeline, GET /api/goals/{goalId}/timeline
    /**
     * The timeline of a goal the caller has already been allowed to see.
     *
     * <p>Six reads: the tasks, their runs, the workspace's agents (for names), the runs' approvals,
     * their questions and the workflow run behind the goal (plus the workflow's current name when
     * there is one). Writes nothing.
     */
    @Transactional(readOnly = true)
    public Timeline timeline(UUID orgId, Goal goal) {
        List<Task> goalTasks = tasks.findByGoalIdOrderByPosition(goal.getId());
        List<UUID> taskIds = goalTasks.stream().map(Task::getId).toList();
        List<Run> taskRuns = taskIds.isEmpty() ? List.of() : runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(taskIds);
        Map<UUID, Agent> agentsById = agents.findByOrgIdOrderByName(orgId).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity(), (first, second) -> first));

        Map<UUID, Run> latestByTask = new java.util.HashMap<>();
        for (Run run : taskRuns) {
            latestByTask.putIfAbsent(run.getTaskId(), run);
        }
        List<TimelineTask> taskViews = goalTasks.stream()
                .map(task -> {
                    Agent agent = task.getAgentId() == null ? null : agentsById.get(task.getAgentId());
                    Run latest = latestByTask.get(task.getId());
                    return new TimelineTask(
                            task.getId(),
                            task.getTitle(),
                            task.getAgentId(),
                            agent == null ? null : agent.getName(),
                            agent == null ? null : agent.getCategory(),
                            task.getPosition(),
                            List.copyOf(task.getDependsOn()),
                            task.hasExplicitDependencies(),
                            task.getStatus(),
                            task.getStartedAt(),
                            task.getCompletedAt(),
                            latest == null ? null : latest.getId());
                })
                .toList();

        List<TimelineRun> runViews = taskRuns.stream()
                .sorted(Comparator.comparing(Run::getStartedAt))
                .map(run -> new TimelineRun(run.getId(), run.getTaskId(), run.getStatus(), run.getStartedAt(), run.getCompletedAt()))
                .toList();

        List<TimelineWait> waits = new ArrayList<>();
        List<UUID> runIds = taskRuns.stream().map(Run::getId).toList();
        if (!runIds.isEmpty()) {
            for (Approval approval : reads.approvalsOfRuns(orgId, runIds)) {
                waits.add(new TimelineWait(
                        approval.getRunId(),
                        approval.getTaskId(),
                        "approval",
                        approval.getRequestedAt(),
                        "pending".equals(approval.getStatus()) ? null : endOf(approval.getDecidedAt(), approval.getUpdatedAt()),
                        approval.getStatus()));
            }
            for (RunQuestion question : reads.questionsOfRuns(orgId, runIds)) {
                waits.add(new TimelineWait(
                        question.getRunId(),
                        question.getTaskId(),
                        "question",
                        question.getCreatedAt(),
                        "pending".equals(question.getStatus()) ? null : endOf(question.getAnsweredAt(), question.getUpdatedAt()),
                        question.getStatus()));
            }
            waits.sort(Comparator.comparing(TimelineWait::startedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        }

        TimelineWorkflow workflow = workflowRuns.findByOrgIdAndGoalId(orgId, goal.getId())
                .map(run -> new TimelineWorkflow(
                        run.getWorkflowId(),
                        run.getId(),
                        currentName(orgId, run),
                        run.getWorkflowVersion(),
                        run.getGraph(),
                        List.copyOf(run.getNodeIds())))
                .orElse(null);

        return new Timeline(
                goal.getId(),
                goal.getTitle(),
                goal.getStatus(),
                goal.getCreatedAt(),
                goal.getCompletedAt(),
                Instant.now(),
                taskViews,
                runViews,
                List.copyOf(waits),
                workflow);
    }

    /** The workflow's name now, or the name the run recorded when the workflow is gone. */
    private String currentName(UUID orgId, WorkflowRun run) {
        String recorded = run.getWorkflowName() == null || run.getWorkflowName().isBlank() ? null : run.getWorkflowName();
        if (run.getWorkflowId() == null) {
            return recorded;
        }
        return workflows.findByIdAndOrgId(run.getWorkflowId(), orgId).map(Workflow::getName).orElse(recorded);
    }

    /** When a settled wait ended: its own decision time, or the row's last change when it has none (expired, withdrawn). */
    private static Instant endOf(Instant settled, Instant lastChanged) {
        return settled != null ? settled : lastChanged;
    }
}
