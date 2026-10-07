package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/** What the schedules endpoints hand back, always read out of the caller's own workspace. */
class ScheduleControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private ScheduleService service;
    private Agents agents;
    private ScheduleController controller;
    private Actor caller;

    @BeforeEach
    void setUp() {
        service = mock(ScheduleService.class);
        agents = mock(Agents.class);
        controller = new ScheduleController(service, agents);
        caller = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L);
        RequestContext.setActor(caller);
        when(agents.findById(any())).thenReturn(Optional.empty());
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
    @DisplayName("a schedule's runs come back as slim rows, a page at a time, with no tasks")
    void runsAreSlimAndPaged() {
        Schedule schedule = schedule();
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle(schedule.getName());
        goal.setStatus("completed");
        goal.setScheduleId(schedule.getId());
        PageRequest asked = PageRequest.of(2, 20);
        when(service.runs(ORG, schedule.getId(), asked)).thenReturn(new PageImpl<>(List.of(goal), asked, 61));

        ScheduleController.ScheduleRunsPage page = controller.runs(schedule.getId(), 2, 20);

        assertThat(page.runs())
                .containsExactly(new ScheduleController.ScheduleRunView(
                        goal.getId(), schedule.getName(), "completed", null, null));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(61);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("a history page is at least 1 and at most 100 long, from page 0")
    void runsPageIsBounded() {
        Schedule schedule = schedule();
        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        when(service.runs(eq(ORG), eq(schedule.getId()), asked.capture()))
                .thenAnswer(call -> new PageImpl<Goal>(List.of(), call.getArgument(2), 0));

        controller.runs(schedule.getId(), -3, 5_000);
        controller.runs(schedule.getId(), 0, 0);

        assertThat(asked.getAllValues().get(0).getPageNumber()).isZero();
        assertThat(asked.getAllValues().get(0).getPageSize()).isEqualTo(ScheduleController.MAX_RUNS_PAGE);
        assertThat(asked.getAllValues().get(1).getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("every change passes the signed-in caller to the service, which decides whether they may")
    void changesPassTheCaller() {
        Schedule schedule = schedule();
        UUID id = schedule.getId();
        when(service.update(ORG, id, "n", null, null, null, caller)).thenReturn(schedule);
        when(service.pause(ORG, id, caller)).thenReturn(schedule);
        when(service.resume(ORG, id, caller)).thenReturn(schedule);
        when(service.runNow(ORG, id, caller)).thenReturn(schedule);
        UUID newOwner = UUID.randomUUID();
        when(service.changeOwner(ORG, id, newOwner, caller)).thenReturn(schedule);

        controller.update(id, new ScheduleController.UpdateScheduleRequest("n", null, null, null));
        controller.pause(id);
        controller.resume(id);
        controller.runNow(id);
        controller.changeOwner(id, new ScheduleController.ChangeOwnerRequest(newOwner));
        controller.delete(id);

        verify(service).update(ORG, id, "n", null, null, null, caller);
        verify(service).pause(ORG, id, caller);
        verify(service).resume(ORG, id, caller);
        verify(service).runNow(ORG, id, caller);
        verify(service).changeOwner(ORG, id, newOwner, caller);
        verify(service).delete(ORG, id, caller);
    }

    @Test
    @DisplayName("a one-off that already ran reads as done; a paused one as paused; an enabled one as active")
    void viewDerivesState() {
        Schedule done = schedule();
        done.setKind("once");
        done.setRunAt(Instant.parse("2026-09-29T05:00:00Z"));
        done.setEnabled(false);
        done.setNextRunAt(null);
        Schedule pausedOnce = schedule();
        pausedOnce.setKind("once");
        pausedOnce.setRunAt(Instant.parse("2026-12-29T05:00:00Z"));
        pausedOnce.setNextRunAt(pausedOnce.getRunAt());
        pausedOnce.setEnabled(false);
        Schedule active = schedule();
        when(service.list(ORG)).thenReturn(List.of(done, pausedOnce, active));

        List<ScheduleController.ScheduleView> views = controller.list();

        assertThat(views).extracting(ScheduleController.ScheduleView::state).containsExactly("done", "paused", "active");
        assertThat(views).extracting(ScheduleController.ScheduleView::completed).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("the view names who the schedule runs as")
    void viewCarriesOwner() {
        Schedule schedule = schedule();
        UUID ownerId = UUID.randomUUID();
        schedule.setRequestedBy(ownerId);
        when(service.list(ORG)).thenReturn(List.of(schedule));

        assertThat(controller.list().get(0).createdBy()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("pause delegates to the service and returns its result")
    void pauseDelegates() {
        Schedule paused = schedule();
        paused.setEnabled(false);
        when(service.pause(ORG, paused.getId(), caller)).thenReturn(paused);

        ScheduleController.ScheduleView view = controller.pause(paused.getId());

        assertThat(view.enabled()).isFalse();
        assertThat(view.state()).isEqualTo("paused");
    }
}
