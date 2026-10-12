// @find: take over browser, takeover service, take control of agent browser, hand back to agent, one person has control, agent waits during takeover, auto release after 30 minutes, run_takeovers, Agent desk
// @what: Lets a person take over a run's web browser, act on its page and hand it back, and makes the run wait for them, with every start, action type (done or refused) and release audited.
// @flow: Called by RunTakeoverController (people) and AgentRunner (awaitHandBack at each step boundary, markTold, endHold when its loop leaves or the run ends, the steps given back); uses RunTakeovers, TakeoverBrowser and AuditClient
package os.aiworkforce.orchestrator.takeover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.observability.Redactor;
import os.aiworkforce.platform.rbac.Permission;

/**
 * A person taking over a run's web browser from its agent.
 *
 * <p>Who may: anyone who can stop runs ({@code run:cancel}), or the person who started the run or
 * asked for its goal. Only one person holds a run's browser at a time; the row per run and its
 * version column make a second "Take over" fail cleanly.
 *
 * <p>A request starts as {@link RunTakeover#STARTING}: the agent may be in the middle of a step on
 * the same page, so the person may not act yet. At its next step boundary the agent's loop calls
 * {@link #awaitHandBack}, which makes the takeover {@link RunTakeover#ACTIVE} and parks the loop,
 * polling once a second, until control comes back - handed back, after a 30 minute ceiling, or
 * because the run stopped. The time waited is handed to the loop so it does not count against the
 * run's working time. A run whose loop leaves (it finished, parked for a person, or was stopped)
 * while someone holds or is getting its browser ends that hold ({@link #endHold}), audited.
 *
 * <p>What the person types is sent to the page and nowhere else. The summary of what they did keeps
 * the labels of the fields they typed into, never the text, and that summary is all the agent, the
 * trace and the audit log ever see.
 */
@Service
public class TakeoverService {

    private static final Logger log = LoggerFactory.getLogger(TakeoverService.class);

    /** The longest a person may hold a run's browser before control returns to the agent by itself. */
    public static final Duration CEILING = Duration.ofMinutes(30);

    /** Run statuses in which a person already holding the browser may keep acting on it. */
    static final Set<String> ACTIVE_RUN = Set.of("running", "waiting_approval", "waiting_input");

    // What a person is told when a takeover is refused. The web shows these as they are.
    static final String NOT_ALLOWED =
            "Only the person who started this run, or someone who can stop runs, can take over its web browser.";
    static final String NOT_WORKING = "This run is not working right now, so there is nothing to take over.";
    static final String NO_PAGE = "This run has no web page open, so there is nothing to take over.";
    static final String SOMEONE_ELSE = "Someone else has control of this web browser.";
    static final String NOT_HELD = "Control has already returned to the agent, so nothing was done.";
    static final String AGENT_FINISHING =
            "The agent is finishing the step it was on. You can act on the page as soon as it stops.";
    static final String RELEASE_NOT_ALLOWED =
            "Only the person who has control, or someone who can stop runs, can hand it back.";

    /** Waits; replaced in tests so they need not sleep. */
    @FunctionalInterface
    interface Sleeper {
        /** Pauses the calling (virtual) thread for {@code duration}. */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RunTakeovers takeovers;
    private final Runs runs;
    private final Tasks tasks;
    private final Goals goals;
    private final TakeoverBrowser browser;
    private final AuditClient audit;

    /** Who may read which conversation's work; absent only where a test builds the service by hand. */
    @Autowired(required = false)
    private os.aiworkforce.orchestrator.chat.ConversationAccess access;

    /** Masks secrets in a person's note before the agent reads it; absent only in hand-built tests. */
    @Autowired(required = false)
    private Redactor redactor;

    /** How many database reads in a row may fail while the loop waits before the run is failed. */
    static final int POLL_ERROR_LIMIT = 30;

    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = duration -> Thread.sleep(duration);
    private Duration pollInterval = Duration.ofSeconds(1);
    /*
     * Where an action's audit entry is sent from: its own virtual thread, so a slow audit service
     * never slows a person's click. Carries the request context with it.
     */
    private Executor auditor = Executors.newVirtualThreadPerTaskExecutor();

    /** Creates the service with its repositories, the browser and the audit client. */
    public TakeoverService(
            RunTakeovers takeovers, Runs runs, Tasks tasks, Goals goals, TakeoverBrowser browser, AuditClient audit) {
        this.takeovers = takeovers;
        this.runs = runs;
        this.tasks = tasks;
        this.goals = goals;
        this.browser = browser;
        this.audit = audit;
    }

    /** Test hook: the clock every time is read from. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /** Test hook: how the loop waits between checks. */
    void useSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** Test hook: how long the loop waits between checks (one second in production). */
    void setPollInterval(Duration interval) {
        this.pollInterval = interval;
    }

    /** Test hook: who may read which conversation's work. */
    void useAccess(os.aiworkforce.orchestrator.chat.ConversationAccess access) {
        this.access = access;
    }

    /** Test hook: where action audit entries are sent from (a direct executor in tests). */
    void useAuditor(Executor auditor) {
        this.auditor = auditor;
    }

    /**
     * Where a run's takeover stands for one person.
     *
     * @param status none, starting, active, released, expired or ended
     * @param active whether a person holds control now and may act on the page
     * @param starting whether a person asked for control and the agent is finishing its step
     * @param takenBy who holds or is getting control (a user id), only while starting or active
     * @param takenAt when they asked for it or, once active, got it; only while starting or active
     * @param endsAt when control returns by itself, only while active
     * @param mine whether the person asking holds or is getting it
     * @param canTakeOver whether the person asking may take control now (or already has it)
     * @param canRelease whether the person asking may hand control back
     * @param actions how many actions the current or last holder made
     * @param releasedBy who handed control back last, when it was handed back and nobody holds it now
     */
    public record TakeoverView(
            String status,
            boolean active,
            boolean starting,
            String takenBy,
            Instant takenAt,
            Instant endsAt,
            boolean mine,
            boolean canTakeOver,
            boolean canRelease,
            int actions,
            String releasedBy) {}

    /** What a person's action left on screen: the page's address and title and a fresh frame (base64 JPEG). */
    public record ActionResult(String url, String title, String frame) {}

    /**
     * What the agent's loop learns when it checks for a takeover.
     *
     * @param happened whether a person held, or had held, the browser since the agent last looked
     * @param waited how long the loop waited for them, which the run is given back
     * @param note what the agent is told before its next turn; null when there is nothing to say
     * @param summary the sentence the trace shows; null when there is nothing to say
     * @param pageChanged whether the person acted on the page, so calls planned before are stale
     */
    public record HandBack(boolean happened, Duration waited, String note, String summary, boolean pageChanged) {


        /** Nobody has the browser and nothing is waiting to be told. */
        public static final HandBack NONE = new HandBack(false, Duration.ZERO, null, null, false);
    }

    // @find: get takeover state, who has control, GET /api/runs/{runId}/takeover
    /**
     * Where the run's takeover stands for {@code actor}. Read-only: a takeover past its ceiling is
     * reported as expired, and one never closed on a run that is no longer working as ended,
     * without writing.
     *
     * @throws ApiException not found when the run is absent, in another workspace or hidden from the actor
     */
    public TakeoverView view(UUID orgId, UUID runId, Actor actor) {
        Run run = visibleRun(orgId, runId, actor);
        return viewOf(run, takeovers.findByRunIdAndOrgId(runId, orgId).orElse(null), actor);
    }

    // @find: start takeover, take control of browser, POST /api/runs/{runId}/takeover
    /**
     * Asks for control of the run's web browser for {@code actor}.
     *
     * <p>Flow: the run must be visible, the actor allowed ({@code run:cancel}, or the run's starter or
     * requester), the run running, nobody else holding or getting it, and the browser must have a
     * page open. Asking again while holding or getting it changes nothing. The row is saved as
     * {@link RunTakeover#STARTING} (a race with another person fails on the key or version); the
     * person may act once the agent's loop has stopped at its next step boundary. The browser is
     * asked to keep the page open and the request is audited.
     *
     * @throws ApiException permission denied, not found, or a conflict naming why it was refused
     */
    public TakeoverView start(UUID orgId, UUID runId, Actor actor) {
        Run run = visibleRun(orgId, runId, actor);
        String person = actor.humanId();
        if (person == null || !mayControl(actor, run)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, NOT_ALLOWED);
        }
        if (!"running".equals(run.getStatus())) {
            throw ApiException.conflict(NOT_WORKING);
        }
        Optional<RunTakeover> existing = takeovers.findById(runId);
        if (existing.isPresent() && holding(existing.get(), run)) {
            if (person.equals(existing.get().getTakenBy())) {
                return viewOf(run, existing.get(), actor);
            }
            throw ApiException.conflict(SOMEONE_ELSE);
        }
        if (!browser.hasPage(runId)) {
            throw ApiException.conflict(NO_PAGE);
        }
        Instant now = clock.instant();
        RunTakeover takeover;
        if (existing.isPresent()) {
            takeover = existing.get();
            if (takeover.isHeld()) {
                // Held past the ceiling and never closed: closed now, so what that holder did is kept.
                takeover.close(takeover.isActive() ? RunTakeover.EXPIRED : RunTakeover.ENDED, null,
                        takeover.getTakenAt().plus(CEILING));
            }
            takeover.retake(person, now);
        } else {
            takeover = RunTakeover.start(runId, orgId, person, now);
        }
        try {
            takeover = takeovers.saveAndFlush(takeover);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException race) {
            throw ApiException.conflict(SOMEONE_ELSE);
        }
        browser.hold(runId, true);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("agentId", String.valueOf(run.getAgentId()));
        audit.record(orgId, actor, "run.takeover.start", "run", runId.toString(), "succeeded", detail);
        log.info("Run {}: a person asked for control of its web browser", runId);
        return viewOf(run, takeover, actor);
    }

    // @find: takeover action, person clicks types scrolls navigates, POST /api/runs/{runId}/takeover/actions
    /**
     * Does one action of the person holding the run's browser and answers the page afterwards.
     *
     * <p>Flow: only the holder may act, and only once the agent has stopped for them (while it is
     * still finishing its step the action is refused with {@link #AGENT_FINISHING}); the action is
     * checked ({@link TakeoverActions#check}); the browser does it (an address goes through the same
     * guard as the agent's open_page); the summary gains its type, the page and, for typing, the
     * field's label only. The action's type is audited, done or refused (with the browser's reason),
     * never what was typed or the address; the entry is sent off the request thread, so a slow audit
     * service does not slow the person down.
     *
     * @throws ApiException a validation failure, a conflict when the actor does not hold control or
     *     the browser refused (with the browser's sentence), or not found
     */
    public ActionResult act(UUID orgId, UUID runId, Actor actor, TakeoverActions.ActionRequest request) {
        Run run = visibleRun(orgId, runId, actor);
        RunTakeover takeover = takeovers.findByRunIdAndOrgId(runId, orgId)
                .filter(held -> holding(held, run))
                .orElseThrow(() -> ApiException.conflict(NOT_HELD));
        if (!Objects.equals(actor.humanId(), takeover.getTakenBy())) {
            throw ApiException.conflict(SOMEONE_ELSE);
        }
        if (!takeover.isActive()) {
            throw ApiException.conflict(AGENT_FINISHING);
        }
        Map<String, Object> body = TakeoverActions.check(request);
        String type = request.type();
        TakeoverBrowser.Answer answer = browser.act(runId, type, body);
        if (!answer.ok()) {
            String problem = answer.problem() == null || answer.problem().isBlank()
                    ? "The web browser could not do that."
                    : answer.problem();
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("type", type);
            refused.put("reason", TakeoverActions.clip(problem, 200));
            auditAction(orgId, actor, runId, "denied", refused);
            log.info("Run {}: the web browser refused a person's {}", runId, type);
            throw ApiException.conflict(problem);
        }
        recordAction(takeover, type, answer);
        auditAction(orgId, actor, runId, "succeeded", Map.of("type", type));
        return new ActionResult(answer.url(), answer.title(), answer.frame());
    }

    /** Sends one action's audit entry from its own thread, with the request's context; never throws. */
    private void auditAction(UUID orgId, Actor actor, UUID runId, String outcome, Map<String, Object> detail) {
        try {
            auditor.execute(RequestContext.wrap(() ->
                    audit.record(orgId, actor, "run.takeover.action", "run", runId.toString(), outcome, detail)));
        } catch (RuntimeException rejected) {
            log.warn("Run {}: a takeover action could not be audited: {}", runId, rejected.getMessage());
        }
    }

    /**
     * Adds one done action to the summary and saves it. A save that loses a race (control returned
     * meanwhile) is tried once more on the fresh row, so long as the agent has not been told yet.
     */
    private void recordAction(RunTakeover takeover, String type, TakeoverBrowser.Answer answer) {
        RunTakeover current = takeover;
        for (int attempt = 0; attempt < 2 && current != null; attempt++) {
            if (!current.isActive() && !current.isPending()) {
                return;
            }
            current.setActions(TakeoverActions.record(current.getActions(), type, answer.url(), answer.field()));
            try {
                takeovers.save(current);
                return;
            } catch (OptimisticLockingFailureException race) {
                current = takeovers.findById(takeover.getRunId()).orElse(null);
            }
        }
    }

    // @find: hand back browser to agent, release takeover, POST /api/runs/{runId}/takeover/release
    /**
     * Hands control back to the agent, with an optional note for it.
     *
     * <p>The holder may hand back; so may anyone who can stop runs, which ends someone else's hold.
     * A request the agent has not stopped for yet is simply withdrawn. The note is shortened to 500
     * characters and screened for secrets, then kept until the agent is told; a withdrawn request
     * with no note leaves nothing to tell. The browser stops holding the page and the release is
     * audited (without the note).
     *
     * @throws ApiException a conflict when nobody holds control, permission denied, or not found
     */
    public TakeoverView release(UUID orgId, UUID runId, Actor actor, String note) {
        Run run = visibleRun(orgId, runId, actor);
        RunTakeover takeover = takeovers.findByRunIdAndOrgId(runId, orgId)
                .filter(held -> holding(held, run))
                .orElseThrow(() -> ApiException.conflict(NOT_HELD));
        boolean mine = Objects.equals(actor.humanId(), takeover.getTakenBy());
        if (!mine && !actor.hasPermission(Permission.Codes.RUN_CANCEL)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, RELEASE_NOT_ALLOWED);
        }
        boolean neverStarted = takeover.isStarting();
        String clean = screen(TakeoverActions.cleanNote(note));
        takeover.setNote(joined(takeover.getNote(), clean));
        Instant now = clock.instant();
        takeover.close(RunTakeover.RELEASED, actor.humanId(), now);
        if (neverStarted && takeover.getNote() == null && TakeoverActions.total(takeover.getActions()) == 0) {
            // The agent never stopped for this person and they leave no note: nothing to tell it.
            takeover.setDeliveredAt(now);
        }
        try {
            takeover = takeovers.saveAndFlush(takeover);
        } catch (OptimisticLockingFailureException race) {
            throw ApiException.conflict(NOT_HELD);
        }
        browser.hold(runId, false);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("actions", TakeoverActions.total(takeover.getActions()));
        detail.put("withNote", clean != null);
        detail.put("byHolder", mine);
        detail.put("beforeControl", neverStarted);
        audit.record(orgId, actor, "run.takeover.release", "run", runId.toString(), "succeeded", detail);
        log.info("Run {}: its web browser was handed back to the agent", runId);
        return viewOf(run, takeover, actor);
    }

    // @find: agent waits while a person has control, wait for hand back, takeover ceiling, auto release
    /**
     * Called by the agent's loop before each model call and each tool call: when a person holds or is
     * getting the run's browser, gives them control and waits until they hand it back, and says what
     * to tell the agent.
     *
     * <p>Flow: nothing held and nothing untold answers {@link HandBack#NONE} at once (one row read; a
     * failure of that read is the caller's). Control that came back while the agent was busy, and
     * not yet told, is reported straight away. A request ({@link RunTakeover#STARTING}) becomes
     * {@link RunTakeover#ACTIVE} now that the loop has stopped, and the ceiling counts from here.
     * Then {@code onWait} runs once (the loop records that it is waiting), then every poll: past the
     * 30 minute ceiling control is taken back automatically (saved, the page let go, audited); a run
     * that is no longer running ends the hold and the wait; a hand back ends the wait. A failed read
     * while waiting is logged and tried again on the next poll; only {@link #POLL_ERROR_LIMIT}
     * failures in a row give up (rethrown). What is to be told stays on the row until the loop calls
     * {@link #markTold} after telling the agent, so it is never lost if the loop parks or stops
     * first. When the person acted, the browser on this (the run's) server learns of it
     * ({@link TakeoverBrowser#personActed}). Runs on the run's own virtual thread; an interrupt ends
     * the wait early.
     *
     * @param runId the run being driven
     * @param runStatus reads the run's status now
     * @param onWait run once when the loop starts waiting
     * @return what happened; never null
     */
    public HandBack awaitHandBack(UUID runId, Supplier<String> runStatus, Runnable onWait) {
        RunTakeover takeover = takeovers.findById(runId).orElse(null);
        if (takeover == null) {
            return HandBack.NONE;
        }
        if (takeover.isPending()) {
            return pendingHandBack(takeover, Duration.ZERO);
        }
        if (takeover.isStarting()) {
            takeover = activate(takeover);
            if (takeover == null) {
                return HandBack.NONE;
            }
            if (takeover.isPending()) {
                return pendingHandBack(takeover, Duration.ZERO);
            }
        }
        if (!takeover.isActive()) {
            return HandBack.NONE;
        }
        Instant began = clock.instant();
        onWait.run();
        log.info("Run {} is waiting: a person has control of its web browser", runId);
        int failures = 0;
        while (true) {
            if (!clock.instant().isBefore(takeover.getTakenAt().plus(CEILING))) {
                RunTakeover expired = closeByPlatform(takeover, RunTakeover.EXPIRED, "ceiling");
                return expired != null && expired.isPending() ? pendingHandBack(expired, waitedSince(began)) : waitOnly(began);
            }
            try {
                sleeper.sleep(pollInterval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return waitOnly(began);
            }
            String status;
            RunTakeover fresh;
            try {
                status = runStatus.get();
                fresh = takeovers.findById(runId).orElse(null);
                failures = 0;
            } catch (RuntimeException unreadable) {
                failures++;
                log.warn("Run {}: could not check its takeover ({} in a row): {}", runId, failures, unreadable.getMessage());
                if (failures >= POLL_ERROR_LIMIT) {
                    throw unreadable;
                }
                continue;
            }
            if (!"running".equals(status)) {
                closeByPlatform(takeover, RunTakeover.ENDED, "run ended");
                return waitOnly(began);
            }
            if (fresh == null) {
                return waitOnly(began);
            }
            takeover = fresh;
            if (!takeover.isActive()) {
                return takeover.isPending() ? pendingHandBack(takeover, waitedSince(began)) : waitOnly(began);
            }
        }
    }

    /**
     * Gives the person control now that the loop has stopped: saved as active from now. A race with
     * the person withdrawing answers the fresh row instead.
     *
     * @return the saved or fresh row, or null when it is gone
     */
    private RunTakeover activate(RunTakeover takeover) {
        takeover.activate(clock.instant());
        try {
            RunTakeover saved = takeovers.save(takeover);
            log.info("Run {}: the agent stopped, so the person has control of its web browser", saved.getRunId());
            return saved;
        } catch (OptimisticLockingFailureException race) {
            return takeovers.findById(takeover.getRunId()).orElse(null);
        }
    }

    /** The loop waited but there is nothing to tell the agent. */
    private HandBack waitOnly(Instant began) {
        return new HandBack(true, waitedSince(began), null, null, false);
    }

    /** How long since {@code began}, never negative. */
    private Duration waitedSince(Instant began) {
        Duration waited = Duration.between(began, clock.instant());
        return waited.isNegative() ? Duration.ZERO : waited;
    }

    /**
     * Ends a hold the platform ends (the ceiling, or the run stopping, finishing or parking), lets
     * the page go and audits it with {@code reason}. A race with the person handing back answers the
     * fresh row instead.
     *
     * @return the saved or fresh row, or null when it could not be read
     */
    private RunTakeover closeByPlatform(RunTakeover takeover, String status, String reason) {
        takeover.close(status, null, clock.instant());
        try {
            RunTakeover saved = takeovers.save(takeover);
            browser.hold(takeover.getRunId(), false);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("actions", TakeoverActions.total(saved.getActions()));
            detail.put("automatic", true);
            detail.put("reason", reason);
            audit.record(saved.getOrgId(), Actor.SYSTEM, "run.takeover.release", "run",
                    saved.getRunId().toString(), "succeeded", detail);
            return saved;
        } catch (OptimisticLockingFailureException race) {
            return takeovers.findById(takeover.getRunId()).orElse(null);
        }
    }

    /**
     * What the agent is to be told about control that came back, without marking it told: the row
     * keeps it until {@link #markTold}. When the person acted, the browser on this server is told
     * which fields they typed into, so an approval of the agent's next click says so.
     */
    private HandBack pendingHandBack(RunTakeover takeover, Duration waited) {
        boolean automatic = RunTakeover.EXPIRED.equals(takeover.getStatus());
        Map<String, Object> summary = takeover.getActions();
        String note = TakeoverActions.noteForAgent(summary, takeover.getNote(), automatic);
        String trace = TakeoverActions.traceSummary(summary, waited, automatic);
        boolean changed = TakeoverActions.total(summary) > 0;
        if (changed) {
            browser.personActed(takeover.getRunId(), TakeoverActions.fields(summary));
        }
        return new HandBack(true, waited, note, trace, changed);
    }

    // @find: mark hand back told, agent told what the person did, delivered_at
    /**
     * Marks what the agent was to be told about a takeover as told, once the loop has written it to
     * the trace and the conversation: the row keeps the summary but loses the note, which the trace
     * keeps from here. Nothing changes when there is nothing untold (someone took control again in
     * the meantime: what they do is told with it). A save that loses a race is tried once more.
     * Never throws; a failure only means the agent may be told again.
     */
    public void markTold(UUID runId) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                RunTakeover takeover = takeovers.findById(runId).orElse(null);
                if (takeover == null || !takeover.isPending()) {
                    return;
                }
                takeover.setDeliveredAt(clock.instant());
                takeover.setNote(null);
                try {
                    takeovers.save(takeover);
                    return;
                } catch (OptimisticLockingFailureException race) {
                    log.debug("Run {}: the takeover changed while the agent was being told", runId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Run {}: could not mark the hand back as told: {}", runId, e.getMessage());
        }
    }

    // @find: end takeover when run ends, run finished while taken over, loop left, takeover ended audited
    /**
     * Ends a hold, or a request for one, because the run's loop left: the run finished, failed or
     * was stopped ({@code reason} "run ended"), or parked to wait for a person, when the agent will
     * not stop at a step boundary for some time. Saved as ended, the page let go, and audited as a
     * release. Nothing changes when nobody holds or is getting the browser. Never throws: a failure
     * is logged, and the hold then reads as ended anyway once the run is no longer working.
     *
     * @param reason why, for the audit entry
     */
    public void endHold(UUID runId, String reason) {
        try {
            RunTakeover takeover = takeovers.findById(runId).orElse(null);
            if (takeover == null || !takeover.isHeld()) {
                return;
            }
            closeByPlatform(takeover, RunTakeover.ENDED, reason);
            log.info("Run {}: control of its web browser ended ({})", runId, reason);
        } catch (RuntimeException e) {
            log.warn("Run {}: could not end the takeover of its web browser: {}", runId, e.getMessage());
        }
    }

    // @find: steps given back for a takeover, step budget after takeover
    /** How many steps takeovers have given back to the run, kept across parks; 0 when none or unreadable. */
    public int stepsGivenBack(UUID runId) {
        try {
            return takeovers.findById(runId).map(RunTakeover::getStepsGivenBack).orElse(0);
        } catch (RuntimeException e) {
            log.warn("Run {}: could not read the steps given back by takeovers: {}", runId, e.getMessage());
            return 0;
        }
    }

    /**
     * Gives the run one step back, kept on its takeover row so a resumed run keeps it. A save that
     * loses a race is tried once more on the fresh row. Never throws; a failure is logged.
     */
    public void giveStepBack(UUID runId) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                RunTakeover takeover = takeovers.findById(runId).orElse(null);
                if (takeover == null) {
                    return;
                }
                takeover.giveStepBack();
                try {
                    takeovers.save(takeover);
                    return;
                } catch (OptimisticLockingFailureException race) {
                    log.debug("Run {}: the takeover changed while a step was given back", runId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Run {}: could not keep the step given back: {}", runId, e.getMessage());
        }
    }

    /** The view of a takeover row (or none) for one person on one run. */
    private TakeoverView viewOf(Run run, RunTakeover takeover, Actor actor) {
        boolean holds = takeover != null && holding(takeover, run);
        boolean active = holds && takeover.isActive();
        String person = actor.humanId();
        boolean mine = holds && person != null && person.equals(takeover.getTakenBy());
        boolean canTakeOver = person != null && mayControl(actor, run) && "running".equals(run.getStatus()) && (!holds || mine);
        boolean canRelease = holds && (mine || actor.hasPermission(Permission.Codes.RUN_CANCEL));
        String status;
        if (takeover == null) {
            status = "none";
        } else if (holds) {
            status = takeover.getStatus();
        } else if (takeover.isHeld()) {
            // Never closed: past its ceiling while the run works, or the run is no longer working.
            status = ACTIVE_RUN.contains(run.getStatus()) ? RunTakeover.EXPIRED : RunTakeover.ENDED;
        } else {
            status = takeover.getStatus();
        }
        return new TakeoverView(
                status,
                active,
                holds && takeover.isStarting(),
                holds ? takeover.getTakenBy() : null,
                holds ? takeover.getTakenAt() : null,
                active ? takeover.getTakenAt().plus(CEILING) : null,
                mine,
                canTakeOver,
                canRelease,
                takeover == null ? 0 : TakeoverActions.total(takeover.getActions()),
                !holds && takeover != null && RunTakeover.RELEASED.equals(takeover.getStatus()) ? takeover.getReleasedBy() : null);
    }

    /** A person holds or is getting control: the row is starting or active, inside the ceiling, and the run is still going. */
    private boolean holding(RunTakeover takeover, Run run) {
        return takeover.isHeld()
                && clock.instant().isBefore(takeover.getTakenAt().plus(CEILING))
                && ACTIVE_RUN.contains(run.getStatus());
    }

    // @find: who may take over a run, run cancel or requester
    /**
     * Whether {@code actor} may take over the run's browser: they can stop runs, they started the run,
     * or they asked for the goal the run's task belongs to.
     */
    boolean mayControl(Actor actor, Run run) {
        if (actor.hasPermission(Permission.Codes.RUN_CANCEL)) {
            return true;
        }
        String person = actor.humanId();
        if (person == null) {
            return false;
        }
        if (person.equals(run.getCreatedBy())) {
            return true;
        }
        if (run.getTaskId() == null) {
            return false;
        }
        UUID goalId = tasks.findByIdAndOrgId(run.getTaskId(), run.getOrgId()).map(Task::getGoalId).orElse(null);
        UUID requester = goalId == null
                ? null
                : goals.findById(goalId)
                        .filter(goal -> run.getOrgId().equals(goal.getOrgId()))
                        .map(Goal::getRequestedBy)
                        .orElse(null);
        return requester != null && person.equals(requester.toString());
    }

    /** The run, unless it is in another workspace or belongs to a private conversation the actor is not part of. */
    private Run visibleRun(UUID orgId, UUID runId, Actor actor) {
        Run run = runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        if (access != null && run.getTaskId() != null && access.hiddenTaskIds(orgId, actor).contains(run.getTaskId())) {
            throw ApiException.notFound("run", runId);
        }
        return run;
    }

    /** A note with anything that looks like a secret masked; unchanged when no redactor is present. */
    private String screen(String note) {
        if (note == null || redactor == null) {
            return note;
        }
        return redactor.text(note);
    }

    /** Two notes as one, either of which may be missing; at most the note limit long. */
    private static String joined(String earlier, String later) {
        if (earlier == null || earlier.isBlank()) {
            return later;
        }
        if (later == null || later.isBlank()) {
            return earlier;
        }
        return TakeoverActions.clip(earlier + " " + later, TakeoverActions.NOTE_LIMIT);
    }
}
