package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

class ScheduleServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");

    private Schedules schedules;
    private ScheduleGoals scheduleGoals;
    private Agents agents;
    private GoalService goalService;
    private ScheduleZoneLookup zones;
    private ScheduleService service;

    private UUID agentId;
    private Agent agent;

    @BeforeEach
    void setUp() {
        schedules = mock(Schedules.class);
        scheduleGoals = mock(ScheduleGoals.class);
        agents = mock(Agents.class);
        goalService = mock(GoalService.class);
        zones = mock(ScheduleZoneLookup.class);
        service = new ScheduleService(schedules, scheduleGoals, agents, goalService, zones);

        lenient().when(zones.zoneFor(ORG)).thenReturn(ZONE);
        lenient().when(schedules.save(any())).thenAnswer(call -> call.getArgument(0));

        agentId = UUID.randomUUID();
        agent = new Agent();
        agent.setId(agentId);
        agent.setOrgId(ORG);
        agent.setName("Hana");
        agent.setStatus("active");
        lenient().when(agents.findByIdAndOrgId(agentId, ORG)).thenReturn(Optional.of(agent));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private Schedule schedule(String kind) {
        Schedule schedule = new Schedule();
        schedule.setId(UuidV7.generate());
        schedule.setOrgId(ORG);
        schedule.setName("Weekly digest");
        schedule.setAgentId(agentId);
        schedule.setInstruction("Summarise the week");
        schedule.setKind(kind);
        schedule.setTimezone(ZONE.getId());
        schedule.setDescription("irrelevant for this test");
        schedule.setEnabled(true);
        schedule.setOverlapPolicy("skip");
        return schedule;
    }

    @Nested
    @DisplayName("creating a schedule")
    class Create {

        @Test
        @DisplayName("parses the phrase, computes the next run and records who asked for it")
        void createsFromRecurringPhrase() {
            UUID personId = UUID.randomUUID();
            RequestContext.setActor(Actor.user(personId.toString(), ORG.toString(), null, Set.of(), 0L));

            Schedule created =
                    service.create(ORG, "Daily standup summary", agentId, "Summarise standup", "daily at 9am");

            assertThat(created.getKind()).isEqualTo("recurring");
            assertThat(created.getCron()).isEqualTo("0 0 9 * * *");
            assertThat(created.getDescription()).isEqualTo("Every day at 9:00 am");
            assertThat(created.getTimezone()).isEqualTo("Australia/Melbourne");
            assertThat(created.getNextRunAt()).isNotNull();
            assertThat(created.getRequestedBy()).isEqualTo(personId);
        }

        @Test
        @DisplayName("a once schedule's next run is its run-at instant")
        void createsFromOncePhrase() {
            Schedule created = service.create(ORG, "One-off reminder", agentId, "Remind Jordan", "in 20 minutes");

            assertThat(created.getKind()).isEqualTo("once");
            assertThat(created.getRunAt()).isEqualTo(created.getNextRunAt());
        }

        @Test
        @DisplayName("refuses an agent from another workspace")
        void refusesUnknownAgent() {
            UUID otherAgent = UUID.randomUUID();
            when(agents.findByIdAndOrgId(otherAgent, ORG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(ORG, "x", otherAgent, "y", "daily at 9am"))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("refuses a paused agent")
        void refusesPausedAgent() {
            agent.setStatus("paused");

            assertThatThrownBy(() -> service.create(ORG, "x", agentId, "y", "daily at 9am"))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("an unreadable phrase fails before anything is saved")
        void refusesUnreadablePhrase() {
            assertThatThrownBy(() -> service.create(ORG, "x", agentId, "y", "whenever suits"))
                    .isInstanceOf(ApiException.class);
            verify(schedules, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updating a schedule")
    class Update {

        @Test
        @DisplayName("re-parses only when a new phrase is given")
        void updatesNameWithoutTouchingTiming() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            existing.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            when(schedules.findByIdAndOrgId(existing.getId(), ORG)).thenReturn(Optional.of(existing));

            Schedule updated = service.update(ORG, existing.getId(), "New name", null, null, null);

            assertThat(updated.getName()).isEqualTo("New name");
            assertThat(updated.getCron()).isEqualTo("0 0 9 * * *");
            assertThat(updated.getNextRunAt()).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
        }

        @Test
        @DisplayName("a new phrase recomputes cron, description and the next run")
        void reparsesOnNewText() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            when(schedules.findByIdAndOrgId(existing.getId(), ORG)).thenReturn(Optional.of(existing));

            Schedule updated = service.update(ORG, existing.getId(), null, null, null, "every hour");

            assertThat(updated.getCron()).isEqualTo("0 0 * * * *");
            assertThat(updated.getDescription()).isEqualTo("Every hour");
        }

        @Test
        @DisplayName("not found raises a 404")
        void missingSchedule() {
            when(schedules.findByIdAndOrgId(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(ORG, UUID.randomUUID(), "x", null, null, null))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("pause and resume")
    class PauseResume {

        @Test
        @DisplayName("pause disables the schedule and clears any stale auto-pause note")
        void pauseClearsReason() {
            Schedule existing = schedule("recurring");
            existing.setPausedReason("Paused after 3 failed runs in a row.");
            when(schedules.findByIdAndOrgId(existing.getId(), ORG)).thenReturn(Optional.of(existing));

            Schedule paused = service.pause(ORG, existing.getId());

            assertThat(paused.isEnabled()).isFalse();
            assertThat(paused.getPausedReason()).isNull();
        }

        @Test
        @DisplayName("resume re-enables, clears failures and the pause note, and recomputes the next run")
        void resumeResets() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            existing.setEnabled(false);
            existing.setConsecutiveFailures(3);
            existing.setPausedReason("Paused after 3 failed runs in a row.");
            when(schedules.findByIdAndOrgId(existing.getId(), ORG)).thenReturn(Optional.of(existing));

            Schedule resumed = service.resume(ORG, existing.getId());

            assertThat(resumed.isEnabled()).isTrue();
            assertThat(resumed.getConsecutiveFailures()).isZero();
            assertThat(resumed.getPausedReason()).isNull();
            assertThat(resumed.getNextRunAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("run now")
    class RunNow {

        @Test
        @DisplayName("fires even when disabled, and does not move the next scheduled run")
        void firesRegardlessOfEnabled() {
            Schedule existing = schedule("recurring");
            existing.setEnabled(false);
            existing.setNextRunAt(Instant.parse("2026-10-05T00:00:00Z"));
            when(schedules.findByIdAndOrgId(existing.getId(), ORG)).thenReturn(Optional.of(existing));
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID());
            when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);

            Schedule result = service.runNow(ORG, existing.getId());

            assertThat(result.getLastGoalId()).isEqualTo(goal.getId());
            assertThat(result.getLastRunAt()).isNotNull();
            assertThat(result.getNextRunAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
            assertThat(result.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("fires as the schedule's requester, or the platform when there is none")
        void firesAsRequester() {
            UUID requester = UUID.randomUUID();
            Schedule withRequester = schedule("once");
            withRequester.setRunAt(Instant.now().plusSeconds(60));
            withRequester.setRequestedBy(requester);
            when(schedules.findByIdAndOrgId(withRequester.getId(), ORG)).thenReturn(Optional.of(withRequester));

            List<Actor> seen = new java.util.ArrayList<>();
            when(goalService.createGoal(any(), any(), anyBoolean())).thenAnswer(call -> {
                seen.add(RequestContext.actor().orElse(null));
                Goal goal = new Goal();
                goal.setId(UUID.randomUUID());
                return goal;
            });

            service.runNow(ORG, withRequester.getId());

            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).id()).isEqualTo(requester.toString());
            assertThat(seen.get(0).kind()).isEqualTo(Actor.Kind.USER);

            Schedule noRequester = schedule("once");
            noRequester.setRunAt(Instant.now().plusSeconds(60));
            when(schedules.findByIdAndOrgId(noRequester.getId(), ORG)).thenReturn(Optional.of(noRequester));

            service.runNow(ORG, noRequester.getId());

            assertThat(seen).hasSize(2);
            assertThat(seen.get(1).isSystem()).isTrue();
        }
    }

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        @Test
        @DisplayName("fires a due schedule and advances a recurring one to its next occurrence")
        void firesDueRecurring() {
            Schedule due = schedule("recurring");
            due.setCron("0 0 9 * * *");
            due.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            when(schedules.findDue(any(), any())).thenReturn(List.of(due));
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID());
            when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);

            int processed = service.sweepDue(50);

            assertThat(processed).isEqualTo(1);
            assertThat(due.getLastGoalId()).isEqualTo(goal.getId());
            // Advanced to the next real 9am occurrence from now, not left at the old due time.
            assertThat(due.getNextRunAt()).isAfter(Instant.now().minusSeconds(10));
            assertThat(due.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("a fired once schedule disables itself and clears its next run")
        void onceDisablesAfterFiring() {
            Schedule due = schedule("once");
            due.setRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            due.setNextRunAt(due.getRunAt());
            when(schedules.findDue(any(), any())).thenReturn(List.of(due));
            when(goalService.createGoal(any(), any(), anyBoolean())).thenAnswer(call -> {
                Goal goal = new Goal();
                goal.setId(UUID.randomUUID());
                return goal;
            });

            service.sweepDue(50);

            assertThat(due.isEnabled()).isFalse();
            assertThat(due.getNextRunAt()).isNull();
        }

        @Test
        @DisplayName("skips this occurrence when overlap policy is skip and the last goal is still running")
        void skipsOverlap() {
            Schedule due = schedule("recurring");
            due.setCron("0 0 9 * * *");
            due.setOverlapPolicy("skip");
            due.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            when(schedules.findDue(any(), any())).thenReturn(List.of(due));
            Goal stillRunning = new Goal();
            stillRunning.setStatus("running");
            when(scheduleGoals.findFirstByScheduleIdOrderByCreatedAtDesc(due.getId()))
                    .thenReturn(Optional.of(stillRunning));

            int processed = service.sweepDue(50);

            assertThat(processed).isEqualTo(1);
            verify(goalService, never()).createGoal(any(), any(), anyBoolean());
            // Still advances, so an overlapping occurrence is skipped rather than retried forever.
            assertThat(due.getNextRunAt()).isAfter(Instant.now().minusSeconds(10));
        }

        @Test
        @DisplayName("fires anyway when the overlap policy is queue")
        void queuePolicyFiresDespiteOverlap() {
            Schedule due = schedule("recurring");
            due.setCron("0 0 9 * * *");
            due.setOverlapPolicy("queue");
            due.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            when(schedules.findDue(any(), any())).thenReturn(List.of(due));
            Goal stillRunning = new Goal();
            stillRunning.setStatus("running");
            lenient()
                    .when(scheduleGoals.findFirstByScheduleIdOrderByCreatedAtDesc(due.getId()))
                    .thenReturn(Optional.of(stillRunning));
            when(goalService.createGoal(any(), any(), anyBoolean())).thenAnswer(call -> {
                Goal goal = new Goal();
                goal.setId(UUID.randomUUID());
                return goal;
            });

            service.sweepDue(50);

            verify(goalService, times(1)).createGoal(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("one schedule's failure does not stop the rest of the sweep")
        void oneFailureDoesNotStopTheRest() {
            Schedule broken = schedule("recurring");
            broken.setCron("0 0 9 * * *");
            broken.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            Schedule healthy = schedule("recurring");
            healthy.setCron("0 0 9 * * *");
            healthy.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            when(schedules.findDue(any(), any())).thenReturn(List.of(broken, healthy));
            when(goalService.createGoal(any(), any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("database unavailable"))
                    .thenAnswer(call -> {
                        Goal goal = new Goal();
                        goal.setId(UUID.randomUUID());
                        return goal;
                    });

            int processed = service.sweepDue(50);

            assertThat(processed).isEqualTo(1);
            assertThat(healthy.getLastGoalId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("previewing a phrase")
    class Preview {

        @Test
        @DisplayName("uses the workspace timezone when none is given, and echoes five next runs")
        void usesWorkspaceZone() {
            ScheduleService.PreviewResult result = service.preview(ORG, "every day at 9am", null);

            assertThat(result.timezone()).isEqualTo("Australia/Melbourne");
            assertThat(result.kind()).isEqualTo("recurring");
            assertThat(result.nextRuns()).hasSize(5);
        }

        @Test
        @DisplayName("an override timezone is used instead of the workspace's own")
        void usesOverrideZone() {
            ScheduleService.PreviewResult result = service.preview(ORG, "every day at 9am", "UTC");

            assertThat(result.timezone()).isEqualTo("UTC");
        }

        @Test
        @DisplayName("an unreadable phrase surfaces the parser's own validation error")
        void surfacesParserError() {
            assertThatThrownBy(() -> service.preview(ORG, "whenever suits", null))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.details()).containsKey("examples"));
        }
    }
}
