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

import os.aiworkforce.orchestrator.domain.Goal;

/** How a fired schedule's own health tracks the goals it creates. */
class ScheduleGoalListenerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private Schedules schedules;
    private ScheduleGoalListener listener;

    @BeforeEach
    void setUp() {
        schedules = mock(Schedules.class);
        listener = new ScheduleGoalListener(schedules);
        lenient().when(schedules.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Goal goalFor(Schedule schedule, String status) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setScheduleId(schedule.getId());
        goal.setStatus(status);
        return goal;
    }

    private Schedule schedule() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setOrgId(ORG);
        return schedule;
    }

    @Test
    @DisplayName("a goal with no schedule is ignored entirely")
    void ignoresGoalWithNoSchedule() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setStatus("completed");

        listener.onGoalFinished(goal);

        verify(schedules, never()).findByIdAndOrgId(any(), any());
    }

    @Test
    @DisplayName("a completed run resets the failure streak")
    void completedResetsFailures() {
        Schedule schedule = schedule();
        schedule.setConsecutiveFailures(2);
        when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));

        listener.onGoalFinished(goalFor(schedule, "completed"));

        assertThat(schedule.getConsecutiveFailures()).isZero();
        assertThat(schedule.getLastStatus()).isEqualTo("completed");
        assertThat(schedule.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a failure short of the limit only counts, it does not pause")
    void failureBelowLimitDoesNotPause() {
        Schedule schedule = schedule();
        schedule.setConsecutiveFailures(1);
        when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));

        listener.onGoalFinished(goalFor(schedule, "failed"));

        assertThat(schedule.getConsecutiveFailures()).isEqualTo(2);
        assertThat(schedule.isEnabled()).isTrue();
        assertThat(schedule.getPausedReason()).isNull();
    }

    @Test
    @DisplayName("the third failure in a row pauses the schedule with an honest reason")
    void thirdFailurePauses() {
        Schedule schedule = schedule();
        schedule.setConsecutiveFailures(2);
        when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));

        listener.onGoalFinished(goalFor(schedule, "failed"));

        assertThat(schedule.getConsecutiveFailures()).isEqualTo(3);
        assertThat(schedule.isEnabled()).isFalse();
        assertThat(schedule.getPausedReason()).isEqualTo("Paused after 3 failed runs in a row.");
    }

    @Test
    @DisplayName("a cancelled goal records the status but leaves the failure streak untouched")
    void cancelledLeavesStreakAlone() {
        Schedule schedule = schedule();
        schedule.setConsecutiveFailures(2);
        when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));

        listener.onGoalFinished(goalFor(schedule, "cancelled"));

        assertThat(schedule.getConsecutiveFailures()).isEqualTo(2);
        assertThat(schedule.getLastStatus()).isEqualTo("cancelled");
        assertThat(schedule.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a schedule that no longer exists is simply skipped")
    void missingScheduleIsSkipped() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setScheduleId(UUID.randomUUID());
        goal.setStatus("completed");
        when(schedules.findByIdAndOrgId(any(), any())).thenReturn(Optional.empty());

        listener.onGoalFinished(goal);

        verify(schedules, never()).save(any());
    }
}
