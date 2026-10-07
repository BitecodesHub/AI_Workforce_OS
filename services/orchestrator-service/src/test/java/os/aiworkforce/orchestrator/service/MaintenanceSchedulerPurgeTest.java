package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import os.aiworkforce.orchestrator.repository.ProcessedEvents;

/** The nightly sweep that forgets event ids older than the idempotency window. */
class MaintenanceSchedulerPurgeTest {

    private ProcessedEvents processedEvents;
    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEvents.class);
        scheduler = new MaintenanceScheduler(
                mock(AgentRunner.class),
                mock(ApprovalService.class),
                mock(GoalService.class),
                mock(QuestionService.class),
                mock(RunExecutor.class),
                processedEvents);
    }

    @Test
    @DisplayName("it forgets event ids older than seven days")
    void forgetsIdsOlderThanSevenDays() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(processedEvents.deleteProcessedBefore(any())).thenReturn(12);
        Instant before = Instant.now();

        scheduler.purgeProcessedEvents();

        verify(processedEvents).deleteProcessedBefore(cutoff.capture());
        assertThat(MaintenanceScheduler.PROCESSED_EVENT_RETENTION).isEqualTo(Duration.ofDays(7));
        assertThat(cutoff.getValue())
                .isBetween(before.minus(7, ChronoUnit.DAYS), Instant.now().minus(7, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("a failing purge is logged and does not stop the scheduler's other sweeps")
    void aFailingPurgeDoesNotEscape() {
        when(processedEvents.deleteProcessedBefore(any())).thenThrow(new IllegalStateException("database is down"));

        scheduler.purgeProcessedEvents();

        verify(processedEvents).deleteProcessedBefore(any());
    }

    @Test
    @DisplayName("a scheduler built without the repository has nothing to purge and does not fail")
    void withoutTheRepositoryItDoesNothing() {
        MaintenanceScheduler bare = new MaintenanceScheduler(
                mock(AgentRunner.class),
                mock(ApprovalService.class),
                mock(GoalService.class),
                mock(QuestionService.class),
                mock(RunExecutor.class),
                null);

        bare.purgeProcessedEvents();

        verify(processedEvents, never()).deleteProcessedBefore(any());
    }

    @Test
    @DisplayName("it runs once a day, in the small hours, and the time can be set in configuration")
    void runsDailyInTheSmallHours() throws NoSuchMethodException {
        Method method = MaintenanceScheduler.class.getMethod("purgeProcessedEvents");

        Scheduled schedule = method.getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.cron()).contains("aiwos.scheduling.processed-events-purge-cron").endsWith("0 30 3 * * *}");
    }
}
