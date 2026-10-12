// @find: tests for takeover service, take over browser permissions, single holder, hand back note, labels not typed values, audit entries, auto release after ceiling, run stopped during takeover
// @what: Unit tests for TakeoverService: who may take over, one holder at a time, control only once the agent has stopped, actions only by the holder, the hand-back note and audit entries never carrying typed text, refused actions audited, control returning after 30 minutes, holds ended with the run, and steps given back kept.
package os.aiworkforce.orchestrator.takeover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * A person takes over a run's web browser: only someone who can stop runs or who asked for the work
 * may, only one person holds it, and what they type never leaves the page.
 */
class TakeoverServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID TASK = UUID.fromString("00000000-0000-7000-8000-0000000000b1");
    private static final UUID GOAL = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    private static final Instant START = Instant.parse("2026-10-12T09:00:00Z");

    private final AtomicReference<RunTakeover> stored = new AtomicReference<>();
    private RunTakeovers repository;
    private Runs runs;
    private Tasks tasks;
    private Goals goals;
    private TakeoverBrowser browser;
    private AuditClient audit;
    private TakeoverTestKit.MovableClock clock;
    private List<Duration> pauses;
    private TakeoverService service;
    private Run run;

    private final Actor manager = person("u-manager", "run:read", "run:cancel");
    private final Actor starter = person("u-starter", "run:read");
    private final Actor requester = person(REQUESTER.toString(), "run:read");
    private final Actor viewer = person("u-viewer", "run:read");

    private static Actor person(String id, String... permissions) {
        return Actor.user(id, ORG.toString(), "role", Set.of(permissions), 0L);
    }

    @BeforeEach
    void setUp() {
        repository = mock(RunTakeovers.class);
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(repository.findByRunIdAndOrgId(any(), any())).thenAnswer(call -> Optional.ofNullable(stored.get())
                .filter(takeover -> takeover.getOrgId().equals(call.getArgument(1))));
        when(repository.save(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return call.getArgument(0);
        });
        runs = mock(Runs.class);
        tasks = mock(Tasks.class);
        goals = mock(Goals.class);
        browser = mock(TakeoverBrowser.class);
        when(browser.hasPage(RUN)).thenReturn(true);
        audit = mock(AuditClient.class);
        clock = new TakeoverTestKit.MovableClock(START);
        pauses = new ArrayList<>();
        service = TakeoverTestKit.service(
                repository, runs, tasks, goals, browser, audit, clock, pauses::add, Duration.ofSeconds(1));
        service.useAuditor(Runnable::run);

        run = new Run();
        run.setId(RUN);
        run.setOrgId(ORG);
        run.setAgentId(UUID.randomUUID());
        run.setStatus("running");
        run.setTaskId(TASK);
        ReflectionTestUtils.setField(run, "createdBy", "u-starter");
        when(runs.findByIdAndOrgId(RUN, ORG)).thenReturn(Optional.of(run));
        Task task = new Task();
        task.setOrgId(ORG);
        task.setGoalId(GOAL);
        when(tasks.findByIdAndOrgId(TASK, ORG)).thenReturn(Optional.of(task));
        Goal goal = new Goal();
        goal.setOrgId(ORG);
        goal.setRequestedBy(REQUESTER);
        when(goals.findById(GOAL)).thenReturn(Optional.of(goal));
    }

    @Test
    @DisplayName("someone who can stop runs, the person who started it and the person who asked may take over; nobody else")
    void permissions() {
        assertThat(service.mayControl(manager, run)).isTrue();
        assertThat(service.mayControl(starter, run)).isTrue();
        assertThat(service.mayControl(requester, run)).isTrue();
        assertThat(service.mayControl(viewer, run)).isFalse();
        assertThat(service.view(ORG, RUN, viewer).canTakeOver()).isFalse();
        assertThat(service.view(ORG, RUN, starter).canTakeOver()).isTrue();

        assertThatThrownBy(() -> service.start(ORG, RUN, viewer))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        verify(repository, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), anyString(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("only a running run with a page open can be taken over, and a run in another workspace is not found")
    void onlyWorkingRunsWithAPage() {
        run.setStatus("waiting_approval");
        assertThatThrownBy(() -> service.start(ORG, RUN, manager)).hasMessage(TakeoverService.NOT_WORKING);
        run.setStatus("running");
        when(browser.hasPage(RUN)).thenReturn(false);
        assertThatThrownBy(() -> service.start(ORG, RUN, manager)).hasMessage(TakeoverService.NO_PAGE);
        UUID otherOrg = UUID.randomUUID();
        assertThatThrownBy(() -> service.view(otherOrg, RUN, manager))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("one person holds control: a second is refused and sees who has it, the holder asking again changes nothing")
    void singleHolder() {
        TakeoverService.TakeoverView mine = service.start(ORG, RUN, starter);

        // Asked for, not yet given: the agent may be mid-step on the same page.
        assertThat(mine.starting()).isTrue();
        assertThat(mine.active()).isFalse();
        assertThat(mine.status()).isEqualTo(RunTakeover.STARTING);
        assertThat(mine.mine()).isTrue();
        assertThat(mine.endsAt()).isNull();
        verify(browser).hold(RUN, true);
        verify(audit).record(eq(ORG), eq(starter), eq("run.takeover.start"), eq("run"), eq(RUN.toString()), eq("succeeded"), anyMap());

        assertThatThrownBy(() -> service.start(ORG, RUN, manager)).hasMessage(TakeoverService.SOMEONE_ELSE);
        TakeoverService.TakeoverView theirs = service.view(ORG, RUN, manager);
        assertThat(theirs.starting()).isTrue();
        assertThat(theirs.takenBy()).isEqualTo("u-starter");
        assertThat(theirs.mine()).isFalse();
        assertThat(theirs.canTakeOver()).isFalse();
        assertThat(theirs.canRelease()).isTrue();
        assertThat(service.view(ORG, RUN, requester).canRelease()).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.start(ORG, RUN, starter).takenAt()).isEqualTo(START);
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("control is given only once the agent's loop stops at a step boundary; until then a person's action is refused")
    void controlOnlyOnceTheAgentStops() {
        service.start(ORG, RUN, starter);
        TakeoverActions.ActionRequest enter =
                new TakeoverActions.ActionRequest("key", null, null, null, null, null, "Enter", null, null);

        assertThatThrownBy(() -> service.act(ORG, RUN, starter, enter)).hasMessage(TakeoverService.AGENT_FINISHING);
        verify(browser, never()).act(any(), anyString(), anyMap());

        clock.advance(Duration.ofMinutes(2));
        List<String> began = new ArrayList<>();
        TakeoverService handing = TakeoverTestKit.service(repository, runs, tasks, goals, browser, audit, clock, pause -> {
            assertThat(stored.get().getStatus()).isEqualTo(RunTakeover.ACTIVE);
            // While the loop waits, the person acts.
            when(browser.act(eq(RUN), eq("key"), anyMap()))
                    .thenReturn(new TakeoverBrowser.Answer(true, null, "https://example.com/", "Example", "", ""));
            service.act(ORG, RUN, starter, enter);
            stored.get().close(RunTakeover.RELEASED, "u-starter", clock.instant());
        }, Duration.ofSeconds(1));

        TakeoverService.HandBack back = handing.awaitHandBack(RUN, () -> "running", () -> began.add("waiting"));

        assertThat(began).containsExactly("waiting");
        assertThat(back.pageChanged()).isTrue();
        // The ceiling counts from when the person got control, not from when they asked.
        assertThat(stored.get().getTakenAt()).isEqualTo(START.plus(Duration.ofMinutes(2)));
        verify(browser).personActed(RUN, List.of());
    }

    @Test
    @DisplayName("a request withdrawn before the agent stopped, with no note, leaves nothing to tell the agent")
    void withdrawnBeforeControl() {
        service.start(ORG, RUN, starter);

        TakeoverService.TakeoverView released = service.release(ORG, RUN, starter, null);

        assertThat(released.status()).isEqualTo(RunTakeover.RELEASED);
        assertThat(released.releasedBy()).isEqualTo("u-starter");
        assertThat(service.awaitHandBack(RUN, () -> "running", () -> {})).isEqualTo(TakeoverService.HandBack.NONE);
    }

    @Test
    @DisplayName("only the holder acts; typing reaches the page, while the summary and the audit keep the field label and the action type only")
    void actionsKeepLabelsNotText() {
        takeOver(starter);
        when(browser.act(eq(RUN), eq("type"), anyMap()))
                .thenReturn(new TakeoverBrowser.Answer(true, null, "https://shop.example.com/sign-in", "Sign in", "/9j/FR", "Password"));
        TakeoverActions.ActionRequest typing =
                new TakeoverActions.ActionRequest("type", null, null, null, null, "hunter2-correct", null, null, null);

        assertThatThrownBy(() -> service.act(ORG, RUN, manager, typing)).hasMessage(TakeoverService.SOMEONE_ELSE);

        TakeoverService.ActionResult result = service.act(ORG, RUN, starter, typing);

        assertThat(result.frame()).isEqualTo("/9j/FR");
        ArgumentCaptor<Map<String, Object>> sent = mapCaptor();
        verify(browser).act(eq(RUN), eq("type"), sent.capture());
        assertThat(sent.getValue()).containsEntry("text", "hunter2-correct");
        assertThat(stored.get().getActions().get("fields")).isEqualTo(List.of("Password"));
        assertThat(stored.get().getActions().toString()).doesNotContain("hunter2");
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(starter), eq("run.takeover.action"), eq("run"), eq(RUN.toString()), eq("succeeded"),
                audited.capture());
        assertThat(audited.getValue()).isEqualTo(Map.of("type", "type"));
    }

    @Test
    @DisplayName("an address the browser refuses is refused with its sentence, kept out of the summary, and audited as denied without the address")
    void refusedNavigate() {
        takeOver(starter);
        String privateAddress = "That address points inside a private network or at this server, which the browser may not open.";
        when(browser.act(eq(RUN), eq("navigate"), anyMap())).thenReturn(TakeoverBrowser.Answer.refused(privateAddress));

        assertThatThrownBy(() -> service.act(ORG, RUN, starter,
                        new TakeoverActions.ActionRequest("navigate", null, null, null, null, null, null, null, "http://169.254.169.254/")))
                .hasMessage(privateAddress);

        assertThat(TakeoverActions.total(stored.get().getActions())).isZero();
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(starter), eq("run.takeover.action"), eq("run"), eq(RUN.toString()), eq("denied"),
                audited.capture());
        assertThat(audited.getValue()).containsEntry("type", "navigate").containsEntry("reason", privateAddress);
        assertThat(audited.getValue().toString()).doesNotContain("169.254");
    }

    @Test
    @DisplayName("a refused typing step is audited by its type only, never with what was typed")
    void refusedTypingAuditedWithoutText() {
        takeOver(starter);
        when(browser.act(eq(RUN), eq("type"), anyMap()))
                .thenReturn(TakeoverBrowser.Answer.refused("Click the field to type into first."));

        assertThatThrownBy(() -> service.act(ORG, RUN, starter,
                        new TakeoverActions.ActionRequest("type", null, null, null, null, "hunter2-correct", null, null, null)))
                .hasMessage("Click the field to type into first.");

        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(starter), eq("run.takeover.action"), eq("run"), eq(RUN.toString()), eq("denied"),
                audited.capture());
        assertThat(audited.getValue().toString()).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("handing back keeps the note for the agent, audits without it, and the agent is told labels, not typed values")
    void handBackNote() {
        takeOver(starter);
        when(browser.act(eq(RUN), eq("type"), anyMap()))
                .thenReturn(new TakeoverBrowser.Answer(true, null, "https://shop.example.com/sign-in?code=xyz", "Sign in", "", "Password"));
        service.act(ORG, RUN, starter,
                new TakeoverActions.ActionRequest("type", null, null, null, null, "hunter2-correct", null, null, null));

        TakeoverService.TakeoverView released = service.release(ORG, RUN, starter, "  Signed in.\nPlease carry on.  ");

        assertThat(released.active()).isFalse();
        assertThat(released.status()).isEqualTo(RunTakeover.RELEASED);
        verify(browser).hold(RUN, false);
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(starter), eq("run.takeover.release"), eq("run"), eq(RUN.toString()), eq("succeeded"),
                audited.capture());
        assertThat(audited.getValue()).containsEntry("withNote", true).containsEntry("actions", 1);
        assertThat(audited.getValue().toString()).doesNotContain("Signed in").doesNotContain("hunter2");

        TakeoverService.HandBack back = service.awaitHandBack(RUN, () -> "running", () -> {});

        assertThat(back.happened()).isTrue();
        assertThat(back.pageChanged()).isTrue();
        assertThat(back.note())
                .contains("\"Password\"")
                .contains("Their note for you: \"Signed in. Please carry on.\"")
                .contains("https://shop.example.com/sign-in")
                .doesNotContain("hunter2")
                .doesNotContain("code=xyz");
        assertThat(back.summary()).contains("1 typing step");
        verify(browser, org.mockito.Mockito.atLeastOnce()).personActed(RUN, List.of("Password"));
        // Not marked told until the loop has told the agent: a loop that parks first tells it next time.
        assertThat(stored.get().getDeliveredAt()).isNull();
        assertThat(service.awaitHandBack(RUN, () -> "running", () -> {}).note()).isEqualTo(back.note());

        service.markTold(RUN);

        assertThat(stored.get().getDeliveredAt()).isNotNull();
        assertThat(stored.get().getNote()).isNull();
        assertThat(service.awaitHandBack(RUN, () -> "running", () -> {})).isEqualTo(TakeoverService.HandBack.NONE);
    }

    @Test
    @DisplayName("the agent waits while a person holds control and resumes when it is handed back")
    void waitsUntilHandedBack() {
        takeOver(starter);
        List<String> waited = new ArrayList<>();
        TakeoverService handing = TakeoverTestKit.service(repository, runs, tasks, goals, browser, audit, clock, pause -> {
            clock.advance(pause);
            if (waited.size() == 2) {
                stored.get().close(RunTakeover.RELEASED, "u-starter", clock.instant());
            }
            waited.add("paused");
        }, Duration.ofSeconds(1));

        TakeoverService.HandBack back = handing.awaitHandBack(RUN, () -> "running", () -> waited.add("began"));

        assertThat(waited).containsExactly("began", "paused", "paused");
        assertThat(back.happened()).isTrue();
        assertThat(back.waited()).isEqualTo(Duration.ofSeconds(2));
        assertThat(back.pageChanged()).isFalse();
        assertThat(back.note()).contains("They did not change anything on the page.");
    }

    @Test
    @DisplayName("nobody handing back within 30 minutes returns control automatically, audited as automatic")
    void ceiling() {
        takeOver(starter);
        TakeoverService patient = TakeoverTestKit.service(
                repository, runs, tasks, goals, browser, audit, clock, clock::advance, Duration.ofMinutes(10));

        TakeoverService.HandBack back = patient.awaitHandBack(RUN, () -> "running", () -> {});

        assertThat(back.waited()).isEqualTo(TakeoverService.CEILING);
        assertThat(back.note()).contains("control returned to you automatically");
        assertThat(stored.get().getStatus()).isEqualTo(RunTakeover.EXPIRED);
        verify(browser).hold(RUN, false);
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(Actor.SYSTEM), eq("run.takeover.release"), eq("run"), eq(RUN.toString()),
                eq("succeeded"), audited.capture());
        assertThat(audited.getValue()).containsEntry("automatic", true).containsEntry("reason", "ceiling");
        assertThat(service.view(ORG, RUN, starter).active()).isFalse();
    }

    @Test
    @DisplayName("a run stopped while a person holds its browser ends the hold and the wait, with nothing to tell")
    void runStoppedDuringTakeover() {
        takeOver(starter);

        TakeoverService.HandBack back = service.awaitHandBack(RUN, () -> "cancelled", () -> {});

        assertThat(back.happened()).isTrue();
        assertThat(back.note()).isNull();
        assertThat(stored.get().getStatus()).isEqualTo(RunTakeover.ENDED);
        assertThat(pauses).hasSize(1);
    }

    @Test
    @DisplayName("someone who can stop runs may end another person's hold; the run's requester may not")
    void forcedRelease() {
        takeOver(starter);

        assertThatThrownBy(() -> service.release(ORG, RUN, requester, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(service.release(ORG, RUN, manager, null).active()).isFalse();
        assertThatThrownBy(() -> service.release(ORG, RUN, manager, null)).hasMessage(TakeoverService.NOT_HELD);
        assertThatThrownBy(() -> service.act(ORG, RUN, starter,
                        new TakeoverActions.ActionRequest("key", null, null, null, null, null, "Enter", null, null)))
                .hasMessage(TakeoverService.NOT_HELD);
    }

    @Test
    @DisplayName("a run that ends while a person holds its browser, without the loop waiting, closes the hold as ended and audits it")
    void runEndedWhileHeld() {
        takeOver(starter);
        run.setStatus("completed");

        service.endHold(RUN, "run ended");

        assertThat(stored.get().getStatus()).isEqualTo(RunTakeover.ENDED);
        assertThat(stored.get().getReleasedAt()).isEqualTo(START);
        verify(browser).hold(RUN, false);
        ArgumentCaptor<Map<String, Object>> audited = mapCaptor();
        verify(audit).record(eq(ORG), eq(Actor.SYSTEM), eq("run.takeover.release"), eq("run"), eq(RUN.toString()),
                eq("succeeded"), audited.capture());
        assertThat(audited.getValue()).containsEntry("reason", "run ended");
        TakeoverService.TakeoverView view = service.view(ORG, RUN, starter);
        assertThat(view.status()).isEqualTo(RunTakeover.ENDED);
        assertThat(view.active()).isFalse();
        // Nothing held: a second ending changes nothing and audits nothing more.
        service.endHold(RUN, "run ended");
        verify(audit, times(1)).record(any(), any(), eq("run.takeover.release"), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("a row never closed reads as ended once its run has finished, not as expired")
    void unclosedHoldOnFinishedRunReadsEnded() {
        takeOver(starter);
        run.setStatus("failed");

        assertThat(service.view(ORG, RUN, manager).status()).isEqualTo(RunTakeover.ENDED);
    }

    @Test
    @DisplayName("a database read that fails while the agent waits is tried again; only many failures in a row give up")
    void waitSurvivesPassingDatabaseErrors() {
        takeOver(starter);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        TakeoverService patient = TakeoverTestKit.service(repository, runs, tasks, goals, browser, audit, clock, pause -> {
            clock.advance(pause);
            if (reads.get() == 3) {
                stored.get().close(RunTakeover.RELEASED, "u-starter", clock.instant());
            }
        }, Duration.ofSeconds(1));

        TakeoverService.HandBack back = patient.awaitHandBack(RUN, () -> {
            if (reads.incrementAndGet() <= 2) {
                throw new org.springframework.dao.TransientDataAccessResourceException("connection reset");
            }
            return "running";
        }, () -> {});

        assertThat(back.happened()).isTrue();
        assertThat(back.note()).isNotNull();

        takeOver(starter);
        TakeoverService broken = TakeoverTestKit.service(
                repository, runs, tasks, goals, browser, audit, clock, clock::advance, Duration.ofSeconds(1));
        assertThatThrownBy(() -> broken.awaitHandBack(RUN, () -> {
                    throw new org.springframework.dao.TransientDataAccessResourceException("database down");
                }, () -> {}))
                .hasMessageContaining("database down");
    }

    @Test
    @DisplayName("steps given back are kept on the row, across a new takeover of the same run")
    void stepsGivenBackAreKept() {
        assertThat(service.stepsGivenBack(RUN)).isZero();
        takeOver(starter);

        service.giveStepBack(RUN);
        service.giveStepBack(RUN);
        service.release(ORG, RUN, starter, null);
        service.markTold(RUN);
        service.start(ORG, RUN, manager);

        assertThat(service.stepsGivenBack(RUN)).isEqualTo(2);
    }

    /** Asks for control as {@code person} and gives it, as the agent's loop does when it stops at a boundary. */
    private void takeOver(Actor person) {
        service.start(ORG, RUN, person);
        stored.get().activate(clock.instant());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
