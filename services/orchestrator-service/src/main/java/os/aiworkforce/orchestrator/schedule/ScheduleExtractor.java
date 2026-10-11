// @find: schedule extractor, find the timing phrase in a sentence, every monday at 9am summarise, remind tomorrow at 3pm, split instruction from timing, schedule from chat sentence, ScheduleExtractor
// @what: Finds the schedule phrase inside an ordinary sentence and splits it from the work to be done.
// @flow: Used by DefaultSchedulePreviewer, CoordinatorService (chat) and AgentScheduleTool
package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads "@Research every Monday at 9am summarise competitor news" or "remind HR tomorrow at 3pm to
 * send onboarding emails" as a timing phrase plus the work.
 *
 * <p>{@link ScheduleParser} only accepts a phrase that is nothing but a schedule. A person writes
 * the schedule inside a sentence, so this tries every run of consecutive words that begins with a
 * word a timing phrase can begin with, longest first, and keeps the longest one the parser reads.
 * The rest of the sentence, with the seam tidied, is the instruction.
 *
 * <p>Two rules keep an ordinary request from turning into a schedule by accident. A recurring
 * phrase with no time of day ("every day", "daily") only counts at the start or the end of the
 * sentence - "summarise what happened every day last week" is a request, not a schedule. And a
 * bare "at 5" is never a time; only "at 5pm" or "at 17:00" is.
 */
public final class ScheduleExtractor {

    /** Static utility; not instantiated. */
    private ScheduleExtractor() {}

    /**
     * A schedule found in a sentence.
     *
     * @param parsed what the timing phrase means
     * @param phrase the timing phrase exactly as written
     * @param instruction the rest of the sentence: the work each run does
     */
    public record Extraction(ParsedSchedule parsed, String phrase, String instruction) {}

    /** The longest phrase tried, in words: "on the last day of every month at 5:30 pm" is ten. */
    private static final int MAX_WORDS = 11;

    /** Words a timing phrase can begin with; only runs starting with one of these are tried. */
    private static final Set<String> STARTERS = Set.of(
            "every", "each", "daily", "weekly", "hourly", "weekdays", "weekends", "everyday", "tomorrow", "today",
            "tonight", "in", "on", "at", "next", "this", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun");

    /** A word that marks a phrase as naming a time of day or a date, not only a rhythm. */
    private static final Pattern ANCHORED =
            Pattern.compile("(?i)\\b(at|tomorrow|today|tonight|in|on|next|this)\\b|\\d");

    /** Matches one whitespace-separated word. */
    private static final Pattern WORD = Pattern.compile("\\S+");
    /** Punctuation trimmed from either end of a candidate phrase; an at sign or hash is kept at the start and a percent sign at the end. */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\p{Punct}&&[^@#]]+|[\\p{Punct}&&[^@#%]]+$");
    /** Joining words and punctuation removed from the start of the remaining instruction. */
    private static final Pattern LEADING_JOINERS = Pattern.compile("(?i)^(?:[,;:\\-\\s]+|(?:and|then|to|please)\\s+)+");
    /** Joining words and punctuation removed from the end of the text before the phrase. */
    private static final Pattern TRAILING_JOINERS = Pattern.compile("(?i)(?:[,;:\\-\\s]+|\\s+(?:and|then))+$");

    // @find: extract schedule from sentence, find timing phrase
    /**
     * The schedule written somewhere in {@code text}, when there is one.
     *
     * <p>Splits the text into words, tries each run that starts with a starter word (longest first) through
     * {@link ScheduleParser#tryParse}, and keeps the longest run that parses and passes the edge rule. Pure:
     * no database or network access. Returns empty for blank text or when nothing parses.
     *
     * @param zone the zone times of day are read in
     * @param now the reference instant for relative phrases such as "in 2 hours"
     */
    public static Optional<Extraction> extract(String text, ZoneId zone, Instant now) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<int[]> words = new ArrayList<>();
        Matcher word = WORD.matcher(text);
        while (word.find()) {
            words.add(new int[] {word.start(), word.end()});
        }
        Extraction best = null;
        int bestLength = 0;
        for (int start = 0; start < words.size(); start++) {
            String first = clean(text.substring(words.get(start)[0], words.get(start)[1])).toLowerCase(Locale.ROOT);
            if (!STARTERS.contains(first)) {
                continue;
            }
            int longest = Math.min(words.size(), start + MAX_WORDS);
            for (int end = longest; end > start; end--) {
                int length = end - start;
                if (length <= bestLength) {
                    break;
                }
                int from = words.get(start)[0];
                int to = words.get(end - 1)[1];
                String phrase = clean(text.substring(from, to));
                if (phrase.isEmpty()) {
                    continue;
                }
                Optional<ParsedSchedule> parsed = ScheduleParser.tryParse(phrase, zone, now);
                if (parsed.isEmpty()) {
                    continue;
                }
                boolean atEdge = start == 0 || end == words.size() || edgeAfterMention(text, words, start);
                if (!atEdge && !ANCHORED.matcher(phrase).find()) {
                    continue;
                }
                String instruction = joinAround(text.substring(0, from), text.substring(to));
                best = new Extraction(parsed.get(), phrase, instruction);
                bestLength = length;
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether everything before word {@code start} is @mentions, so the phrase opens the request. */
    private static boolean edgeAfterMention(String text, List<int[]> words, int start) {
        for (int i = 0; i < start; i++) {
            if (!text.substring(words.get(i)[0], words.get(i)[1]).startsWith("@")) {
                return false;
            }
        }
        return true;
    }

    /** Strips edge punctuation and surrounding whitespace from a candidate phrase. */
    private static String clean(String phrase) {
        return EDGE_PUNCTUATION.matcher(phrase.strip()).replaceAll("").strip();
    }

    /**
     * The sentence with the phrase lifted out. Only the seam is tidied - a joining "to" or "and",
     * stray commas - and everything else stays exactly as written, line breaks included, because
     * this is the standing instruction every run works from.
     */
    private static String joinAround(String before, String after) {
        String head = TRAILING_JOINERS.matcher(before).replaceFirst("");
        String tail = after.replaceFirst("^[\\s,;:]+", "");
        if (head.isBlank()) {
            tail = LEADING_JOINERS.matcher(tail).replaceFirst("");
        } else if (tail.matches("(?s)^[.!?].*")) {
            return (head + tail).strip();
        }
        String joined = head.isBlank() || tail.isBlank() ? head + tail : head + " " + tail;
        return LEADING_JOINERS.matcher(joined.strip()).replaceFirst("").strip();
    }
}
