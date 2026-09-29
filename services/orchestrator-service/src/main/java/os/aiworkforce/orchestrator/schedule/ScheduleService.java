package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creates, edits and fires schedules.
 *
 * <p>A schedule's phrase is parsed once, at creation and again whenever it is edited with a new
 * one; after that the sweep and every other reader work from the cron or run-at this already
 * settled on. Firing - turning a due schedule into a goal - is the one piece of behaviour shared
 * between a person clicking "run now" and the sweep noticing {@code next_run_at} has passed, so
 * it lives here once rather than twice.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final Schedules schedules;
    private final ScheduleGoals scheduleGoals;
    private final Agents agents;
    private final GoalService goalService;
    private final ScheduleZoneLookup zones;

    public ScheduleService(
            Schedules schedules,
            ScheduleGoals scheduleGoals,
            Agents agents,
            GoalService goalService,
            ScheduleZoneLookup zones) {
        this.schedules = schedules;
        this.scheduleGoals = scheduleGoals;
        this.agents = agents;
        this.goalService = goalService;
        this.zones = zones;
    }

    public record PreviewResult(
            String kind, String cron, Instant runAt, String description, String timezone, List<Instant> nextRuns) {}

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

    @Transactional(readOnly = true)
    public List<Schedule> list(UUID orgId) {
        return schedules.findByOrgIdOrderByNameAsc(orgId);
    }

    @Transactional(readOnly = true)
    public Schedule get(UUID orgId, UUID id) {
        return schedules.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("schedule", id));
    }

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
        schedule.setRequestedBy(RequestContext.actor()
                .map(Actor::id)
                .map(ScheduleService::parseUuidOrNull)
                .orElse(null));
        schedules.save(schedule);
        log.info("Schedule {} ({}) created for agent {}", schedule.getId(), parsed.kind(), agent.getId());
        return schedule;
    }

    @Transactional
    public Schedule update(UUID orgId, UUID id, String name, UUID agentId, String instruction, String text) {
        Schedule schedule = get(orgId, id);
        if (name != null && !name.isBlank()) {
            schedule.setName(name);
        }
        if (agentId != null) {
            schedule.setAgentId(requireActiveAgent(orgId, agentId).getId());
        }
        if (instruction != null && !instruction.isBlank()) {
            schedule.setInstruction(instruction);
        }
        if (text != null && !text.isBlank()) {
            ZoneId zone = zones.zoneFor(orgId);
            Instant now = Instant.now();
            ParsedSchedule parsed = ScheduleParser.parse(text, zone, now);
            applyParsed(schedule, parsed, zone, now);
        }
        return schedules.save(schedule);
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

    /** Pauses a schedule so it is skipped by the sweep until someone resumes it. */
    @Transactional
    public Schedule pause(UUID orgId, UUID id) {
        Schedule schedule = get(orgId, id);
        schedule.setEnabled(false);
        // A fresh, deliberate pause replaces whatever note an earlier auto-pause may have left;
        // showing a stale "paused after 3 failed runs" beside a pause a person just chose
        // themselves would misstate why it is paused.
        schedule.setPausedReason(null);
        return schedules.save(schedule);
    }

    /** Resumes a paused schedule, recomputing when it next fires from right now. */
    @Transactional
    public Schedule resume(UUID orgId, UUID id) {
        Schedule schedule = get(orgId, id);
        schedule.setEnabled(true);
        schedule.setConsecutiveFailures(0);
        schedule.setPausedReason(null);
        ZoneId zone = zoneOf(schedule);
        Instant now = Instant.now();
        schedule.setNextRunAt(nextRunAtOf(asParsed(schedule), zone, now));
        return schedules.save(schedule);
    }

    /** Fires a schedule immediately, whether or not it is enabled, without moving its next run. */
    @Transactional
    public Schedule runNow(UUID orgId, UUID id) {
        Schedule schedule = get(orgId, id);
        fire(schedule);
        return schedule;
    }

    @Transactional
    public void delete(UUID orgId, UUID id) {
        schedules.delete(get(orgId, id));
    }

    @Transactional(readOnly = true)
    public List<Goal> runs(UUID orgId, UUID id) {
        get(orgId, id);
        return scheduleGoals.findByOrgIdAndScheduleIdOrderByCreatedAtDesc(orgId, id);
    }

    /**
     * Starts as many of this tick's due schedules as fit in {@code limit}, one workspace's worth
     * of failures never stopping the rest.
     *
     * @return how many due schedules were processed, fired or skipped for an overlap alike
     */
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
            fire(schedule);
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

    /**
     * Creates and starts the goal one occurrence of this schedule fires, as the schedule's own
     * creator - or the platform itself, for a schedule that predates having one on record.
     */
    private void fire(Schedule schedule) {
        UUID requestedBy = schedule.getRequestedBy();
        Actor actor = requestedBy == null
                ? Actor.SYSTEM
                : Actor.user(requestedBy.toString(), schedule.getOrgId().toString(), null, Set.of(), 0L);
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
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
