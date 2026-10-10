// @find: tests for schedule sweep, schedule, processes due schedules, failure is swallowed, ScheduleSweepTest, ScheduleSweep
// @what: Tests for ScheduleSweep in the orchestrator schedule package (2 test methods).
// @flow: Exercises ScheduleSweep
package os.aiworkforce.orchestrator.schedule;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tick that notices a schedule is due: never lets a failure stop the next tick. */
class ScheduleSweepTest {

    private ScheduleService service;
    private ScheduleSweep sweep;

    @BeforeEach
    void setUp() {
        service = mock(ScheduleService.class);
        sweep = new ScheduleSweep(service);
    }

    // @find: test processes due schedules, schedule sweep
    @Test
    @DisplayName("processes due schedules through the service, in one bounded batch")
    void processesDueSchedules() {
        when(service.sweepDue(50)).thenReturn(3);

        sweep.sweep();

        verify(service).sweepDue(50);
    }

    // @find: test failure is swallowed, schedule sweep
    @Test
    @DisplayName("a failing sweep does not escape the tick")
    void failureIsSwallowed() {
        when(service.sweepDue(50)).thenThrow(new IllegalStateException("database unavailable"));

        sweep.sweep();

        verify(service).sweepDue(50);
    }
}
