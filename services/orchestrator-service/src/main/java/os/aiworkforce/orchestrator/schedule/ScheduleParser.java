// @find: schedule parser, parse schedule text, natural language schedule, every day at, every monday, every n hours, cron, once at date, next runs, ScheduleParser, human readable schedule
// @what: Turns plain-words schedule text into a cron expression or a one-off time and computes the next runs.
// @flow: Called by ScheduleService.preview/create/update
package os.aiworkforce.orchestrator.schedule;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.scheduling.support.CronExpression;

import os.aiworkforce.platform.error.ApiException;

/**
 * Turns a plain-English schedule phrase into a cron expression or a one-off run time.
 *
 * <p>Every accepted phrase is matched in full, case-insensitively, against one of a fixed set of
 * shapes rather than tokenised generically: a schedule a person cannot read back and recognise as
 * what they typed is worse than a schedule that refuses an unusual phrasing with examples of what
 * does work. {@link #parse} is the one place that turns a phrase into a decision; every other
 * method in this file is a private step it uses.
 */
public final class ScheduleParser {

    private ScheduleParser() {}

    /** Shown on every rejected phrase, so a person always has something that is known to work. */
    private static final List<String> EXAMPLES = List.of(
            "every day at 9am",
            "every weekday at 9am",
            "every monday at 9am",
            "every monday and thursday at 2pm",
            "every 15 minutes",
            "every 2 hours",
            "every month on the 1st at 9am",
            "on the last day of every month at 5pm",
            "tomorrow at 5pm",
            "in 2 hours",
            "on 1 October at 9am");

    private static final Map<String, Integer> DAY_ORDER =
            Map.of("MON", 0, "TUE", 1, "WED", 2, "THU", 3, "FRI", 4, "SAT", 5, "SUN", 6);

    private static final Map<String, String> DAY_ABBREVIATION = Map.ofEntries(
            Map.entry("mon", "MON"),
            Map.entry("monday", "MON"),
            Map.entry("tue", "TUE"),
            Map.entry("tues", "TUE"),
            Map.entry("tuesday", "TUE"),
            Map.entry("wed", "WED"),
            Map.entry("weds", "WED"),
            Map.entry("wednesday", "WED"),
            Map.entry("thu", "THU"),
            Map.entry("thur", "THU"),
            Map.entry("thurs", "THU"),
            Map.entry("thursday", "THU"),
            Map.entry("fri", "FRI"),
            Map.entry("friday", "FRI"),
            Map.entry("sat", "SAT"),
            Map.entry("saturday", "SAT"),
            Map.entry("sun", "SUN"),
            Map.entry("sunday", "SUN"));

    private static final Map<String, String> DAY_FULL_NAME = Map.of(
            "MON",
            "Monday",
            "TUE",
            "Tuesday",
            "WED",
            "Wednesday",
            "THU",
            "Thursday",
            "FRI",
            "Friday",
            "SAT",
            "Saturday",
            "SUN",
            "Sunday");

    private static final Map<String, Month> MONTH_NAME = buildMonthNames();

    private static Map<String, Month> buildMonthNames() {
        Map<String, Month> months = new java.util.HashMap<>();
        for (Month month : Month.values()) {
            months.put(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT), month);
            months.put(month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT), month);
        }
        return Map.copyOf(months);
    }

    // ---- Time-of-day -----------------------------------------------------------------------

    private static final Pattern TIME = Pattern.compile("(?i)^(noon|midnight|(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)$");

    private static LocalTime parseTime(String token) {
        Matcher m = TIME.matcher(token.strip());
        if (!m.matches()) {
            throw invalid();
        }
        String whole = m.group(1).toLowerCase(Locale.ROOT);
        if ("noon".equals(whole)) {
            return LocalTime.NOON;
        }
        if ("midnight".equals(whole)) {
            return LocalTime.MIDNIGHT;
        }
        int hour = Integer.parseInt(m.group(2));
        int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        String ampm = m.group(4) == null ? null : m.group(4).toLowerCase(Locale.ROOT);
        if (ampm == null) {
            if (hour == 24) {
                hour = 0;
            }
        } else if ("pm".equals(ampm)) {
            if (hour < 12) {
                hour += 12;
            }
        } else {
            if (hour == 12) {
                hour = 0;
            }
        }
        try {
            return LocalTime.of(hour, minute);
        } catch (DateTimeException e) {
            throw invalid();
        }
    }

    /** "9:00 am", "2:00 pm" - the exact shape every description below echoes a time with. */
    private static String describeTime(LocalTime time) {
        int hour = time.getHour();
        String ampm = hour < 12 ? "am" : "pm";
        int twelveHour = hour % 12 == 0 ? 12 : hour % 12;
        return String.format(Locale.ROOT, "%d:%02d %s", twelveHour, time.getMinute(), ampm);
    }

    // ---- Public API -------------------------------------------------------------------------

    /**
     * Tries to read {@code text} as a schedule, answering nothing rather than throwing.
     *
     * <p>Used where an ordinary sentence might arrive alongside a genuine schedule phrase - the
     * chat coordinator's intent detection - and a false positive would be worse than a miss.
     */
    // @find: try parse schedule text, detect schedule phrase
    public static Optional<ParsedSchedule> tryParse(String text, ZoneId zone, Instant now) {
        try {
            return Optional.of(parse(text, zone, now));
        } catch (ApiException notASchedule) {
            return Optional.empty();
        }
    }

    /**
     * Parses {@code text} as a schedule, or explains why it could not.
     *
     * @throws ApiException a validation failure ({@code field} {@code "text"}) carrying examples
     *     of phrases that do work
     */
    // @find: parse schedule text into cron or once, invalid schedule error
    public static ParsedSchedule parse(String text, ZoneId zone, Instant now) {
        if (text == null || text.isBlank()) {
            throw invalid();
        }
        String original = text.strip();
        String lower = original.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();

        Optional<ParsedSchedule> isoInstant = tryIsoInstant(original, zone, now);
        if (isoInstant.isPresent()) {
            return isoInstant.get();
        }
        Optional<ParsedSchedule> rawCron = tryRawCron(original);
        if (rawCron.isPresent()) {
            return rawCron.get();
        }

        Optional<ParsedSchedule> recurring = tryRecurring(lower, zone, now);
        if (recurring.isPresent()) {
            return recurring.get();
        }
        Optional<ParsedSchedule> once = tryOnce(lower, zone, now);
        if (once.isPresent()) {
            return once.get();
        }
        throw invalid();
    }

    /**
     * The next {@code count} times a parsed schedule fires, in the given zone.
     *
     * @throws ApiException a validation failure ({@code field} {@code "text"}) when the cron cannot
     *     be read - a stored row from before a parser rule tightened, say - rather than the cron
     *     library's own {@link IllegalArgumentException}, which every caller would answer with a 500
     */
    // @find: next run times, preview upcoming runs
    public static List<Instant> nextRuns(ParsedSchedule schedule, ZoneId zone, Instant from, int count) {
        if ("once".equals(schedule.kind())) {
            return schedule.runAt() != null && schedule.runAt().isAfter(from) ? List.of(schedule.runAt()) : List.of();
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(schedule.cron());
        } catch (IllegalArgumentException | NullPointerException unreadable) {
            throw ApiException.validation("text", "That timetable could not be read. Describe it again in plain words.")
                    .with("examples", EXAMPLES);
        }
        List<Instant> runs = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.ofInstant(from, zone);
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) {
                break;
            }
            runs.add(next.toInstant());
            cursor = next;
        }
        return runs;
    }

    private static ApiException invalid() {
        return ApiException.validation("text", "That is not a schedule this platform understands yet.")
                .with("examples", EXAMPLES);
    }

    // ---- ISO instant and raw cron, tried before any natural-language pattern ----------------

    private static final Pattern ISO_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[tT ]\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?([zZ]|[+-]\\d{2}:?\\d{2})$");

    private static Optional<ParsedSchedule> tryIsoInstant(String original, ZoneId zone, Instant now) {
        if (!ISO_INSTANT.matcher(original).matches()) {
            return Optional.empty();
        }
        String normalised = original.replace(' ', 'T').replace('t', 'T').replace('z', 'Z');
        try {
            Instant runAt = Instant.parse(normalised);
            if (!runAt.isAfter(now)) {
                throw ApiException.validation("text", "That time has already passed.")
                        .with("examples", EXAMPLES);
            }
            return Optional.of(new ParsedSchedule("once", null, runAt, describeOnce(runAt, zone)));
        } catch (DateTimeParseException notAnInstant) {
            return Optional.empty();
        }
    }

    private static Optional<ParsedSchedule> tryRawCron(String original) {
        String[] parts = original.trim().split("\\s+");
        if (parts.length != 5 && parts.length != 6) {
            return Optional.empty();
        }
        // A phrase like "every monday and thursday at 2pm" happens to be six words too; only
        // something built entirely from cron-field characters is even attempted as one, and
        // {@link CronExpression#isValidExpression} is the real gate against a false positive.
        if (!original.matches("(?i)^[0-9A-Za-z*/,\\-? ]+$")) {
            return Optional.empty();
        }
        String candidate = parts.length == 6 ? original.trim() : "0 " + original.trim();
        if (!CronExpression.isValidExpression(candidate)) {
            return Optional.empty();
        }
        return Optional.of(
                new ParsedSchedule("recurring", candidate, null, "As set by the cron expression " + candidate));
    }

    // ---- Recurring phrases ------------------------------------------------------------------

    // At most four digits: a longer number is not a cadence anyone means, and Integer.parseInt on
    // one would throw outside the validation path (a 500 rather than a readable refusal).
    private static final Pattern EVERY_N_MINUTES = Pattern.compile("^every\\s+(\\d{1,4})\\s+minutes?$");
    private static final Pattern EVERY_N_HOURS = Pattern.compile("^every\\s+(\\d{1,4})\\s+hours?$");

    /*
     * A cron step restarts at the top of every hour (minutes) or every day (hours): "*\/45" fires at
     * :00 and :45, a 45 then a 15 minute gap, and "*\/7" hours leaves a 3 hour gap at midnight. Only
     * a step that divides its period evenly keeps the rhythm its own description promises, so those
     * are the only ones accepted.
     */
    private static final List<Integer> EVEN_MINUTES = List.of(5, 6, 10, 12, 15, 20, 30);
    private static final List<Integer> EVEN_HOURS = List.of(1, 2, 3, 4, 6, 8, 12);
    private static final Pattern HOURLY = Pattern.compile("^(?:every\\s+hour|hourly)$");
    private static final Pattern DAILY = Pattern.compile("^(?:every\\s+day|daily)(?:\\s+at\\s+(.+))?$");
    private static final Pattern WEEKDAYS = Pattern.compile("^(?:every\\s+weekday|weekdays)(?:\\s+at\\s+(.+))?$");
    private static final Pattern WEEKENDS = Pattern.compile("^(?:every\\s+weekend|weekends)(?:\\s+at\\s+(.+))?$");
    private static final Pattern DAY_LIST =
            Pattern.compile("^every\\s+([a-z]+(?:(?:\\s*,\\s*|\\s+and\\s+)[a-z]+)*)\\s+at\\s+(.+)$");
    private static final Pattern WEEK_ON_DAY = Pattern.compile("^every\\s+week\\s+on\\s+([a-z]+)\\s+at\\s+(.+)$");
    private static final Pattern MONTH_ON_NTH =
            Pattern.compile("^every\\s+month\\s+on\\s+the\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+at\\s+(.+)$");
    private static final Pattern NTH_OF_MONTH =
            Pattern.compile("^on\\s+the\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+of\\s+every\\s+month\\s+at\\s+(.+)$");
    private static final Pattern LAST_DAY_OF_MONTH =
            Pattern.compile("^on\\s+the\\s+last\\s+day\\s+of\\s+every\\s+month\\s+at\\s+(.+)$");

    private static Optional<ParsedSchedule> tryRecurring(String lower, ZoneId zone, Instant now) {
        Matcher m;

        if ((m = EVERY_N_MINUTES.matcher(lower)).matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n > 0 && n % 60 == 0) {
                // "every 120 minutes" is "every 2 hours", and is held to the hours rule.
                return Optional.of(everyNHours(n / 60));
            }
            if (n < 5) {
                throw ApiException.validation("text", "A schedule cannot repeat more often than every 5 minutes.")
                        .with("examples", EXAMPLES);
            }
            if (!EVEN_MINUTES.contains(n)) {
                throw uneven("Every " + n + " minutes", n);
            }
            return Optional.of(recurring("0 */" + n + " * * * *", "Every " + n + " minutes"));
        }
        if ((m = EVERY_N_HOURS.matcher(lower)).matches()) {
            return Optional.of(everyNHours(Integer.parseInt(m.group(1))));
        }
        if (HOURLY.matcher(lower).matches()) {
            return Optional.of(recurring("0 0 * * * *", "Every hour"));
        }
        if ((m = DAILY.matcher(lower)).matches()) {
            LocalTime time = timeOrDefault(m.group(1));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " * * *", "Every day at " + describeTime(time)));
        }
        if ((m = WEEKDAYS.matcher(lower)).matches()) {
            LocalTime time = timeOrDefault(m.group(1));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " * * MON-FRI",
                    "Every weekday at " + describeTime(time)));
        }
        if ((m = WEEKENDS.matcher(lower)).matches()) {
            LocalTime time = timeOrDefault(m.group(1));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " * * SAT,SUN",
                    "Every weekend at " + describeTime(time)));
        }
        if ((m = WEEK_ON_DAY.matcher(lower)).matches()) {
            String day = dayCode(m.group(1));
            LocalTime time = parseTime(m.group(2));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " * * " + day,
                    "Every " + DAY_FULL_NAME.get(day) + " at " + describeTime(time)));
        }
        if ((m = DAY_LIST.matcher(lower)).matches()) {
            List<String> days = splitDayList(m.group(1));
            LocalTime time = parseTime(m.group(2));
            String cronDays = String.join(",", days);
            String description =
                    "Every " + joinWords(days.stream().map(DAY_FULL_NAME::get).toList()) + " at " + describeTime(time);
            return Optional.of(
                    recurring("0 " + time.getMinute() + " " + time.getHour() + " * * " + cronDays, description));
        }
        if ((m = MONTH_ON_NTH.matcher(lower)).matches()) {
            int day = requireDayOfMonth(Integer.parseInt(m.group(1)));
            LocalTime time = parseTime(m.group(2));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " " + day + " * *",
                    "On the " + ordinal(day) + " of every month at " + describeTime(time)));
        }
        if ((m = NTH_OF_MONTH.matcher(lower)).matches()) {
            int day = requireDayOfMonth(Integer.parseInt(m.group(1)));
            LocalTime time = parseTime(m.group(2));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " " + day + " * *",
                    "On the " + ordinal(day) + " of every month at " + describeTime(time)));
        }
        if ((m = LAST_DAY_OF_MONTH.matcher(lower)).matches()) {
            LocalTime time = parseTime(m.group(1));
            return Optional.of(recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " L * *",
                    "On the last day of every month at " + describeTime(time)));
        }
        return Optional.empty();
    }

    private static ParsedSchedule recurring(String cron, String description) {
        return new ParsedSchedule("recurring", cron, null, description);
    }

    /** "Every N hours", for an N that divides the day: 1 is hourly, 24 is the daily default. */
    private static ParsedSchedule everyNHours(int n) {
        if (n < 1) {
            throw ApiException.validation("text", "A schedule that repeats by the hour needs at least 1 hour.")
                    .with("examples", EXAMPLES);
        }
        if (n == 1) {
            return recurring("0 0 * * * *", "Every hour");
        }
        if (n == 24) {
            LocalTime time = timeOrDefault(null);
            return recurring(
                    "0 " + time.getMinute() + " " + time.getHour() + " * * *", "Every day at " + describeTime(time));
        }
        if (!EVEN_HOURS.contains(n)) {
            throw uneven("Every " + n + " hours", n * 60);
        }
        return recurring("0 0 */" + n + " * * *", "Every " + n + " hours");
    }

    /** Refuses an interval that does not divide its hour or day, naming the nearest ones that do. */
    private static ApiException uneven(String asked, int minutes) {
        List<Integer> even = new ArrayList<>(EVEN_MINUTES);
        EVEN_HOURS.forEach(hours -> even.add(hours * 60));
        even.add(24 * 60);
        Integer below = null;
        Integer above = null;
        for (int candidate : even) {
            if (candidate < minutes) {
                below = candidate;
            } else if (candidate > minutes && above == null) {
                above = candidate;
            }
        }
        List<String> nearest = new ArrayList<>();
        if (below != null) {
            nearest.add(describeInterval(below));
        }
        if (above != null) {
            nearest.add(describeInterval(above));
        }
        String problem = minutes > 24 * 60
                ? asked + " is longer than a day, which a schedule cannot repeat on yet. Try every day instead."
                : asked + " would leave uneven gaps through the " + (minutes < 60 ? "hour" : "day") + ". Try "
                        + String.join(" or ", nearest) + " instead.";
        return ApiException.validation("text", problem)
                .with("suggestions", nearest)
                .with("examples", EXAMPLES);
    }

    private static String describeInterval(int minutes) {
        if (minutes == 24 * 60) {
            return "every day";
        }
        if (minutes == 60) {
            return "every hour";
        }
        return minutes % 60 == 0 ? "every " + minutes / 60 + " hours" : "every " + minutes + " minutes";
    }

    private static int requireDayOfMonth(int day) {
        if (day < 1 || day > 28) {
            // 29, 30 and 31 do not exist in every month; "the last day of every month" is the
            // phrase for that, and a month-specific date belongs to a one-off schedule instead.
            throw ApiException.validation("text", "A monthly day must be between 1 and 28, or the last day.")
                    .with("examples", EXAMPLES);
        }
        return day;
    }

    private static LocalTime timeOrDefault(String atClause) {
        return atClause == null || atClause.isBlank() ? LocalTime.of(9, 0) : parseTime(atClause);
    }

    private static String dayCode(String token) {
        String code = DAY_ABBREVIATION.get(token.strip());
        if (code == null) {
            throw invalid();
        }
        return code;
    }

    private static List<String> splitDayList(String listText) {
        String[] tokens = listText.split("\\s*,\\s*|\\s+and\\s+");
        Set<String> codes = new LinkedHashSet<>();
        for (String token : tokens) {
            codes.add(dayCode(token));
        }
        if (codes.isEmpty()) {
            throw invalid();
        }
        return codes.stream().sorted(Comparator.comparing(DAY_ORDER::get)).toList();
    }

    private static String joinWords(List<String> words) {
        if (words.size() == 1) {
            return words.get(0);
        }
        if (words.size() == 2) {
            return words.get(0) + " and " + words.get(1);
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                joined.append(i == words.size() - 1 ? ", and " : ", ");
            }
            joined.append(words.get(i));
        }
        return joined.toString();
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    // ---- One-off phrases --------------------------------------------------------------------

    // Bounded for the same reason as the recurring counts: an unbounded number overflows the parse.
    private static final Pattern IN_DURATION =
            Pattern.compile("^in\\s+(\\d{1,4})\\s+(minute|minutes|hour|hours|day|days)$");
    private static final Pattern TOMORROW = Pattern.compile("^tomorrow(?:\\s+at\\s+(.+))?$");
    private static final Pattern TODAY = Pattern.compile("^today\\s+at\\s+(.+)$");
    private static final Pattern ON_ISO_DATE =
            Pattern.compile("^on\\s+(\\d{4}-\\d{2}-\\d{2})(?:[ t](\\d{1,2}:\\d{2}))?$");
    private static final Pattern ON_DAY_MONTH =
            Pattern.compile("^on\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)(?:\\s+(\\d{4}))?(?:\\s+at\\s+(.+))?$");

    private static Optional<ParsedSchedule> tryOnce(String lower, ZoneId zone, Instant now) {
        Matcher m;

        if ((m = IN_DURATION.matcher(lower)).matches()) {
            long n = Long.parseLong(m.group(1));
            Duration duration =
                    switch (m.group(2)) {
                        case "minute", "minutes" -> Duration.ofMinutes(n);
                        case "hour", "hours" -> Duration.ofHours(n);
                        default -> Duration.ofDays(n);
                    };
            Instant runAt = now.plus(duration);
            return Optional.of(once(runAt, zone));
        }
        if ((m = TOMORROW.matcher(lower)).matches()) {
            LocalTime time = timeOrDefault(m.group(1));
            LocalDate date = LocalDate.ofInstant(now, zone).plusDays(1);
            Instant runAt = ZonedDateTime.of(date, time, zone).toInstant();
            return Optional.of(once(requireFuture(runAt, now), zone));
        }
        if ((m = TODAY.matcher(lower)).matches()) {
            LocalTime time = parseTime(m.group(1));
            LocalDate date = LocalDate.ofInstant(now, zone);
            Instant runAt = ZonedDateTime.of(date, time, zone).toInstant();
            return Optional.of(once(requireFuture(runAt, now), zone));
        }
        if ((m = ON_ISO_DATE.matcher(lower)).matches()) {
            LocalDate date = LocalDate.parse(m.group(1));
            LocalTime time = m.group(2) == null ? LocalTime.of(9, 0) : parseTime(m.group(2));
            Instant runAt = ZonedDateTime.of(date, time, zone).toInstant();
            return Optional.of(once(requireFuture(runAt, now), zone));
        }
        if ((m = ON_DAY_MONTH.matcher(lower)).matches()) {
            int day = Integer.parseInt(m.group(1));
            Month month = MONTH_NAME.get(m.group(2));
            if (month == null) {
                throw invalid();
            }
            LocalTime time = timeOrDefault(m.group(4));
            if (m.group(3) != null) {
                int year = Integer.parseInt(m.group(3));
                Instant runAt = dateTimeOf(year, month, day, time, zone);
                return Optional.of(once(requireFuture(runAt, now), zone));
            }
            // No year given: the nearest occurrence of that date and time that is still ahead.
            int currentYear = LocalDate.ofInstant(now, zone).getYear();
            Instant candidate = dateTimeOf(currentYear, month, day, time, zone);
            if (!candidate.isAfter(now)) {
                candidate = dateTimeOf(currentYear + 1, month, day, time, zone);
            }
            return Optional.of(once(candidate, zone));
        }
        return Optional.empty();
    }

    private static Instant dateTimeOf(int year, Month month, int day, LocalTime time, ZoneId zone) {
        try {
            LocalDate date = LocalDate.of(year, month, day);
            return ZonedDateTime.of(date, time, zone).toInstant();
        } catch (DateTimeException e) {
            throw ApiException.validation("text", "That date does not exist.").with("examples", EXAMPLES);
        }
    }

    private static Instant requireFuture(Instant runAt, Instant now) {
        if (!runAt.isAfter(now)) {
            throw ApiException.validation(
                            "text", "That time has already passed today; try a later time or a later date.")
                    .with("examples", EXAMPLES);
        }
        return runAt;
    }

    private static ParsedSchedule once(Instant runAt, ZoneId zone) {
        return new ParsedSchedule("once", null, runAt, describeOnce(runAt, zone));
    }

    private static final DateTimeFormatter ONCE_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private static String describeOnce(Instant runAt, ZoneId zone) {
        ZonedDateTime at = ZonedDateTime.ofInstant(runAt, zone);
        return "Once, on " + ONCE_DATE.format(at) + " at " + describeTime(at.toLocalTime());
    }
}
