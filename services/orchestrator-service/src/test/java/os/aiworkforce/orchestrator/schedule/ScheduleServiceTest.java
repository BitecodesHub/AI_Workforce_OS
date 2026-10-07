package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.UuidV7;

class ScheduleServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final ZoneId ZONE = ZoneId.of("Australia/Melbourne");

    private Schedules schedules;
    private ScheduleGoals scheduleGoals;
    private Agents agents;
    private GoalService goalService;
    private ScheduleZoneLookup zones;
    private AuditClient audit;
    private ScheduleService service;

    private UUID agentId;
    private Agent agent;

    /** The person every schedule built by {@link #schedule} runs as, unless a test says otherwise. */
    private final UUID ownerId = UUID.randomUUID();

    private Actor owner;

    /** A different person on the built-in employee role: may create work, may not cancel it. */
    private Actor employee;

    /** A different person on the manager role: may cancel anyone's work. */
    private UUID managerId;

    private Actor manager;

    @BeforeEach
    void setUp() {
        schedules = mock(Schedules.class);
        scheduleGoals = mock(ScheduleGoals.class);
        agents = mock(Agents.class);
        goalService = mock(GoalService.class);
        zones = mock(ScheduleZoneLookup.class);
        audit = mock(AuditClient.class);
        service = new ScheduleService(schedules, scheduleGoals, agents, goalService, zones, audit);

        owner = person(ownerId, Permission.Codes.TASK_READ, Permission.Codes.TASK_CREATE);
        employee = person(UUID.randomUUID(), Permission.Codes.TASK_READ, Permission.Codes.TASK_CREATE);
        managerId = UUID.randomUUID();
        manager = person(
                managerId, Permission.Codes.TASK_READ, Permission.Codes.TASK_CREATE, Permission.Codes.TASK_CANCEL);

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

    private static Actor person(UUID id, String... permissions) {
        return Actor.user(id.toString(), ORG.toString(), "role", Set.of(permissions), 0L);
    }

    private void stored(Schedule schedule) {
        lenient().when(schedules.findByIdAndOrgId(schedule.getId(), ORG)).thenReturn(Optional.of(schedule));
    }

    private Goal startedGoal() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setStatus("planning");
        return goal;
    }

    /** Every audit entry recorded so far, by action, with the actor and detail each carried. */
    private record Entry(Actor actor, String action, String resourceType, String resourceId, Map<String, Object> detail) {}

    @SuppressWarnings("unchecked")
    private List<Entry> audited() {
        return mockingDetails(audit).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("record"))
                .map(call -> {
                    Object[] args = call.getArguments();
                    assertThat(args[0]).isEqualTo(ORG);
                    assertThat(args[5]).isEqualTo("succeeded");
                    return new Entry(
                            (Actor) args[1],
                            (String) args[2],
                            (String) args[3],
                            (String) args[4],
                            (Map<String, Object>) args[6]);
                })
                .toList();
    }

    private Entry onlyEntry(String action) {
        List<Entry> matching =
                audited().stream().filter(entry -> entry.action().equals(action)).toList();
        assertThat(matching).as("audit entries for " + action).hasSize(1);
        return matching.get(0);
    }

    private static void assertDenied(Runnable call) {
        ApiException denied = catchThrowableOfType(call::run, ApiException.class);
        assertThat(denied).as("expected the change to be refused").isNotNull();
        assertThat(denied.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(denied.details()).containsEntry("requiredPermission", Permission.Codes.TASK_CANCEL);
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
        schedule.setRequestedBy(ownerId);
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
            stored(existing);

            Schedule updated = service.update(ORG, existing.getId(), "New name", null, null, null, owner);

            assertThat(updated.getName()).isEqualTo("New name");
            assertThat(updated.getCron()).isEqualTo("0 0 9 * * *");
            assertThat(updated.getNextRunAt()).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
        }

        @Test
        @DisplayName("a new phrase recomputes cron, description and the next run")
        void reparsesOnNewText() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            stored(existing);

            Schedule updated = service.update(ORG, existing.getId(), null, null, null, "every hour", owner);

            assertThat(updated.getCron()).isEqualTo("0 0 * * * *");
            assertThat(updated.getDescription()).isEqualTo("Every hour");
        }

        @Test
        @DisplayName("not found raises a 404")
        void missingSchedule() {
            when(schedules.findByIdAndOrgId(any(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(ORG, UUID.randomUUID(), "x", null, null, null, owner))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("the audit entry names what changed, the previous agent and a fingerprint of the instruction")
        void auditsChangedFields() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            stored(existing);
            UUID previousAgent = existing.getAgentId();
            Agent other = new Agent();
            other.setId(UUID.randomUUID());
            other.setOrgId(ORG);
            other.setStatus("active");
            when(agents.findByIdAndOrgId(other.getId(), ORG)).thenReturn(Optional.of(other));

            service.update(ORG, existing.getId(), null, other.getId(), "Summarise the month", null, owner);

            Entry entry = onlyEntry("schedule.update");
            assertThat(entry.actor()).isEqualTo(owner);
            assertThat(entry.resourceType()).isEqualTo("schedule");
            assertThat(entry.resourceId()).isEqualTo(existing.getId().toString());
            assertThat(entry.detail())
                    .containsEntry("changed", List.of("agentId", "instruction"))
                    .containsEntry("agentId", other.getId().toString())
                    .containsEntry("previousAgentId", previousAgent.toString())
                    .containsEntry("instructionSha256", ScheduleService.sha256("Summarise the month"))
                    .containsEntry("previousInstructionSha256", ScheduleService.sha256("Summarise the week"));
            // The owner edited their own schedule, so it still runs as them.
            assertThat(existing.getRequestedBy()).isEqualTo(ownerId);
            assertThat(audited()).noneMatch(e -> e.action().equals("schedule.owner_change"));
        }

        @Test
        @DisplayName("an edit that changes nothing writes no audit entry")
        void noChangeNoAudit() {
            Schedule existing = schedule("recurring");
            stored(existing);

            service.update(ORG, existing.getId(), existing.getName(), null, existing.getInstruction(), null, owner);

            assertThat(audited()).isEmpty();
        }
    }

    @Nested
    @DisplayName("who may change a schedule")
    class WhoMayChange {

        @Test
        @DisplayName("an employee gets 403 pausing or deleting another person's schedule, and nothing changes")
        void employeeCannotPauseOrDeleteAnothersSchedule() {
            Schedule theirs = schedule("recurring");
            theirs.setCron("0 0 9 * * *");
            stored(theirs);

            assertDenied(() -> service.pause(ORG, theirs.getId(), employee));
            assertDenied(() -> service.delete(ORG, theirs.getId(), employee));

            assertThat(theirs.isEnabled()).isTrue();
            verify(schedules, never()).save(any());
            verify(schedules, never()).delete(any());
            assertThat(audited()).isEmpty();
        }

        @Test
        @DisplayName("an employee gets 403 editing, resuming or running another person's schedule")
        void employeeCannotEditResumeOrRunAnothersSchedule() {
            Schedule theirs = schedule("recurring");
            theirs.setCron("0 0 9 * * *");
            theirs.setEnabled(false);
            stored(theirs);

            assertDenied(() -> service.update(ORG, theirs.getId(), null, null, "Email everyone", null, employee));
            assertDenied(() -> service.resume(ORG, theirs.getId(), employee));
            assertDenied(() -> service.runNow(ORG, theirs.getId(), employee));

            assertThat(theirs.getInstruction()).isEqualTo("Summarise the week");
            assertThat(theirs.getRequestedBy()).isEqualTo(ownerId);
            verify(goalService, never()).createGoal(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("the owner can pause, resume, edit, run and delete their own schedule")
        void ownerManagesOwnSchedule() {
            Schedule mine = schedule("recurring");
            mine.setCron("0 0 9 * * *");
            stored(mine);
            when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(startedGoal());

            service.pause(ORG, mine.getId(), owner);
            assertThat(mine.isEnabled()).isFalse();
            service.resume(ORG, mine.getId(), owner);
            assertThat(mine.isEnabled()).isTrue();
            service.update(ORG, mine.getId(), null, null, "Summarise the fortnight", null, owner);
            assertThat(mine.getInstruction()).isEqualTo("Summarise the fortnight");
            service.runNow(ORG, mine.getId(), owner);
            service.delete(ORG, mine.getId(), owner);

            verify(schedules).delete(mine);
            assertThat(mine.getRequestedBy()).isEqualTo(ownerId);
        }

        @Test
        @DisplayName("a manager can manage anyone's schedule, and becomes its owner by editing the instruction")
        void managerTakesOverByEditingInstruction() {
            Schedule theirs = schedule("recurring");
            theirs.setCron("0 0 9 * * *");
            stored(theirs);

            service.pause(ORG, theirs.getId(), manager);
            service.resume(ORG, theirs.getId(), manager);
            assertThat(theirs.getRequestedBy()).as("pausing and resuming keep the owner").isEqualTo(ownerId);

            service.update(ORG, theirs.getId(), null, null, "Summarise the week for the board", null, manager);

            assertThat(theirs.getRequestedBy()).isEqualTo(managerId);
            Entry update = onlyEntry("schedule.update");
            assertThat(update.detail())
                    .containsEntry("previousOwner", ownerId.toString())
                    .containsEntry("owner", managerId.toString());
            Entry ownerChange = onlyEntry("schedule.owner_change");
            assertThat(ownerChange.actor()).isEqualTo(manager);
            assertThat(ownerChange.detail())
                    .containsEntry("previousOwner", ownerId.toString())
                    .containsEntry("owner", managerId.toString())
                    .containsEntry("via", "edit");
        }

        @Test
        @DisplayName("a manager renaming or re-timing someone's schedule leaves the owner as they were")
        void managerRenameKeepsOwner() {
            Schedule theirs = schedule("recurring");
            theirs.setCron("0 0 9 * * *");
            stored(theirs);

            service.update(ORG, theirs.getId(), "Board digest", null, null, "every hour", manager);

            assertThat(theirs.getRequestedBy()).isEqualTo(ownerId);
            assertThat(audited()).noneMatch(e -> e.action().equals("schedule.owner_change"));
        }

        @Test
        @DisplayName("a schedule with no owner on record needs task:cancel")
        void legacyScheduleNeedsTaskCancel() {
            Schedule legacy = schedule("recurring");
            legacy.setCron("0 0 9 * * *");
            legacy.setRequestedBy(null);
            stored(legacy);

            assertDenied(() -> service.pause(ORG, legacy.getId(), employee));
            service.pause(ORG, legacy.getId(), manager);

            assertThat(legacy.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("no actor at all is refused")
        void noActorRefused() {
            Schedule mine = schedule("recurring");
            assertDenied(() -> service.requireCanManage(mine, null));
        }

        @Test
        @DisplayName("handing a schedule to someone else needs task:cancel, and is audited as an owner change")
        void changeOwner() {
            Schedule theirs = schedule("recurring");
            stored(theirs);
            UUID newOwner = UUID.randomUUID();

            ApiException denied = catchThrowableOfType(
                    () -> service.changeOwner(ORG, theirs.getId(), newOwner, owner), ApiException.class);
            assertThat(denied.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
            assertThat(theirs.getRequestedBy()).isEqualTo(ownerId);

            service.changeOwner(ORG, theirs.getId(), newOwner, manager);

            assertThat(theirs.getRequestedBy()).isEqualTo(newOwner);
            Entry entry = onlyEntry("schedule.owner_change");
            assertThat(entry.detail())
                    .containsEntry("previousOwner", ownerId.toString())
                    .containsEntry("owner", newOwner.toString())
                    .containsEntry("via", "transfer");
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
            stored(existing);

            Schedule paused = service.pause(ORG, existing.getId(), owner);

            assertThat(paused.isEnabled()).isFalse();
            assertThat(paused.getPausedReason()).isNull();
        }

        @Test
        @DisplayName("pause without an explicit actor uses the one in the request context")
        void pauseReadsRequestContext() {
            Schedule existing = schedule("recurring");
            stored(existing);
            RequestContext.setActor(employee);

            assertDenied(() -> service.pause(ORG, existing.getId()));

            RequestContext.setActor(owner);
            assertThat(service.pause(ORG, existing.getId()).isEnabled()).isFalse();
        }

        @Test
        @DisplayName("resume re-enables, clears failures and the pause note, and recomputes the next run")
        void resumeResets() {
            Schedule existing = schedule("recurring");
            existing.setCron("0 0 9 * * *");
            existing.setEnabled(false);
            existing.setConsecutiveFailures(3);
            existing.setPausedReason("Paused after 3 failed runs in a row.");
            stored(existing);

            Schedule resumed = service.resume(ORG, existing.getId(), owner);

            assertThat(resumed.isEnabled()).isTrue();
            assertThat(resumed.getConsecutiveFailures()).isZero();
            assertThat(resumed.getPausedReason()).isNull();
            assertThat(resumed.getNextRunAt()).isNotNull();
        }

        @Test
        @DisplayName("Stop everything's pause reaches a schedule whoever owns it, and says why")
        void internalPauseIgnoresOwnership() {
            Schedule theirs = schedule("recurring");
            stored(theirs);
            // The person pressing Stop everything holds run:cancel and task:create, not task:cancel.
            RequestContext.setActor(employee);

            service.pauseInternal(ORG, theirs.getId(), ScheduleService.STOPPED_EVERYTHING_REASON);

            assertThat(theirs.isEnabled()).isFalse();
            assertThat(theirs.getPausedReason()).isEqualTo(ScheduleService.STOPPED_EVERYTHING_REASON);
            Entry entry = onlyEntry("schedule.pause");
            assertThat(entry.actor()).isEqualTo(employee);
            assertThat(entry.detail()).containsEntry("reason", ScheduleService.STOPPED_EVERYTHING_REASON);
        }

        @Test
        @DisplayName("Stop everything itself is recorded once, with what it stopped")
        void recordsStopAll() {
            service.recordStopAll(ORG, manager, Map.of("schedulesPaused", 2, "runsCancelled", 1));

            Entry entry = onlyEntry("orchestrator.stop_all");
            assertThat(entry.actor()).isEqualTo(manager);
            assertThat(entry.resourceType()).isEqualTo("workspace");
            assertThat(entry.detail()).containsEntry("schedulesPaused", 2).containsEntry("runsCancelled", 1);
        }
    }

    @Nested
    @DisplayName("one-off schedules")
    class OneOff {

        private Schedule fired() {
            Schedule done = schedule("once");
            done.setRunAt(Instant.now().minus(1, ChronoUnit.HOURS));
            done.setDescription("Once, an hour ago");
            done.setEnabled(false);
            done.setNextRunAt(null);
            done.setLastRunAt(done.getRunAt());
            stored(done);
            return done;
        }

        @Test
        @DisplayName("a one-off the sweep already fired reads as completed; a paused one does not")
        void completedIsDerived() {
            assertThat(ScheduleService.isCompleted(fired())).isTrue();

            Schedule paused = schedule("once");
            paused.setRunAt(Instant.now().plus(1, ChronoUnit.DAYS));
            paused.setNextRunAt(paused.getRunAt());
            paused.setEnabled(false);
            assertThat(ScheduleService.isCompleted(paused)).isFalse();

            Schedule recurring = schedule("recurring");
            recurring.setEnabled(false);
            assertThat(ScheduleService.isCompleted(recurring)).isFalse();
        }

        @Test
        @DisplayName("resuming a one-off whose time has passed is a validation error, and fires nothing")
        void resumingPastOneOffIsRefused() {
            Schedule done = fired();

            ApiException refused =
                    catchThrowableOfType(() -> service.resume(ORG, done.getId(), owner), ApiException.class);

            assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.details())
                    .containsEntry("field", "when")
                    .containsEntry("problem", "This one-off time has passed; edit it to pick a new time.");
            assertThat(done.isEnabled()).isFalse();
            assertThat(done.getNextRunAt()).isNull();
        }

        @Test
        @DisplayName("a one-off paused before it fired is refused too, once its time has gone by")
        void resumingStalePausedOneOffIsRefused() {
            Schedule stale = schedule("once");
            stale.setRunAt(Instant.now().minus(5, ChronoUnit.MINUTES));
            stale.setNextRunAt(stale.getRunAt());
            stale.setEnabled(false);
            stored(stale);

            ApiException refused =
                    catchThrowableOfType(() -> service.resume(ORG, stale.getId(), owner), ApiException.class);

            assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(stale.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("editing a done one-off to a time still ahead turns it back on")
        void editingDoneOneOffToFutureEnablesIt() {
            Schedule done = fired();
            done.setPausedReason("Paused after 3 failed runs in a row.");

            Schedule updated = service.update(ORG, done.getId(), null, null, null, "in 2 hours", owner);

            assertThat(updated.getKind()).isEqualTo("once");
            assertThat(updated.isEnabled()).isTrue();
            assertThat(updated.getPausedReason()).isNull();
            assertThat(updated.getNextRunAt()).isAfter(Instant.now());
            assertThat(onlyEntry("schedule.update").detail()).containsEntry("reactivated", true);
        }

        @Test
        @DisplayName("a schedule paused on purpose stays paused whatever is edited")
        void editingDeliberatelyPausedScheduleKeepsItPaused() {
            Schedule paused = schedule("once");
            paused.setRunAt(Instant.now().plus(1, ChronoUnit.DAYS));
            paused.setNextRunAt(paused.getRunAt());
            paused.setEnabled(false);
            stored(paused);

            Schedule updated = service.update(ORG, paused.getId(), null, null, null, "in 2 hours", owner);

            assertThat(updated.isEnabled()).isFalse();
            assertThat(updated.getNextRunAt()).isAfter(Instant.now());
        }
    }

    @Nested
    @DisplayName("when an owner leaves the workspace")
    class OwnerRemoved {

        private final Actor identity = new Actor(
                "identity",
                Actor.Kind.SYSTEM,
                ORG.toString(),
                null,
                Set.of(),
                0L,
                UUID.randomUUID().toString(),
                null,
                null,
                Map.of());

        @Test
        @DisplayName("pauses every enabled schedule they own with a note, counts them, and audits each as the system")
        void pausesTheirSchedules() {
            Schedule active = schedule("recurring");
            Schedule alreadyPaused = schedule("recurring");
            alreadyPaused.setEnabled(false);
            Schedule done = schedule("once");
            done.setEnabled(false);
            done.setNextRunAt(null);
            when(schedules.findByOrgIdAndRequestedBy(ORG, ownerId))
                    .thenReturn(List.of(active, alreadyPaused, done));

            int paused = service.pauseForRemovedOwner(ORG, ownerId, identity);

            assertThat(paused).isEqualTo(1);
            assertThat(active.isEnabled()).isFalse();
            assertThat(active.getPausedReason()).isEqualTo(ScheduleService.OWNER_REMOVED_REASON);
            // Already paused: stays paused, and now says why a resume will need a new owner first.
            assertThat(alreadyPaused.getPausedReason()).isEqualTo(ScheduleService.OWNER_REMOVED_REASON);
            // A one-off that already ran will not fire again, so it is left as it was.
            assertThat(done.getPausedReason()).isNull();
            verify(schedules, never()).save(done);

            Entry entry = onlyEntry("schedule.pause");
            assertThat(entry.actor().kind()).isEqualTo(Actor.Kind.SYSTEM);
            assertThat(entry.resourceId()).isEqualTo(active.getId().toString());
            assertThat(entry.detail()).containsEntry("reason", ScheduleService.OWNER_REMOVED_REASON);
        }

        @Test
        @DisplayName("only reads the departed person's schedules")
        void readsOnlyThatPerson() {
            when(schedules.findByOrgIdAndRequestedBy(ORG, ownerId)).thenReturn(List.of());

            assertThat(service.pauseForRemovedOwner(ORG, ownerId, identity)).isZero();

            verify(schedules).findByOrgIdAndRequestedBy(ORG, ownerId);
            verify(schedules, never()).findByOrgIdOrderByNameAsc(any());
            verify(schedules, never()).save(any());
        }

        @Test
        @DisplayName("somebody else cannot resume it in the former member's name until it is transferred")
        void resumeNeedsTransferFirst() {
            Schedule orphaned = schedule("recurring");
            orphaned.setCron("0 0 9 * * *");
            orphaned.setEnabled(false);
            orphaned.setPausedReason(ScheduleService.OWNER_REMOVED_REASON);
            stored(orphaned);

            ApiException refused =
                    catchThrowableOfType(() -> service.resume(ORG, orphaned.getId(), manager), ApiException.class);
            assertThat(refused.code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(orphaned.isEnabled()).isFalse();

            service.changeOwner(ORG, orphaned.getId(), managerId, manager);
            assertThat(orphaned.getPausedReason()).isNull();
            service.resume(ORG, orphaned.getId(), manager);

            assertThat(orphaned.isEnabled()).isTrue();
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
            stored(existing);
            Goal goal = startedGoal();
            when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);

            Schedule result = service.runNow(ORG, existing.getId(), owner);

            assertThat(result.getLastGoalId()).isEqualTo(goal.getId());
            assertThat(result.getLastRunAt()).isNotNull();
            assertThat(result.getNextRunAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
            assertThat(result.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("fires as the person who pressed it, not as the schedule's owner")
        void firesAsCaller() {
            Schedule theirs = schedule("recurring");
            stored(theirs);
            List<Actor> seen = new ArrayList<>();
            List<UUID> requesters = new ArrayList<>();
            when(goalService.createGoal(any(), any(), anyBoolean())).thenAnswer(call -> {
                seen.add(RequestContext.actor().orElse(null));
                requesters.add(call.<GoalService.NewGoal>getArgument(1).requestedBy());
                return startedGoal();
            });

            service.runNow(ORG, theirs.getId(), manager);

            assertThat(seen).containsExactly(manager);
            assertThat(requesters).containsExactly(managerId);
            assertThat(theirs.getRequestedBy()).as("running it does not take it over").isEqualTo(ownerId);
            Entry entry = onlyEntry("schedule.run_now");
            assertThat(entry.actor()).isEqualTo(manager);
            assertThat(entry.detail())
                    .containsEntry("scheduleId", theirs.getId().toString())
                    .containsEntry("triggeredBy", managerId.toString())
                    .containsEntry("triggeredByKind", "USER")
                    .containsEntry("owner", ownerId.toString())
                    .containsKey("goalId");
        }
    }

    @Nested
    @DisplayName("the audit trail")
    class AuditTrail {

        @Test
        @DisplayName("every change writes an entry, as whoever made it, after it commits")
        void everyChangeIsAudited() {
            RequestContext.setActor(owner);
            Schedule created = service.create(ORG, "Weekly digest", agentId, "Summarise the week", "daily at 9am");
            stored(created);
            when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(startedGoal());

            service.update(ORG, created.getId(), "Weekly board digest", null, null, null, owner);
            service.pause(ORG, created.getId(), owner);
            service.resume(ORG, created.getId(), owner);
            service.runNow(ORG, created.getId(), owner);
            service.changeOwner(ORG, created.getId(), managerId, manager);
            service.delete(ORG, created.getId(), manager);

            assertThat(audited())
                    .extracting(Entry::action)
                    .containsExactly(
                            "schedule.create",
                            "schedule.update",
                            "schedule.pause",
                            "schedule.resume",
                            "schedule.run_now",
                            "schedule.owner_change",
                            "schedule.delete");
            assertThat(audited()).allSatisfy(entry -> {
                assertThat(entry.resourceType()).isEqualTo("schedule");
                assertThat(entry.resourceId()).isEqualTo(created.getId().toString());
            });
            Entry create = onlyEntry("schedule.create");
            assertThat(create.actor()).isEqualTo(owner);
            assertThat(create.detail())
                    .containsEntry("agentId", agentId.toString())
                    .containsEntry("instructionSha256", ScheduleService.sha256("Summarise the week"))
                    .doesNotContainValue("Summarise the week");
            assertThat(onlyEntry("schedule.delete").actor()).isEqualTo(manager);
        }

        @Test
        @DisplayName("a refused change writes nothing")
        void refusedChangeIsNotAudited() {
            Schedule theirs = schedule("recurring");
            stored(theirs);

            assertDenied(() -> service.update(ORG, theirs.getId(), "Mine now", null, null, null, employee));

            assertThat(audited()).isEmpty();
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        @DisplayName("reads one page of a schedule's goals, after checking it is in this workspace")
        void readsOnePage() {
            Schedule existing = schedule("recurring");
            stored(existing);
            PageRequest page = PageRequest.of(1, 20);
            when(scheduleGoals.findByOrgIdAndScheduleIdOrderByCreatedAtDesc(ORG, existing.getId(), page))
                    .thenReturn(new PageImpl<>(List.of(startedGoal()), page, 21));

            assertThat(service.runs(ORG, existing.getId(), page).getContent()).hasSize(1);

            when(schedules.findByIdAndOrgId(any(), eq(ORG))).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.runs(ORG, UUID.randomUUID(), page))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
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
        @DisplayName("fires as the schedule's owner, or the platform when it has none")
        void firesAsOwner() {
            Schedule withOwner = schedule("recurring");
            withOwner.setCron("0 0 9 * * *");
            withOwner.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            Schedule legacy = schedule("recurring");
            legacy.setCron("0 0 9 * * *");
            legacy.setNextRunAt(Instant.parse("2026-09-29T00:00:00Z"));
            legacy.setRequestedBy(null);
            when(schedules.findDue(any(), any())).thenReturn(List.of(withOwner, legacy));
            List<Actor> seen = new ArrayList<>();
            List<UUID> requesters = new ArrayList<>();
            when(goalService.createGoal(any(), any(), anyBoolean())).thenAnswer(call -> {
                seen.add(RequestContext.actor().orElse(null));
                requesters.add(call.<GoalService.NewGoal>getArgument(1).requestedBy());
                return startedGoal();
            });

            service.sweepDue(50);

            assertThat(seen).hasSize(2);
            assertThat(seen.get(0).id()).isEqualTo(ownerId.toString());
            assertThat(seen.get(0).kind()).isEqualTo(Actor.Kind.USER);
            assertThat(seen.get(1).isSystem()).isTrue();
            assertThat(requesters).containsExactly(ownerId, null);
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
