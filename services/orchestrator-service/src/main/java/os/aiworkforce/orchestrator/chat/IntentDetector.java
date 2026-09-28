package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a person meant by a message that was not addressed with an {@code @mention}.
 *
 * <p>A mention is decided before this runs and always wins - somebody who wrote {@code @research}
 * meant work for Research, whatever the sentence around it reads like. Everything else is one of
 * three things: a schedule to set up, a question about documents the workspace holds, or work for
 * the coordinator to route.
 */
public final class IntentDetector {

    private IntentDetector() {}

    public enum Intent {
        WORK,
        DOCUMENTS,
        SCHEDULE
    }

    /** Words and phrases that mark a sentence as naming a recurrence or a time, not just an action. */
    private static final Pattern TIMING_MARKER = Pattern.compile(
            "(?i)\\b(every|each|daily|weekly|monthly|tomorrow|today)\\b|\\bat\\s+\\d|\\bin\\s+\\d+\\s*"
                    + "(minute|minutes|hour|hours)\\b");

    private static final Set<String> QUESTION_STARTERS = Set.of(
            "what", "who", "where", "when", "why", "how", "which", "does", "do", "is", "are", "can");

    private static final Set<String> ACTION_VERBS = Set.of(
            "draft", "send", "write", "create", "schedule", "book", "post", "summarise", "summarize",
            "prepare", "update", "open", "file", "reply", "email", "research", "compile", "review", "triage");

    /** A phrase that pins down when the work should happen, so it can be lifted out of an instruction. */
    private static final Pattern TIMING_PHRASE = Pattern.compile(
            "(?i)\\b(every\\s+[\\p{L}]+(\\s+(at|on)\\s+[\\p{L}0-9:]+)?"
                    + "|each\\s+[\\p{L}]+(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|daily(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|weekly(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|monthly(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|tomorrow(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|today\\s+at\\s+[\\p{L}0-9:]+"
                    + "|in\\s+\\d+\\s*(minute|minutes|hour|hours|day|days))\\b");

    /** Mentions decide work by themselves; call this only when the message has none. */
    public static Intent detect(String text, ZoneId zone, Instant now, SchedulePreviewer previewer) {
        if (text == null || text.isBlank()) {
            return Intent.WORK;
        }
        if (hasTimingMarker(text) && previewer.tryParse(text, zone, now).isPresent()) {
            return Intent.SCHEDULE;
        }
        if (looksLikeDocuments(text)) {
            return Intent.DOCUMENTS;
        }
        return Intent.WORK;
    }

    private static boolean hasTimingMarker(String text) {
        return TIMING_MARKER.matcher(text).find();
    }

    private static boolean looksLikeDocuments(String text) {
        String trimmed = text.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("search") || lower.startsWith("find in")) {
            return true;
        }
        boolean question = trimmed.endsWith("?") || startsWithQuestionWord(lower);
        if (!question) {
            return false;
        }
        return !containsActionVerb(lower);
    }

    private static boolean startsWithQuestionWord(String lower) {
        Matcher firstWord = Pattern.compile("^[\\p{L}]+").matcher(lower);
        return firstWord.find() && QUESTION_STARTERS.contains(firstWord.group());
    }

    private static boolean containsActionVerb(String lower) {
        for (String verb : ACTION_VERBS) {
            if (Pattern.compile("\\b" + Pattern.quote(verb) + "\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The instruction with its timing phrase lifted out, when one can be found - {@code "every
     * weekday at 9am summarise support tickets"} becomes {@code "summarise support tickets"}. The
     * whole text is returned, stripped, when no recognisable phrase is found: a schedule with an
     * instruction nobody can read is worse than one that repeats the timing back.
     */
    public static String withoutTimingPhrase(String text) {
        if (text == null) {
            return "";
        }
        Matcher marker = TIMING_PHRASE.matcher(text);
        if (!marker.find()) {
            return text.strip();
        }
        String remainder = text.substring(0, marker.start()) + " " + text.substring(marker.end());
        remainder = remainder.replaceAll("^[\\s,;:]+", "").replaceAll("[\\s,;:]+$", "");
        remainder = remainder.replaceAll("[ \\t]{2,}", " ").strip();
        return remainder.isBlank() ? text.strip() : remainder;
    }
}
