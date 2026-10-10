// @find: schedule service, create schedule, update schedule, delete schedule, pause, resume, run now, change owner, sweep due schedules, fire schedule, schedule audit, preview, ScheduleService, recurring work, overlap
// @what: Business logic for schedules: create, edit, pause, resume, run now, delete, owner changes and firing due schedules as goals.
// @flow: Called by ScheduleController, ScheduleSweep, InternalScheduleController; creates goals via the board
package os.aiworkforce.orchestrator.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creates, edits and fires schedules.
 *
 * <p>A schedule's phrase is parsed once, at creation and again whenever it is edited with a new
 * one; after that the sweep and every other reader work from the cron or run-at this already
 * settled on. Firing - turning a due schedule into a goal - is the one piece of behaviour shared
 * between a person clicking "run now" and the sweep noticing {@code next_run_at} has passed, so
 * it lives here once rather than twice.
 *
 * <p>A schedule fires as its owner ({@code requestedBy}), so changing one is changing work that
 * will later run in somebody's name. Every change is therefore open only to that owner and to
 * anyone who can cancel work ({@link #requireCanManage}, the same rule goals follow), and every
 * change is audited once it commits.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** Shown on every schedule paused because the person it runs as left the workspace. */
    public static final String OWNER_REMOVED_REASON = "Owner is no longer a member";

    /** Shown on every schedule Stop everything paused, so nobody mistakes it for a failure. */
    public static final String STOPPED_EVERYTHING_REASON = "Paused when all agent work was stopped.";

    private static final String RESOURCE = "schedule";

    private final Schedules schedules;
    private final ScheduleGoals scheduleGoals;
    private final Agents agents;
    private final GoalService goalService;
    private final ScheduleZoneLookup zones;
    private final AuditClient audit;

    public ScheduleService(
            Schedules schedules,
            ScheduleGoals scheduleGoals,
            Agents agents,
            GoalService goalService,
            ScheduleZoneLookup zones,
            AuditClient audit) {
        this.schedules = schedules;
        this.scheduleGoals = scheduleGoals;
        this.agents = agents;
        this.goalService = goalService;
        this.zones = zones;
        this.audit = audit;
    }

    public record PreviewResult(
            String kind, String cron, Instant runAt, String description, String timezone, List<Instant> nextRuns) {}

    // @find: preview schedule, when will it run
    @Transactional(readOnly = true)
    public PreviewResult preview(UUID orgId, String text, String timezoneOverride) {
        ZoneId zone = resolveZone(orgId, timezoneOverride);
        Instant now = Instant.now();
        ParsedSchedule parsed = ScheduleParser.parse(text, zone, now);
        List<Instant> nextRuns = ScheduleParser.nextRuns(parsed, zone, now, 5);
        return new PreviewResult(
                parsed.kind(), parsed.cron(), parsed.runAt(), parsed.description(), zone.getId(), nextRuns);
    }

    private ZoneId resolveZone(UUID orgId, String timezoneOverride) {
        if (timezoneOverride == null || timezoneOverride.isBlank()) {
            return zones.zoneFor(orgId);
        }
        try {
            return ZoneId.of(timezoneOverride);
        } catch (java.time.DateTimeException e) {
            throw ApiException.validation("timezone", "That is not a recognised timezone.");
        }
    }

    // @find: list schedules
    @Transactional(readOnly = true)
    public List<Schedule> list(UUID orgId) {
        return schedules.findByOrgIdOrderByNameAsc(orgId);
    }

    // @find: get one schedule
    @Transactional(readOnly = true)
    public Schedule get(UUID orgId, UUID id) {
        return schedules.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("schedule", id));
    }

    // ---- Who may change a schedule --------------------------------------------------------------

    /**
     * Refuses a change the actor may not make to this schedule.
     *
     * <p>Open to the person it runs as - who can always change their mind about their own work -
     * and to anyone who can cancel work in general: the rule {@link GoalService} applies to
     * stopping and retrying a goal, so a schedule cannot be used to get around it. A schedule from
     * before owners were recorded has nobody to match, so it needs {@code task:cancel}.
     *
     * @throws ApiException {@code PERMISSION_DENIED} naming {@code task:cancel}
     */
    // @find: who can manage a schedule, owner or admin check
    public void requireCanManage(Schedule schedule, Actor actor) {
        if (!isOwner(schedule, actor) && (actor == null || !actor.hasPermission(Permission.Codes.TASK_CANCEL))) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "Only the person this schedule runs as, or someone who can cancel work, can change it.")
                    .with("requiredPermission", Permission.Codes.TASK_CANCEL);
        }
    }

    private static boolean isOwner(Schedule schedule, Actor actor) {
        return actor != null
                && schedule.getRequestedBy() != null
                && schedule.getRequestedBy().toString().equals(actor.humanId());
    }

    /**
     * A one-off that has already fired: the sweep disables it and clears its next run, and nothing
     * else leaves a one-off in that shape (a pause keeps its next run), so the combination is the
     * mark of one that is done rather than paused.
     */
    public static boolean isCompleted(Schedule schedule) {
        return "once".equals(schedule.getKind()) && !schedule.isEnabled() && schedule.getNextRunAt() == null;
    }

    // ---- Creating and changing ------------------------------------------------------------------

    // @find: create schedule, new schedule
    @Transactional
    public Schedule create(UUID orgId, String name, UUID agentId, String instruction, String text) {
        Agent agent = requireActiveAgent(orgId, agentId);
        ZoneId zone = zones.zoneFor(orgId);
        Instant now = Instant.now();
        ParsedSchedule parsed = ScheduleParser.parse(text, zone, now);

        Schedule schedule = new Schedule();
        schedule.setId(UuidV7.generate());
        schedule.setOrgId(orgId);
        schedule.setName(name);
        schedule.setAgentId(agent.getId());
        schedule.setInstruction(instruction);
        applyParsed(schedule, parsed, zone, now);
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        schedule.setRequestedBy(parseUuidOrNull(actor.humanId()));
        schedules.save(schedule);
        log.info("Schedule {} ({}) created for agent {}", schedule.getId(), parsed.kind(), agent.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", schedule.getName());
        detail.put("agentId", schedule.getAgentId().toString());
        detail.put("kind", schedule.getKind());
        detail.put("description", schedule.getDescription());
        detail.put("instructionSha256", sha256(schedule.getInstruction()));
        recordAfterCommit(orgId, actor, "schedule.create", schedule.getId(), detail);
        return schedule;
    }

    /**
     * Changes a schedule's name, agent, instruction or timing; a field left null or blank keeps
     * what it had.
     *
     * <p>When someone other than its owner changes what the schedule does - its agent or its
     * instruction, or a new time for a one-off that already ran - they become its owner: every
     * later run then starts in the name of the person who actually chose that work, never in the
     * name of somebody who did not. A rename or a new time leaves the owner as they were.
     *
     * <p>A one-off that has already run comes back to life when it is given a new time that is
     * still ahead. A schedule somebody paused on purpose stays paused whatever is edited.
     */
    // @find: update schedule, edit schedule
    @Transactional
    public Schedule update(
            UUID orgId, UUID id, String name, UUID agentId, String instruction, String text, Actor actor) {
        Schedule schedule = get(orgId, id);
        requireCanManage(schedule, actor);
        boolean wasCompleted = isCompleted(schedule);
        UUID previousAgentId = schedule.getAgentId();
        String previousInstruction = schedule.getInstruction();
        String previousDescription = schedule.getDescription();
        List<String> changed = new ArrayList<>();

        if (name != null && !name.isBlank() && !name.equals(schedule.getName())) {
            schedule.setName(name);
            changed.add("name");
        }
        if (agentId != null && !agentId.equals(schedule.getAgentId())) {
            schedule.setAgentId(requireActiveAgent(orgId, agentId).getId());
            changed.add("agentId");
        }
        if (instruction != null && !instruction.isBlank() && !instruction.equals(schedule.getInstruction())) {
            schedule.setInstruction(instruction);
            changed.add("instruction");
        }
        boolean reactivated = false;
        if (text != null && !text.isBlank()) {
            ZoneId zone = zones.zoneFor(orgId);
            Instant now = Instant.now();
            ParsedSchedule parsed = ScheduleParser.parse(text, zone, now);
            applyParsed(schedule, parsed, zone, now);
            changed.add("timing");
            // Only a one-off that already ran: nobody chose to stop it, it simply finished, so a new
            // time ahead of now is a request to run it again. A deliberate pause is never undone here.
            if (wasCompleted && schedule.getNextRunAt() != null && schedule.getNextRunAt().isAfter(now)) {
                schedule.setEnabled(true);
                schedule.setPausedReason(null);
                schedule.setConsecutiveFailures(0);
                reactivated = true;
            }
        }

        // Bringing a finished one-off back is deciding that work runs again, the same as "Run now",
        // so it moves the owner just as redefining the work does.
        UUID previousOwner = schedule.getRequestedBy();
        UUID editor = parseUuidOrNull(actor.humanId());
        boolean redefinesWork = changed.contains("agentId") || changed.contains("instruction") || reactivated;
        boolean ownerChanges = redefinesWork && !Objects.equals(previousOwner, editor);
        if (ownerChanges) {
            schedule.setRequestedBy(editor);
            clearOwnerRemovedNote(schedule);
        }
        Schedule saved = schedules.save(schedule);
        if (changed.isEmpty()) {
            return saved;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changed", List.copyOf(changed));
        detail.put("agentId", schedule.getAgentId().toString());
        if (changed.contains("agentId")) {
            detail.put("previousAgentId", previousAgentId.toString());
        }
        detail.put("instructionSha256", sha256(schedule.getInstruction()));
        if (changed.contains("instruction")) {
            detail.put("previousInstructionSha256", sha256(previousInstruction));
        }
        if (changed.contains("timing")) {
            detail.put("description", schedule.getDescription());
            detail.put("previousDescription", previousDescription);
        }
        if (reactivated) {
            detail.put("reactivated", true);
        }
        if (ownerChanges) {
            putOwners(detail, previousOwner, schedule.getRequestedBy());
        }
        recordAfterCommit(orgId, actor, "schedule.update", schedule.getId(), detail);
        if (ownerChanges) {
            Map<String, Object> ownerDetail = new LinkedHashMap<>();
            putOwners(ownerDetail, previousOwner, schedule.getRequestedBy());
            ownerDetail.put("via", "edit");
            recordAfterCommit(orgId, actor, "schedule.owner_change", schedule.getId(), ownerDetail);
        }
        return saved;
    }

    /**
     * Hands a schedule to another person, who it then fires as. Needs {@code task:cancel}: an owner
     * cannot give their own work away to somebody, only someone who can manage everyone's can.
     */
    // @find: change schedule owner
    @Transactional
    public Schedule changeOwner(UUID orgId, UUID id, UUID newOwner, Actor actor) {
        if (actor == null || !actor.hasPermission(Permission.Codes.TASK_CANCEL)) {
            throw ApiException.permissionDenied(Permission.Codes.TASK_CANCEL);
        }
        if (newOwner == null) {
            throw ApiException.validation("userId", "Choose who the schedule should run as.");
        }
        Schedule schedule = get(orgId, id);
        UUID previousOwner = schedule.getRequestedBy();
        if (newOwner.equals(previousOwner)) {
            return schedule;
        }
        schedule.setRequestedBy(newOwner);
        clearOwnerRemovedNote(schedule);
        Schedule saved = schedules.save(schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        putOwners(detail, previousOwner, newOwner);
        detail.put("via", "transfer");
        recordAfterCommit(orgId, actor, "schedule.owner_change", schedule.getId(), detail);
        return saved;
    }

    /** A new owner is a current member, so a note saying the owner left no longer describes it. */
    private static void clearOwnerRemovedNote(Schedule schedule) {
        if (OWNER_REMOVED_REASON.equals(schedule.getPausedReason())) {
            schedule.setPausedReason(null);
        }
    }

    private void applyParsed(Schedule schedule, ParsedSchedule parsed, ZoneId zone, Instant now) {
        schedule.setKind(parsed.kind());
        schedule.setCron(parsed.cron());
        schedule.setRunAt(parsed.runAt());
        schedule.setTimezone(zone.getId());
        schedule.setDescription(parsed.description());
        schedule.setNextRunAt(nextRunAtOf(parsed, zone, now));
    }

    private static Instant nextRunAtOf(ParsedSchedule parsed, ZoneId zone, Instant now) {
        if ("once".equals(parsed.kind())) {
            return parsed.runAt();
        }
        return ScheduleParser.nextRuns(parsed, zone, now, 1).stream()
                .findFirst()
                .orElse(null);
    }

    private Agent requireActiveAgent(UUID orgId, UUID agentId) {
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        if (!agent.isActive()) {
            throw ApiException.validation("agentId", "The agent must be active to schedule work for it.");
        }
        return agent;
    }

    // ---- Pausing and resuming -------------------------------------------------------------------

    /**
     * Pauses a schedule for the person in the request context, held to the same rule as {@link
     * #pause(UUID, UUID, Actor)}. Stop everything does not come through here: it uses {@link
     * #pauseInternal}, which has to reach every schedule whoever owns it.
     */
    // @find: pause schedule
    @Transactional
    public Schedule pause(UUID orgId, UUID id) {
        return pause(orgId, id, RequestContext.requireActor());
    }

    /** Pauses a schedule so it is skipped by the sweep until someone resumes it. */
    // @find: pause schedule with actor
    @Transactional
    public Schedule pause(UUID orgId, UUID id, Actor actor) {
        Schedule schedule = get(orgId, id);
        requireCanManage(schedule, actor);
        schedule.setEnabled(false);
        // A fresh, deliberate pause replaces whatever note an earlier auto-pause may have left;
        // showing a stale "paused after 3 failed runs" beside a pause a person just chose
        // themselves would misstate why it is paused. The one note kept is that its owner has
        // left: that is still true, and it is what stops a resume firing in a former member's name.
        if (!OWNER_REMOVED_REASON.equals(schedule.getPausedReason())) {
            schedule.setPausedReason(null);
        }
        Schedule saved = schedules.save(schedule);
        recordAfterCommit(orgId, actor, "schedule.pause", schedule.getId(), new LinkedHashMap<>());
        return saved;
    }

    /**
     * Pauses a schedule with no check of who is asking, leaving {@code reason} as the note a person
     * reads beside it.
     *
     * <p>Only for callers that authorised the pause on grounds of their own: Stop everything, which
     * needs {@code run:cancel} and must reach every schedule whoever owns it, and the notice that a
     * schedule's owner has left. The audit entry names whoever is in the request context, or the
     * platform when nobody is.
     */
    // @find: pause schedule by system, auto pause with reason
    @Transactional
    public Schedule pauseInternal(UUID orgId, UUID id, String reason) {
        Schedule schedule = get(orgId, id);
        schedule.setEnabled(false);
        schedule.setPausedReason(reason);
        Schedule saved = schedules.save(schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (reason != null) {
            detail.put("reason", reason);
        }
        recordAfterCommit(
                orgId, RequestContext.actor().orElse(Actor.SYSTEM), "schedule.pause", schedule.getId(), detail);
        return saved;
    }

    /**
     * Pauses every schedule that runs as a person who has just left the workspace.
     *
     * <p>Each enabled one is disabled with {@link #OWNER_REMOVED_REASON} and audited as a pause by
     * {@code caller}. One already paused keeps its pause but takes the same note, so a later resume
     * by somebody else is refused rather than firing in the former member's name. A one-off that
     * already ran is left alone: it will not fire again on its own.
     *
     * @return how many enabled schedules this paused
     */
    // @find: pause schedules of removed member
    @Transactional
    public int pauseForRemovedOwner(UUID orgId, UUID userId, Actor caller) {
        int paused = 0;
        for (Schedule schedule : schedules.findByOrgIdAndRequestedBy(orgId, userId)) {
            if (isCompleted(schedule)) {
                continue;
            }
            boolean wasEnabled = schedule.isEnabled();
            schedule.setEnabled(false);
            schedule.setPausedReason(OWNER_REMOVED_REASON);
            schedules.save(schedule);
            if (wasEnabled) {
                paused++;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason", OWNER_REMOVED_REASON);
                detail.put("owner", userId.toString());
                recordAfterCommit(orgId, caller, "schedule.pause", schedule.getId(), detail);
            }
        }
        log.info("Paused {} schedule(s) owned by {}, who left workspace {}", paused, userId, orgId);
        return paused;
    }

    /**
     * Resumes a paused schedule, recomputing when it next fires from right now.
     *
     * <p>A one-off whose time has passed is refused rather than resumed: resuming it would fire it
     * at once, which nobody pressing "Resume" expects. So is a schedule whose owner has left, unless
     * the person resuming it is that owner, back again: it would otherwise fire in a former
     * member's name, and transferring it first is how somebody else takes it on.
     */
    // @find: resume schedule
    @Transactional
    public Schedule resume(UUID orgId, UUID id, Actor actor) {
        Schedule schedule = get(orgId, id);
        requireCanManage(schedule, actor);
        Instant now = Instant.now();
        if (OWNER_REMOVED_REASON.equals(schedule.getPausedReason()) && !isOwner(schedule, actor)) {
            throw ApiException.conflict(
                    "The person this schedule runs as is no longer a member. Transfer it to someone before resuming it.");
        }
        if ("once".equals(schedule.getKind())
                && (schedule.getRunAt() == null || !schedule.getRunAt().isAfter(now))) {
            throw ApiException.validation("when", "This one-off time has passed; edit it to pick a new time.");
        }
        schedule.setEnabled(true);
        schedule.setConsecutiveFailures(0);
        schedule.setPausedReason(null);
        schedule.setNextRunAt(nextRunAtOf(asParsed(schedule), zoneOf(schedule), now));
        Schedule saved = schedules.save(schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (schedule.getNextRunAt() != null) {
            detail.put("nextRunAt", schedule.getNextRunAt().toString());
        }
        recordAfterCommit(orgId, actor, "schedule.resume", schedule.getId(), detail);
        return saved;
    }

    // ---- Running, deleting and reading history --------------------------------------------------

    /**
     * Fires a schedule immediately, whether or not it is enabled, without moving its next run.
     *
     * <p>The goal it starts is the caller's, not the owner's: a person pressing "Run now" is the one
     * deciding this run happens, so it starts in their name. Only the sweep fires as the owner.
     */
    // @find: run schedule now, run now
    @Transactional
    public Schedule runNow(UUID orgId, UUID id, Actor actor) {
        Schedule schedule = get(orgId, id);
        requireCanManage(schedule, actor);
        Goal goal = fire(schedule, actor, parseUuidOrNull(actor.humanId()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scheduleId", schedule.getId().toString());
        detail.put("goalId", goal.getId().toString());
        detail.put("triggeredBy", actor.id());
        detail.put("triggeredByKind", actor.kind().name());
        if (schedule.getRequestedBy() != null) {
            detail.put("owner", schedule.getRequestedBy().toString());
        }
        recordAfterCommit(orgId, actor, "schedule.run_now", schedule.getId(), detail);
        return schedule;
    }

    // @find: delete schedule
    @Transactional
    public void delete(UUID orgId, UUID id, Actor actor) {
        Schedule schedule = get(orgId, id);
        requireCanManage(schedule, actor);
        schedules.delete(schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", schedule.getName());
        detail.put("agentId", schedule.getAgentId().toString());
        if (schedule.getRequestedBy() != null) {
            detail.put("owner", schedule.getRequestedBy().toString());
        }
        recordAfterCommit(orgId, actor, "schedule.delete", schedule.getId(), detail);
    }

    /** One page of the goals this schedule has fired, newest first. */
    // @find: schedule run history
    @Transactional(readOnly = true)
    public Page<Goal> runs(UUID orgId, UUID id, Pageable pageable) {
        get(orgId, id);
        return scheduleGoals.findByOrgIdAndScheduleIdOrderByCreatedAtDesc(orgId, id, pageable);
    }

    /**
     * Records one Stop everything in the audit trail, with what it stopped.
     *
     * <p>Kept beside {@link #pauseInternal}, which Stop everything uses, so the board's service can
     * have it recorded without an audit client of its own. Recorded after any surrounding commit;
     * Stop everything runs each cancellation in a transaction of its own, so by the time it calls
     * this they have all committed.
     */
    // @find: audit stop all schedules
    public void recordStopAll(UUID orgId, Actor actor, Map<String, Object> counts) {
        Actor recordedAs = actor == null ? Actor.SYSTEM : actor;
        Map<String, Object> detail = counts == null ? Map.of() : new LinkedHashMap<>(counts);
        LifecycleAnnouncer.afterCommit(() -> audit.record(
                orgId, recordedAs, "orchestrator.stop_all", "workspace", orgId.toString(), "succeeded", detail));
    }

    // ---- The sweep ------------------------------------------------------------------------------

    /**
     * Starts as many of this tick's due schedules as fit in {@code limit}, one workspace's worth
     * of failures never stopping the rest.
     *
     * @return how many due schedules were processed, fired or skipped for an overlap alike
     */
    // @find: sweep due schedules, start due work, scheduler tick
    @Transactional
    public int sweepDue(int limit) {
        Instant now = Instant.now();
        List<Schedule> due = schedules.findDue(now, PageRequest.of(0, limit));
        int processed = 0;
        for (Schedule schedule : due) {
            try {
                processDue(schedule, now);
                processed++;
            } catch (RuntimeException e) {
                log.error("Could not process due schedule {}", schedule.getId(), e);
            }
        }
        return processed;
    }

    private void processDue(Schedule schedule, Instant now) {
        boolean skipThisOccurrence = "skip".equals(schedule.getOverlapPolicy()) && lastGoalStillActive(schedule);
        if (skipThisOccurrence) {
            log.info(
                    "Schedule {} is due but its last goal is still active; skipping this occurrence", schedule.getId());
        } else {
            fire(schedule, ownerActor(schedule), schedule.getRequestedBy());
        }
        advance(schedule, now);
    }

    private boolean lastGoalStillActive(Schedule schedule) {
        return scheduleGoals
                .findFirstByScheduleIdOrderByCreatedAtDesc(schedule.getId())
                .map(goal -> !goal.isFinished())
                .orElse(false);
    }

    private void advance(Schedule schedule, Instant now) {
        if ("once".equals(schedule.getKind())) {
            schedule.setEnabled(false);
            schedule.setNextRunAt(null);
        } else {
            schedule.setNextRunAt(nextRunAtOf(asParsed(schedule), zoneOf(schedule), now));
        }
        schedules.save(schedule);
    }

    private static ParsedSchedule asParsed(Schedule schedule) {
        return new ParsedSchedule(
                schedule.getKind(), schedule.getCron(), schedule.getRunAt(), schedule.getDescription());
    }

    private static ZoneId zoneOf(Schedule schedule) {
        try {
            return ZoneId.of(schedule.getTimezone());
        } catch (java.time.DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    /** Who the sweep fires as: the schedule's owner, or the platform for one with no owner on record. */
    private static Actor ownerActor(Schedule schedule) {
        UUID requestedBy = schedule.getRequestedBy();
        return requestedBy == null
                ? Actor.SYSTEM
                : Actor.user(requestedBy.toString(), schedule.getOrgId().toString(), null, Set.of(), 0L);
    }

    /** Creates and starts the goal one occurrence of this schedule fires, as {@code actor}, for {@code requestedBy}. */
    private Goal fire(Schedule schedule, Actor actor, UUID requestedBy) {
        Goal goal = RequestContext.as(
                actor,
                () -> goalService.createGoal(
                        schedule.getOrgId(),
                        new GoalService.NewGoal(
                                schedule.getName(),
                                schedule.getInstruction(),
                                requestedBy,
                                "schedule",
                                null,
                                schedule.getId(),
                                List.of(new GoalService.NewTask(
                                        schedule.getAgentId(),
                                        schedule.getName(),
                                        schedule.getInstruction(),
                                        List.of()))),
                        true));
        schedule.setLastGoalId(goal.getId());
        schedule.setLastRunAt(Instant.now());
        // The goal it started is under way; the listener overwrites this with how it finished.
        schedule.setLastStatus(goal.getStatus());
        schedules.save(schedule);
        return goal;
    }

    // ---- Audit ----------------------------------------------------------------------------------

    /**
     * Records one schedule change once the transaction commits, so a rolled-back change leaves no
     * entry and a slow analytics service never holds this transaction's row locks.
     */
    private void recordAfterCommit(
            UUID orgId, Actor actor, String action, UUID scheduleId, Map<String, Object> detail) {
        Actor recordedAs = actor == null ? Actor.SYSTEM : actor;
        Map<String, Object> frozen = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        LifecycleAnnouncer.afterCommit(() -> audit.record(
                orgId, recordedAs, action, RESOURCE, scheduleId.toString(), "succeeded", frozen));
    }

    private static void putOwners(Map<String, Object> detail, UUID previousOwner, UUID owner) {
        detail.put("previousOwner", previousOwner == null ? "none" : previousOwner.toString());
        detail.put("owner", owner == null ? "none" : owner.toString());
    }

    /**
     * The instruction's fingerprint, so the audit trail shows whether it changed without copying
     * what may be a long or sensitive text into every entry.
     */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
