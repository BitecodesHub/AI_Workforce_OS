// @find: schedule requests, create schedule from chat, create schedule from agent tool, remind HR tomorrow, schedule confirmation sentence, next run in plain words, reminder target agent, ScheduleRequests
// @what: Turns a request written in words ("@Research every Monday at 9am ...", "remind HR tomorrow at 3pm to ...") into a saved schedule and a plain-words confirmation.
// @flow: Used by chat CoordinatorService and AgentScheduleTool; calls ScheduleService.create
package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.domain.Agent;

/**
 * Saves a schedule someone asked for in words and says back, in a sentence, what will happen and
 * when it next will.
 *
 * <p>The one place chat and an agent's {@code schedule.create} tool share, so a schedule set up
 * either way is named, worded and confirmed the same.
 */
@Component
public class ScheduleRequests {

    /** The longest schedule name; the rest of a long instruction stays in the instruction. */
    static final int MAX_NAME = 60;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' h:mm a", Locale.ENGLISH);

    /** "remind HR to ...", "ask Research to ...", "tell @Support that ...". */
    private static final Pattern REMINDER = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:remind|ask|tell|get|have)\\s+@?([\\p{L}\\p{N}][\\p{L}\\p{N} -]{0,40}?)\\s+"
                    + "(?:to|that|about)\\s+(.+)$",
            Pattern.DOTALL);

    private final ScheduleService service;

    public ScheduleRequests(ScheduleService service) {
        this.service = service;
    }

    /** The agent a "remind HR to ..." sentence names, and what it is reminded to do. */
    public record Target(Agent agent, String instruction) {}

    /** A saved schedule and the sentence that confirms it. */
    public record Confirmation(Schedule schedule, String content, Map<String, Object> detail) {}

    // @find: reminder target, remind agent to do something
    /**
     * The active agent a "remind X to ..." sentence names by key or name, ignoring case, spaces and
     * hyphens, and the work it is reminded to do; empty when the sentence names nobody here.
     */
    public static Optional<Target> reminderTarget(String instruction, List<Agent> agents) {
        if (instruction == null || agents == null) {
            return Optional.empty();
        }
        Matcher m = REMINDER.matcher(instruction.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        String wanted = normalise(m.group(1));
        String work = m.group(2).strip();
        for (Agent agent : agents) {
            if (!agent.isActive()) {
                continue;
            }
            if (wanted.equals(normalise(agent.getKey())) || wanted.equals(normalise(agent.getName()))) {
                return Optional.of(new Target(agent, capitalise(work)));
            }
        }
        return Optional.empty();
    }

    // @find: create schedule from words, save chat schedule
    /**
     * Saves a schedule for {@code agent} as the person in the request context.
     *
     * @param phrase the timing phrase alone, such as "every Monday at 9am"
     * @throws os.aiworkforce.platform.error.ApiException when the phrase cannot be read or the agent is not active
     */
    public Confirmation create(UUID orgId, Agent agent, String instruction, String phrase) {
        String name = nameFor(instruction);
        Schedule schedule = service.create(orgId, name, agent.getId(), instruction, phrase);
        return new Confirmation(schedule, sentence(schedule, agent.getName()), detail(schedule, agent, phrase));
    }

    /** A schedule's name: the instruction's opening words. */
    public static String nameFor(String instruction) {
        String flat = instruction == null ? "" : instruction.strip().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            return "Scheduled task";
        }
        if (flat.length() <= MAX_NAME) {
            return capitalise(flat);
        }
        int cut = flat.lastIndexOf(' ', MAX_NAME - 1);
        return capitalise((cut > 0 ? flat.substring(0, cut) : flat.substring(0, MAX_NAME)).strip());
    }

    // @find: schedule confirmation sentence, scheduled will run next
    /**
     * "Scheduled. Research will summarise competitor news every Monday at 9:00 am (Australia/Sydney).
     * The first run is Mon 13 Oct 2026 at 9:00 AM."
     */
    public static String sentence(Schedule schedule, String agentName) {
        ZoneId zone = zoneOf(schedule);
        String what = lowerFirst(schedule.getInstruction().strip().replaceAll("\\s+", " "));
        if (what.length() > 160) {
            what = what.substring(0, 157).strip() + "...";
        }
        what = what.replaceAll("[.!?]+$", "");
        String when = lowerFirst(schedule.getDescription());
        StringBuilder text = new StringBuilder("Scheduled. ")
                .append(agentName == null ? "The agent" : agentName)
                .append(" will ")
                .append(what)
                .append(' ')
                .append(when)
                .append(" (")
                .append(zone.getId())
                .append(").");
        Instant next = schedule.getNextRunAt();
        if (next != null && schedule.isRecurring()) {
            text.append(" The first run is ").append(formatIn(next, zone)).append('.');
        }
        return text.toString();
    }

    /** What a chat message carries about the saved schedule, for its card and its link. */
    public static Map<String, Object> detail(Schedule schedule, Agent agent, String phrase) {
        ZoneId zone = zoneOf(schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scheduleId", schedule.getId().toString());
        detail.put("text", phrase);
        detail.put("kind", schedule.getKind());
        detail.put("cron", schedule.getCron());
        detail.put("runAt", schedule.getRunAt() == null ? null : schedule.getRunAt().toString());
        detail.put("description", schedule.getDescription());
        detail.put("timezone", zone.getId());
        detail.put("nextRunAt", schedule.getNextRunAt() == null ? null : schedule.getNextRunAt().toString());
        detail.put(
                "nextRuns",
                ScheduleParser.nextRuns(
                                new ParsedSchedule(
                                        schedule.getKind(),
                                        schedule.getCron(),
                                        schedule.getRunAt(),
                                        schedule.getDescription()),
                                zone,
                                Instant.now(),
                                5)
                        .stream()
                        .map(Instant::toString)
                        .toList());
        detail.put("agentId", agent.getId().toString());
        detail.put("agentName", agent.getName());
        detail.put("instruction", schedule.getInstruction());
        detail.put("name", schedule.getName());
        return detail;
    }

    /** "Mon 13 Oct 2026 at 9:00 AM" in the schedule's own zone. */
    public static String formatIn(Instant instant, ZoneId zone) {
        return WHEN.format(ZonedDateTime.ofInstant(instant, zone));
    }

    private static ZoneId zoneOf(Schedule schedule) {
        try {
            return ZoneId.of(schedule.getTimezone());
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "");
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String lowerFirst(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Keep an acronym or a name as written: "HR" or "Monday" must not become "hR" or "monday".
        if (text.length() > 1 && Character.isUpperCase(text.charAt(1))) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
