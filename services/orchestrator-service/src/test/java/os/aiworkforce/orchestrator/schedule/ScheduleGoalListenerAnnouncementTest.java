package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener.SchedulePause;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;


/** A schedule that pauses itself is announced once, as it pauses, so a workspace hears of it. */
class ScheduleGoalListenerAnnouncementTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Schedules schedules;
    private LifecycleAnnouncer announcer;
    private ScheduleGoalListener listener;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        schedules = mock(Schedules.class);
        announcer = mock(LifecycleAnnouncer.class);
        ObjectProvider<LifecycleAnnouncer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(announcer);
        listener = new ScheduleGoalListener(schedules);
        listener.setAnnouncer(provider);
        lenient().when(schedules.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Schedule schedule(int failures, boolean enabled) {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setOrgId(ORG);
        schedule.setRequestedBy(UUID.randomUUID());
        schedule.setConsecutiveFailures(failures);
        schedule.setEnabled(enabled);
        when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private Goal failed(Schedule schedule) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setScheduleId(schedule.getId());
        goal.setStatus("failed");
        return goal;
    }

    @Test
    @DisplayName("the third failure in a row pauses the schedule and announces it, with who it works for")
    void announcesThePause() {
        Schedule schedule = schedule(2, true);

        listener.onGoalFinished(failed(schedule));

        ArgumentCaptor<SchedulePause> pause = ArgumentCaptor.forClass(SchedulePause.class);
        verify(announcer).schedulePaused(pause.capture());
        assertThat(pause.getValue().orgId()).isEqualTo(ORG);
        assertThat(pause.getValue().scheduleId()).isEqualTo(schedule.getId());
        assertThat(pause.getValue().ownerId()).isEqualTo(schedule.getRequestedBy());
        assertThat(pause.getValue().failures()).isEqualTo(3);
        assertThat(pause.getValue().reason()).isEqualTo("Paused after 3 failed runs in a row.");
        assertThat(schedule.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a failure short of the limit is not announced")
    void belowTheLimitIsQuiet() {
        listener.onGoalFinished(failed(schedule(1, true)));

        verify(announcer, never()).schedulePaused(any());
    }

    @Test
    @DisplayName("a schedule already paused that fails again, run by hand, is not announced a second time")
    void anAlreadyPausedScheduleIsNotNewsAgain() {
        listener.onGoalFinished(failed(schedule(5, false)));

        verify(announcer, never()).schedulePaused(any());
    }

    @Test
    @DisplayName("with no announcer to tell, the pause still happens")
    void noAnnouncer() {
        ScheduleGoalListener alone = new ScheduleGoalListener(schedules);
        Schedule schedule = schedule(2, true);

        alone.onGoalFinished(failed(schedule));

        assertThat(schedule.isEnabled()).isFalse();
    }
}
