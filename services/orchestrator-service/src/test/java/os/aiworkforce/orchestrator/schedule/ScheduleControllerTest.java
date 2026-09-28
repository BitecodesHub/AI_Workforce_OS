package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.web.GoalController;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/** What the schedules endpoints hand back, always read out of the caller's own workspace. */
class ScheduleControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private ScheduleService service;
    private Agents agents;
    private Tasks tasks;
    private Runs runs;
    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScheduleService.class);
        agents = mock(Agents.class);
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        controller = new ScheduleController(service, agents, tasks, runs);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private Schedule schedule() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setOrgId(ORG);
        schedule.setName("Weekly digest");
        schedule.setAgentId(UUID.randomUUID());
        schedule.setInstruction("Summarise the week");
        schedule.setKind("recurring");
        schedule.setCron("0 0 9 * * *");
        schedule.setTimezone("Australia/Melbourne");
        schedule.setDescription("Every day at 9:00 am");
        schedule.setEnabled(true);
        schedule.setOverlapPolicy("skip");
        return schedule;
    }

    @Test
    @DisplayName("list looks up each schedule's agent name")
    void listIncludesAgentName() {
        Schedule schedule = schedule();
        Agent agent = new Agent();
        agent.setId(schedule.getAgentId());
        agent.setName("Priya");
        when(service.list(ORG)).thenReturn(List.of(schedule));
        when(agents.findById(schedule.getAgentId())).thenReturn(Optional.of(agent));

        List<ScheduleController.ScheduleView> views = controller.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).agentName()).isEqualTo("Priya");
        assertThat(views.get(0).id()).isEqualTo(schedule.getId());
    }

    @Test
    @DisplayName("preview passes the timezone override through and echoes back the parser's result")
    void previewDelegates() {
        ScheduleService.PreviewResult result = new ScheduleService.PreviewResult(
                "recurring", "0 0 9 * * *", null, "Every day at 9:00 am", "UTC", List.of(Instant.EPOCH));
        when(service.preview(ORG, "every day at 9am", "UTC")).thenReturn(result);

        ScheduleController.PreviewResponse response =
                controller.preview(new ScheduleController.PreviewRequest("every day at 9am", "UTC"));

        assertThat(response.description()).isEqualTo("Every day at 9:00 am");
        assertThat(response.nextRuns()).containsExactly(Instant.EPOCH);
    }

    @Test
    @DisplayName("a schedule's runs come back in the goal shape, tasks included")
    void runsMapToGoalView() {
        Schedule schedule = schedule();
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle(schedule.getName());
        goal.setStatus("completed");
        goal.setScheduleId(schedule.getId());
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setGoalId(goal.getId());
        task.setAgentId(schedule.getAgentId());
        task.setTitle(schedule.getName());
        task.setStatus("completed");
        Run run = new Run();
        run.setId(UUID.randomUUID());
        when(service.runs(ORG, schedule.getId())).thenReturn(List.of(goal));
        when(tasks.findByGoalIdOrderByPosition(goal.getId())).thenReturn(List.of(task));
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));

        List<GoalController.GoalView> views = controller.runs(schedule.getId());

        assertThat(views).hasSize(1);
        GoalController.GoalView view = views.get(0);
        assertThat(view.scheduleId()).isEqualTo(schedule.getId());
        assertThat(view.tasks()).hasSize(1);
        assertThat(view.tasks().get(0).runId()).isEqualTo(run.getId());
    }

    @Test
    @DisplayName("pause and resume delegate to the service and return its result")
    void pauseAndResumeDelegate() {
        Schedule paused = schedule();
        paused.setEnabled(false);
        when(service.pause(ORG, paused.getId())).thenReturn(paused);
        when(agents.findById(any())).thenReturn(Optional.empty());

        ScheduleController.ScheduleView view = controller.pause(paused.getId());

        assertThat(view.enabled()).isFalse();
    }
}
