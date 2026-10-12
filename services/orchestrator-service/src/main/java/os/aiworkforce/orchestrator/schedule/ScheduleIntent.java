// @find: schedule intent, does the person ask for a schedule, clearly asks for recurring or timed work, a date that describes the subject, starts next Monday, remind me at, schedule a meeting is not a schedule, ScheduleIntent
// @what: Decides whether a chat message clearly asks for work to be set up on a schedule, as opposed to a request that only mentions a date or a time.
// @flow: Called by IntentDetector (a message without a mention) and CoordinatorService (a message that mentions one agent) before a message becomes a schedule; uses ScheduleExtractor and ScheduleParser.
package os.aiworkforce.orchestrator.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells "every Monday at 9am summarise competitor news" (set something up to happen later) from
 * "draft a welcome email for Jane, who starts next Monday" (do something now, about a day).
 *
 * <p>{@link ScheduleExtractor} finds a timing phrase wherever it can be read. That is not enough
 * to turn a message into a schedule: most dates in a request describe its subject - when someone
 * starts, when a meeting is, when an invoice is due - and a schedule saved by mistake means the
 * work the person asked for is not done now. A message asks for a schedule only when one of these
 * holds:
 *
 * <ul>
 *   <li>It says so: it opens with "set a reminder" or "schedule ..." (but not "schedule a meeting",
 *       which is calendar work to do now), or says "remind me", "on a schedule" or "make it
 *       recurring" - or it opens with "remind" someone at a time that is not part of what they are
 *       reminded of ("remind HR tomorrow at 3pm to send onboarding emails", but not "remind the team
 *       that the office closes at 4pm").
 *   <li>It is nothing but a timing phrase.
 *   <li>The timing phrase opens the request ("Tomorrow at 9am send the board pack"), and is not
 *       the subject of a sentence ("Next Monday is Jane's first day").
 *   <li>The phrase follows the work rather than describing something in it - the word before it is
 *       not "is", "who starts", "due", "for", "the meeting" and the like - and either it repeats
 *       and ends the request ("Send me the pipeline summary every weekday at 8:30am"), or the
 *       request opens with an action that is done at a time, such as send, post or check
 *       ("Send the board pack to the directors tomorrow at 9am").
 * </ul>
 *
 * <p>Everything else is ordinary work. A miss costs little: the agent that takes the work can still
 * set up a schedule itself when the person asked for one (AgentScheduleTool).
 */
public final class ScheduleIntent {

    /** Static utility; not instantiated. */
    private ScheduleIntent() {}

    /** A mention, or a polite opening, before the request itself: "@Research", "please", "can you", "I'd like you to". */
    private static final Pattern OPENER = Pattern.compile(
            "(?i)^(?:@[\\p{L}\\p{N}][\\p{L}\\p{N}-]*|please|pls|kindly|also|and|then|now|ok|okay|hi|hey"
                    + "|(?:can|could|would|will)\\s+you(?:\\s+please)?|i\\s+(?:want|need)\\s+you\\s+to"
                    + "|i(?:'d|\\s+would)\\s+like\\s+you\\s+to|go\\s+ahead\\s+and)(?=[\\s,;:.!-]|$)[\\s,;:.!-]*");

    /** A request that opens by asking for a reminder or a schedule. "Schedule a meeting" is calendar work, not a schedule. */
    private static final Pattern EXPLICIT_LEAD = Pattern.compile(
            "(?i)^(?:set\\s+(?:up\\s+)?(?:a|an)\\s+(?:[\\p{L}-]+\\s+)?reminder\\b"
                    + "|(?:set\\s+up|create|add|make)\\s+(?:a|an)\\s+(?:new\\s+)?(?:schedule\\b|(?:recurring|repeating)\\s+\\w+"
                    + "|(?:daily|weekly|monthly|hourly|regular)\\s+(?:task|job|schedule|reminder|check)\\b)"
                    + "|schedule\\b(?!\\s+(?:(?:a|an|the|my|our|your|their|some)\\s+)?(?:[\\p{L}-]+\\s+)?"
                    + "(?:meeting|call|appointment|interview|event|session|catch[- ]?up|stand[- ]?up|demo|chat|lunch"
                    + "|dinner|visit|review|workshop|webinar|training|induction|1:1|one-on-one)s?\\b))");

    /**
     * A request that opens with "remind" someone. It asks for a reminder at the time named, unless
     * the time belongs to what the reminder is about: "remind the team that the office closes at 4pm".
     */
    private static final Pattern REMIND_LEAD = Pattern.compile("(?i)^remind\\b");

    /** Words anywhere in a request that ask for something to happen later or again. */
    private static final Pattern EXPLICIT_ANYWHERE = Pattern.compile(
            "(?i)\\bremind\\s+(?:me|us)\\b|\\bon\\s+a\\s+schedule\\b|\\bas\\s+a\\s+(?:recurring|repeating|scheduled)\\b"
                    + "|\\bmake\\s+(?:it|this|that)\\s+(?:recurring|repeat)\\b|\\bset\\s+(?:it|this|that)\\s+up\\s+to\\s+"
                    + "(?:run|repeat)\\b|\\bschedule\\s+(?:it|this|that)\\b");

    /** One word, with an apostrophe or hyphen inside it. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*");

    /** A word just after a phrase that opens the request which makes the phrase the subject of a sentence: "Next Monday is ...". */
    private static final Set<String> COPULAS =
            Set.of("is", "was", "will", "would", "are", "were", "has", "had", "should", "could", "might", "works", "suits");

    /**
     * Words that, just before a timing phrase, tie it to something being described: being and
     * relative words ("is", "who"), what a thing does ("starts", "closes", "due") and past
     * participles ("booked", see {@link #describesThing}).
     */
    private static final Set<String> BEING = Set.of(
            "is", "are", "was", "were", "be", "been", "being", "will", "would", "who", "which", "that", "whose", "when",
            "where", "while", "than", "as", "it's", "that's", "he's", "she's", "they're", "due", "held",
            "start", "starts", "started", "starting", "begin", "begins", "began", "beginning", "end", "ends", "ending",
            "finish", "finishes", "finishing", "close", "closes", "closing", "open", "opens", "opening", "run", "runs",
            "running", "happen", "happens", "happening", "occur", "occurs", "meet", "meets", "come", "comes", "coming",
            "arrive", "arrives", "arriving", "leave", "leaves", "leaving", "return", "returns", "back", "fall", "falls",
            "land", "lands", "launch", "launches", "go", "goes", "going", "join", "joins", "joining", "work", "works",
            "working", "live", "off", "away");

    /** Events a date belongs to: "the board meeting tomorrow at 10am", "her first day next Monday". */
    private static final Set<String> EVENTS = Set.of(
            "meeting", "meetings", "call", "calls", "appointment", "interview", "event", "session", "workshop", "webinar",
            "training", "induction", "shift", "deadline", "class", "lunch", "dinner", "breakfast", "party", "standup",
            "stand-up", "catch-up", "catchup", "demo", "presentation", "conference", "flight", "trip", "delivery",
            "visit", "day", "date", "holiday", "review", "outage", "release", "game", "match", "show", "service");

    /** Prepositions and determiners that make a date part of a thing or a deadline: "the agenda for tomorrow at 9am", "by Friday". */
    private static final Set<String> LINKING = Set.of(
            "for", "by", "before", "after", "until", "till", "since", "from", "of", "about", "during", "through",
            "around", "between", "on", "at", "in", "to", "our", "my", "your", "their", "his", "her", "its", "the", "a",
            "an", "this", "last", "first");

    /** Actions a person asks to be done at a time ("send ... tomorrow at 9am"); a request opening with another verb is work now. */
    private static final Set<String> TIMED_ACTIONS = Set.of(
            "send", "post", "publish", "email", "e-mail", "message", "text", "ping", "notify", "alert", "announce",
            "remind", "check", "run", "call", "ring", "share", "summarise", "summarize", "report", "compile", "collect",
            "gather", "fetch", "pull", "export", "archive", "chase", "follow", "monitor", "scan", "sync", "refresh",
            "upload", "start", "tweet", "back", "backup");

    // @find: asks for schedule, clearly asks for timed work, schedule or work
    /**
     * Whether {@code text} clearly asks for work to be set up to happen later or regularly, by the
     * rules in the class comment. Pure; reads no clock but {@code now}.
     *
     * <p>A question (ending in "?") or a message with no timing phrase is never a schedule. The
     * caller still reads the schedule itself (when, how often, what to do) with {@link
     * ScheduleExtractor}; this only answers whether the person asked for one.
     *
     * @param zone the zone times of day are read in
     * @param now the reference instant for relative phrases
     */
    public static boolean asksForSchedule(String text, ZoneId zone, Instant now) {
        if (text == null) {
            return false;
        }
        String message = text.strip();
        if (message.isEmpty() || message.endsWith("?")) {
            return false;
        }
        int lead = leadEnd(message);
        String request = message.substring(lead);
        if (request.isBlank()) {
            return false;
        }
        if (EXPLICIT_LEAD.matcher(request).lookingAt() || EXPLICIT_ANYWHERE.matcher(request).find()) {
            return true;
        }
        boolean remind = REMIND_LEAD.matcher(request).lookingAt();
        // Nothing but a timing phrase ("every weekday at 9am"): the person is setting up a schedule.
        if (ScheduleParser.tryParse(request, zone, now).isPresent()) {
            return true;
        }
        Optional<ScheduleExtractor.Extraction> found = ScheduleExtractor.extract(message, zone, now);
        if (found.isEmpty()) {
            return false;
        }
        String phrase = found.get().phrase();
        int from = message.indexOf(phrase, lead);
        if (from < 0) {
            from = message.indexOf(phrase);
        }
        if (from < 0) {
            return false;
        }
        String before = message.substring(0, from);
        String after = message.substring(from + phrase.length());
        boolean recurring = "recurring".equals(found.get().parsed().kind());

        if (from <= lead || leadEnd(before) >= before.length()) {
            // The phrase opens the request: when to do what follows - unless it is what the sentence is about.
            return !COPULAS.contains(firstWord(after));
        }
        String previous = lastWord(before);
        if (remind) {
            // "Remind HR tomorrow at 3pm to ...", not "remind the team that the office closes at 4pm".
            return !describesThing(previous);
        }
        if (describesThing(previous) || LINKING.contains(previous)) {
            return false;
        }
        boolean timedAction = TIMED_ACTIONS.contains(firstWord(request));
        if (recurring) {
            boolean atEnd = after.strip().matches("[\\p{Punct}\\s]*");
            return atEnd || timedAction;
        }
        return timedAction;
    }

    /** Where the request itself starts: after any mentions and polite openings ("@HR please", "can you"). */
    private static int leadEnd(String text) {
        int at = 0;
        while (at < text.length()) {
            int skipped = at;
            while (skipped < text.length() && Character.isWhitespace(text.charAt(skipped))) {
                skipped++;
            }
            Matcher opener = OPENER.matcher(text);
            opener.region(skipped, text.length());
            if (!opener.lookingAt() || opener.end() == skipped) {
                return skipped;
            }
            at = opener.end();
        }
        return at;
    }

    /** Whether a word just before a timing phrase ties the phrase to something being described, or to an event. */
    private static boolean describesThing(String word) {
        if (word.isEmpty()) {
            return false;
        }
        // A past participle ("booked", "planned", "scheduled") ties the date to the thing it describes.
        return BEING.contains(word) || EVENTS.contains(word) || (word.length() > 3 && word.endsWith("ed"));
    }

    /** The first word of the text, in lower case; empty when there is none. */
    private static String firstWord(String text) {
        Matcher word = WORD.matcher(text);
        return word.find() ? word.group().toLowerCase(Locale.ROOT) : "";
    }

    /** The last word of the text, in lower case; empty when there is none. */
    private static String lastWord(String text) {
        Matcher word = WORD.matcher(text);
        String last = "";
        while (word.find()) {
            last = word.group();
        }
        return last.toLowerCase(Locale.ROOT);
    }
}
