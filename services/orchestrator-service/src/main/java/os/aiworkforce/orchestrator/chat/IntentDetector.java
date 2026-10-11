// @find: intent detector, what did the person mean, schedule intent, documents intent, work intent, question about documents, timing phrase, IntentDetector, @mention wins, refers to documents
// @what: Classifies a chat message without an @mention as a schedule request, a question about documents, or work to route.
// @flow: Called by CoordinatorService; uses SchedulePreviewer.
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

    /** Not instantiable; every member is static. */
    private IntentDetector() {}

    /**
     * What a message without a mention is asking for.
     *
     * <p>{@code WORK} is routed to agents by the coordinator, {@code DOCUMENTS} is answered from the
     * workspace's document passages, and {@code SCHEDULE} is turned into a schedule preview.
     */
    public enum Intent {
        WORK,
        DOCUMENTS,
        SCHEDULE
    }

    /** Words and phrases that mark a sentence as naming a recurrence or a time, not just an action. */
    private static final Pattern TIMING_MARKER =
            Pattern.compile("(?i)\\b(every|each|daily|weekly|monthly|hourly|everyday|tomorrow|today|tonight)\\b"
                    + "|\\bat\\s+\\d|\\bin\\s+(\\d+|an?|half\\s+an)\\s*(minute|minutes|mins?|hour|hours|hrs?|day|days)\\b"
                    + "|\\b(on|next|this)\\s+(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b");

    /** First words that make a sentence read as a question. */
    private static final Set<String> QUESTION_STARTERS =
            Set.of("what", "who", "where", "when", "why", "how", "which", "does", "do", "is", "are", "can");

    /**
     * Verbs that mark a sentence as a request for something to be done, so it is routed as work and not as a document question.
     */
    private static final Set<String> ACTION_VERBS = Set.of(
            "draft",
            "send",
            "write",
            "create",
            "schedule",
            "book",
            "post",
            "summarise",
            "summarize",
            "prepare",
            "update",
            "open",
            "file",
            "reply",
            "email",
            "research",
            "compile",
            "review",
            "triage",
            "plan",
            "help",
            "explain",
            "make",
            "give",
            "tell",
            "calculate",
            "translate",
            "list",
            "suggest",
            "fix",
            "check",
            "rewrite",
            "edit",
            "improve",
            "brainstorm",
            "outline",
            "analyse",
            "analyze",
            "organise",
            "organize",
            "decide",
            "recommend",
            "estimate");

    /** A phrase that pins down when the work should happen, so it can be lifted out of an instruction. */
    private static final Pattern TIMING_PHRASE =
            Pattern.compile("(?i)\\b(every\\s+[\\p{L}]+(\\s+(at|on)\\s+[\\p{L}0-9:]+)?"
                    + "|each\\s+[\\p{L}]+(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|daily(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|weekly(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|monthly(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|tomorrow(\\s+at\\s+[\\p{L}0-9:]+)?"
                    + "|today\\s+at\\s+[\\p{L}0-9:]+"
                    + "|in\\s+\\d+\\s*(minute|minutes|hour|hours|day|days))\\b");

    /**
     * Mentions decide work by themselves; call this only when the message has none.
     *
     * <p>Called by {@code CoordinatorService}. A message is a {@code SCHEDULE} when it carries a
     * timing marker, does not end in a question mark and the previewer can read a schedule from it;
     * otherwise a {@code DOCUMENTS} request when it starts with a search phrase or is a question
     * without an action verb; otherwise {@code WORK}. Empty text is {@code WORK}. Pure apart from the
     * previewer call, which only parses; nothing is stored.
     */
    // @find: detect intent of message, schedule or documents or work
    public static Intent detect(String text, ZoneId zone, Instant now, SchedulePreviewer previewer) {
        if (text == null || text.isBlank()) {
            return Intent.WORK;
        }
        // A question about a time ("what happened today at 3pm?") asks for an answer, not a schedule.
        if (hasTimingMarker(text) && !text.strip().endsWith("?") && previewer.tryParse(text, zone, now).isPresent()) {
            return Intent.SCHEDULE;
        }
        if (looksLikeDocuments(text)) {
            return Intent.DOCUMENTS;
        }
        return Intent.WORK;
    }

    /** Whether the text contains any word or phrase that names a recurrence or a time. */
    private static boolean hasTimingMarker(String text) {
        return TIMING_MARKER.matcher(text).find();
    }

    /** Whether the text is a document search or a question with no action verb in it. */
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

    /** Whether the first word is one of {@link #QUESTION_STARTERS}. */
    private static boolean startsWithQuestionWord(String lower) {
        Matcher firstWord = Pattern.compile("^[\\p{L}]+").matcher(lower);
        return firstWord.find() && QUESTION_STARTERS.contains(firstWord.group());
    }

    /** Whether any word of {@link #ACTION_VERBS} appears as a whole word. */
    private static boolean containsActionVerb(String lower) {
        for (String verb : ACTION_VERBS) {
            if (Pattern.compile("\\b" + Pattern.quote(verb) + "\\b")
                    .matcher(lower)
                    .find()) {
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
     *
     * <p>Only the seam where the phrase was is tidied. Everything else stays exactly as the person
     * wrote it - line breaks, spacing and pasted text included - because this is the standing
     * instruction every run of the schedule works from.
     */
    // @find: remove timing phrase from text
    public static String withoutTimingPhrase(String text) {
        if (text == null) {
            return "";
        }
        Matcher marker = TIMING_PHRASE.matcher(text);
        if (!marker.find()) {
            return text.strip();
        }
        String before = SEAM_END.matcher(text.substring(0, marker.start())).replaceFirst("");
        String after = SEAM_START.matcher(text.substring(marker.end())).replaceFirst("");
        String remainder = before.isEmpty() || after.isEmpty() ? before + after : before + " " + after;
        remainder = SEAM_START.matcher(remainder).replaceFirst("");
        remainder = SEAM_END.matcher(remainder).replaceFirst("").strip();
        return remainder.isBlank() ? text.strip() : remainder;
    }

    /** Leading whitespace and separators left where a timing phrase was removed. */
    private static final Pattern SEAM_START = Pattern.compile("^[\\s,;:]+");
    /** Trailing whitespace and separators left where a timing phrase was removed. */
    private static final Pattern SEAM_END = Pattern.compile("[\\s,;:]+$");

    /** Words that name the workspace's own documents, or the policies and handbooks they hold. */
    private static final Pattern DOCUMENT_REFERENCE = Pattern.compile(
            "\\b(knowledge base|documents?|docs|uploaded|on file|handbooks?|polic(y|ies)|procedures?|"
                    + "guidelines?|playbooks?|manuals?|faqs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Whether a request leans on the workspace's own documents - "using our refund policy", "per
     * the handbook" - so that, when no document turns out to cover it, the agent can be told to say
     * so rather than state company facts it would otherwise have to invent.
     */
    // @find: message refers to documents, asks about knowledge base
    public static boolean refersToDocuments(String text) {
        return text != null && DOCUMENT_REFERENCE.matcher(text).find();
    }
}
