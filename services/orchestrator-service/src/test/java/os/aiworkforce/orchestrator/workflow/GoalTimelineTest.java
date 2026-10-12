// @find: tests for goal timeline, replay timeline, live view, tasks runs and waits, approval wait ends when decided, open question has no end, expired wait ends at last change, workflow snapshot, goal without workflow, private conversation goal hidden, other workspace not found, GoalTimelineTest, GoalTimelineService, GoalTimelineController
// @what: Tests for GoalTimelineService and GoalTimelineController: the timestamps a timeline carries, the workflow canvas behind a goal, and that private and foreign goals are not found.
// @flow: Exercises GoalTimelineService with mocked repositories, and GoalTimelineController with a mocked ConversationAccess
package os.aiworkforce.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.chat.ConversationAccess;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.BaseEntity;

/** A timeline carries every start, wait and finish as a timestamp, and the canvas the goal ran. */
class GoalTimelineTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-12T09:00:00Z");

    private Tasks tasks;
    private Runs runs;
    private Agents agents;
    private WorkflowReads reads;
    private WorkflowRuns workflowRuns;
    private Workflows workflows;
    private GoalTimelineService service;

    @BeforeEach
    void setUp() {
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        agents = mock(Agents.class);
        reads = mock(WorkflowReads.class);
        workflowRuns = mock(WorkflowRuns.class);
        workflows = mock(Workflows.class);
        service = new GoalTimelineService(tasks, runs, agents, reads, workflowRuns, workflows);
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(Permission.Codes.TASK_READ), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static void stamp(BaseEntity entity, String field, Instant at) {
        try {
            Field declared = BaseEntity.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(entity, at);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Goal goal(String status) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("Reply to a customer");
        goal.setStatus(status);
        stamp(goal, "createdAt", T0);
        return goal;
    }

    private static Task task(Goal goal, Agent agent, int position, List<UUID> dependsOn, String status) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setAgentId(agent.getId());
        task.setTitle("Step " + position);
        task.setInstruction("Do it.");
        task.setPosition(position);
        task.setDependsOn(dependsOn);
        task.setStatus(status);
        return task;
    }

    private static Run run(Task task, String status, Instant started, Instant completed) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(task.getId());
        run.setAgentId(task.getAgentId());
        run.setStatus(status);
        run.setStartedAt(started);
        run.setCompletedAt(completed);
        return run;
    }

    // @find: test timeline carries tasks runs and waits, goal timeline
    @Test
    @DisplayName("a timeline lists tasks with their agents, runs in start order, and approval and question waits with their ends")
    void buildsTimeline() {
        Agent rhea = WorkflowValidatorTest.agent("Rhea", "active");
        Goal goal = goal("running");
        Task first = task(goal, rhea, 0, List.of(), "completed");
        first.setStartedAt(T0.plusSeconds(1));
        first.setCompletedAt(T0.plusSeconds(60));
        Task second = task(goal, rhea, 1, List.of(first.getId()), "waiting_input");
        second.setStartedAt(T0.plusSeconds(61));
        Run firstRun = run(first, "completed", T0.plusSeconds(1), T0.plusSeconds(60));
        Run secondRun = run(second, "waiting_input", T0.plusSeconds(61), null);

        Approval approval = new Approval();
        approval.setOrgId(ORG);
        approval.setRunId(firstRun.getId());
        approval.setTaskId(first.getId());
        approval.setStatus("approved");
        approval.setRequestedAt(T0.plusSeconds(10));
        approval.setDecidedAt(T0.plusSeconds(40));
        RunQuestion open = new RunQuestion();
        open.setOrgId(ORG);
        open.setRunId(secondRun.getId());
        open.setTaskId(second.getId());
        open.setStatus("pending");
        stamp(open, "createdAt", T0.plusSeconds(70));
        RunQuestion expired = new RunQuestion();
        expired.setOrgId(ORG);
        expired.setRunId(secondRun.getId());
        expired.setTaskId(second.getId());
        expired.setStatus("expired");
        stamp(expired, "createdAt", T0.plusSeconds(62));
        stamp(expired, "updatedAt", T0.plusSeconds(65));

        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(first, second));
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of(secondRun, firstRun));
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(rhea));
        when(reads.approvalsOfRuns(eq(ORG), any())).thenReturn(List.of(approval));
        when(reads.questionsOfRuns(eq(ORG), any())).thenReturn(List.of(open, expired));
        when(workflowRuns.findByOrgIdAndGoalId(ORG, goal.getId())).thenReturn(Optional.empty());

        GoalTimelineService.Timeline timeline = service.timeline(ORG, goal);

        assertThat(timeline.goalId()).isEqualTo(goal.getId());
        assertThat(timeline.createdAt()).isEqualTo(T0);
        assertThat(timeline.now()).isNotNull();
        assertThat(timeline.tasks()).extracting(GoalTimelineService.TimelineTask::agentName).containsExactly("Rhea", "Rhea");
        assertThat(timeline.tasks().get(1).dependsOn()).containsExactly(first.getId());
        assertThat(timeline.tasks()).noneMatch(GoalTimelineService.TimelineTask::explicitDependencies);
        assertThat(timeline.tasks().get(0).runId()).isEqualTo(firstRun.getId());
        assertThat(timeline.runs()).extracting(GoalTimelineService.TimelineRun::id)
                .containsExactly(firstRun.getId(), secondRun.getId());
        assertThat(timeline.waits()).extracting(GoalTimelineService.TimelineWait::kind)
                .containsExactly("approval", "question", "question");
        assertThat(timeline.waits().get(0).endedAt()).isEqualTo(T0.plusSeconds(40));
        assertThat(timeline.waits().get(1).endedAt()).isEqualTo(T0.plusSeconds(65));
        assertThat(timeline.waits().get(1).outcome()).isEqualTo("expired");
        assertThat(timeline.waits().get(2).endedAt()).isNull();
        assertThat(timeline.workflow()).isNull();
    }

    // @find: test timeline carries workflow snapshot, goal timeline
    @Test
    @DisplayName("a goal a workflow started carries the canvas copy it ran and the node of each task")
    void workflowSnapshot() {
        Goal goal = goal("completed");
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setWorkflowId(UUID.randomUUID());
        run.setWorkflowVersion(3);
        run.setGoalId(goal.getId());
        run.setGraph(WorkflowGraphs.canvas().step("n1", null, "Go.", 0, 0).map());
        run.setNodeIds(List.of("n1"));
        Workflow workflow = new Workflow();
        workflow.setName("Morning digest");
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of());
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
        when(workflowRuns.findByOrgIdAndGoalId(ORG, goal.getId())).thenReturn(Optional.of(run));
        when(workflows.findByIdAndOrgId(run.getWorkflowId(), ORG)).thenReturn(Optional.of(workflow));

        GoalTimelineService.Timeline timeline = service.timeline(ORG, goal);

        assertThat(timeline.workflow().workflowRunId()).isEqualTo(run.getId());
        assertThat(timeline.workflow().name()).isEqualTo("Morning digest");
        assertThat(timeline.workflow().version()).isEqualTo(3);
        assertThat(timeline.workflow().nodeIds()).containsExactly("n1");
        assertThat(timeline.workflow().graph()).isEqualTo(run.getGraph());
        // No tasks, so no run, approval or question is read.
        verify(runs, never()).findByTaskIdInOrderByTaskIdAscStartedAtDesc(any());
        verify(reads, never()).approvalsOfRuns(any(), any());
    }

    // @find: test deleted workflow keeps its replay name, explicit dependencies in timeline, goal timeline
    @Test
    @DisplayName("a run whose workflow was deleted keeps the name it ran under, and workflow tasks say their dependencies are complete")
    void deletedWorkflowKeepsName() {
        Agent rhea = WorkflowValidatorTest.agent("Rhea", "active");
        Goal goal = goal("completed");
        Task root = task(goal, rhea, 0, List.of(), "completed");
        root.setExplicitDependencies(true);
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setWorkflowId(null);
        run.setWorkflowName("Morning digest");
        run.setGoalId(goal.getId());
        run.setGraph(WorkflowGraphs.canvas().step("n1", rhea.getId(), "Go.", 0, 0).map());
        run.setNodeIds(List.of("n1"));
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(root));
        when(runs.findByTaskIdInOrderByTaskIdAscStartedAtDesc(any())).thenReturn(List.of());
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(rhea));
        when(workflowRuns.findByOrgIdAndGoalId(ORG, goal.getId())).thenReturn(Optional.of(run));

        GoalTimelineService.Timeline timeline = service.timeline(ORG, goal);

        assertThat(timeline.workflow().workflowId()).isNull();
        assertThat(timeline.workflow().name()).isEqualTo("Morning digest");
        assertThat(timeline.tasks().get(0).explicitDependencies()).isTrue();
        verify(workflows, never()).findByIdAndOrgId(any(), any());
    }

    // @find: test private conversation goal hidden from timeline, goal timeline controller
    @Test
    @DisplayName("the timeline of a goal from a private conversation the caller is not in, or of another workspace, is not found")
    void hiddenGoalsNotFound() {
        Goals goals = mock(Goals.class);
        GoalTimelineService timelines = mock(GoalTimelineService.class);
        ConversationAccess access = mock(ConversationAccess.class);
        GoalTimelineController controller = new GoalTimelineController(goals, timelines);
        controller.setAccess(access);
        Goal privateGoal = goal("running");
        privateGoal.setConversationId(UUID.randomUUID());
        Goal openGoal = goal("running");
        when(goals.findByIdAndOrgId(privateGoal.getId(), ORG)).thenReturn(Optional.of(privateGoal));
        when(goals.findByIdAndOrgId(openGoal.getId(), ORG)).thenReturn(Optional.of(openGoal));
        when(access.hidden(eq(ORG), any(), eq(privateGoal.getConversationId()))).thenReturn(true);
        UUID foreign = UUID.randomUUID();
        when(goals.findByIdAndOrgId(foreign, ORG)).thenReturn(Optional.empty());

        ApiException hidden = catchThrowableOfType(() -> controller.timeline(privateGoal.getId()), ApiException.class);
        ApiException missing = catchThrowableOfType(() -> controller.timeline(foreign), ApiException.class);
        controller.timeline(openGoal.getId());

        assertThat(hidden.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(missing.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(timelines, never()).timeline(ORG, privateGoal);
        verify(timelines).timeline(ORG, openGoal);
    }
}
