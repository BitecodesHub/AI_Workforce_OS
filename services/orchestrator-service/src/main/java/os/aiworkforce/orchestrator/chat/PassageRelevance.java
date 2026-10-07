package os.aiworkforce.orchestrator.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a message should be answered from documents at all, and which of the passages
 * a search returned actually bear on it.
 *
 * <p>A question about the agent itself ("What is your name"), a greeting or a thank-you is not a
 * question about the workspace's documents, and a search for it only returns whatever slide deck
 * shares the most common words. Such a message is never searched for. For any other message the
 * passages returned are filtered on the question's distinctive terms - stopwords such as "what",
 * "is" and "your" do not count - so a passage that shares only filler with the question is dropped.
 */
public final class PassageRelevance {

    private PassageRelevance() {}

    /** Below this a passage's own score is noise. */
    static final double MIN_SCORE = 0.05;

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final Pattern META = Pattern.compile(
            "\\b(who are you|who is this|what are you|what is your (name|role|job|purpose)|what'?s your (name|role|job)"
                    + "|your name|what can you do|what do you do|what are you able|how can you help|how are you"
                    + "|introduce yourself|tell me about yourself|about yourself|are you (an? )?(ai|bot|human|real|robot)"
                    + "|who (made|built|created) you|what should i call you|can you help me"
                    + "|(what|which) (\\w+ )?(abilities|capabilities|skills|tools|permissions|access|integrations)"
                    + " (do|have|can) you|what (else )?can you do (in|with|on)\\b"
                    + "|what are your (\\w+ )?(abilities|capabilities|skills|tools|permissions))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SMALL_TALK = Pattern.compile(
            "^\\s*(hi|hello|hey|hiya|yo|thanks|thank you|thx|cheers|ok|okay|cool|great|nice|good (morning|afternoon|evening|night)"
                    + "|bye|goodbye|see you|sure|yes|no)\\b[\\s\\p{Punct}\\p{L}]{0,40}$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "what", "which", "who", "whom",
            "how", "why", "when", "where", "does", "did", "have", "has", "had", "not", "but", "you", "your", "yours",
            "our", "can", "about", "into", "than", "then", "them", "they", "their", "there", "will", "would", "should",
            "could", "may", "might", "also", "any", "all", "been", "being", "its", "per", "use", "using", "used",
            "make", "made", "need", "get", "got", "give", "tell", "show", "list", "find", "know", "want", "like",
            "some", "more", "most", "very", "just", "only", "each", "other", "such", "please", "mine", "his", "her",
            "she", "him", "himself", "yourself", "myself", "own", "thanks", "thank", "hello", "hey", "name", "call");

    /*
     * An instruction to act through a connected service - "create a new repo named test repo",
     * "open an issue about the login bug" - starts with a verb like these...
     */
    private static final Pattern ACTION = Pattern.compile(
            "^\\s*(please\\s+)?(create|make|open|add|merge|close|reopen|update|delete|remove|post|send|schedule|book"
                    + "|draft|comment|assign|archive|cancel|refund|rename|invite|push|commit|set up|start|move|list"
                    + "|fork|clone|label|tag|reply|forward|edit|change|new)\\b",
            Pattern.CASE_INSENSITIVE);

    /* ...and names a thing that lives in a connected service rather than in a document. */
    private static final Pattern CONNECTOR_THING = Pattern.compile(
            "\\b(repos?|repositor(y|ies)|branch(es)?|pull requests?|prs?|issues?|tickets?|channels?|emails?|messages?"
                    + "|events?|meetings?|invites?|invoices?|tasks?|cards?|commits?|gists?|webhooks?|contacts?|deals?"
                    + "|leads?|calendar|inbox|github|slack|jira|linear|notion|asana|zendesk|hubspot|salesforce|gmail"
                    + "|outlook|teams|zoom|stripe|confluence)\\b",
            Pattern.CASE_INSENSITIVE);

    /*
     * Words that say what to do in a connected service, not what the request is about. In such a
     * request they would match any slide that happens to say "create" or "new", so they never
     * count towards grounding it in a document.
     */
    private static final Set<String> ACTION_FILLER = Set.of(
            "create", "new", "named", "name", "called", "titled", "repo", "repos", "repository", "repositories",
            "branch", "branches", "pull", "request", "requests", "issue", "issues", "ticket", "tickets", "private",
            "public", "add", "open", "make", "merge", "close", "update", "delete", "remove", "post", "send", "channel",
            "message", "email", "comment", "github", "slack", "jira", "linear", "notion", "asana", "zendesk",
            "hubspot", "salesforce", "gmail", "outlook", "teams", "zoom", "stripe", "confluence", "task", "event",
            "meeting", "calendar", "draft", "schedule", "assign", "label", "account", "organisation", "organization");

    /**
     * Whether the message is about the agent, a greeting or small talk, so that no document can be
     * what it asks for. A message that names documents ("what documents do you have") is not.
     */
    public static boolean isConversational(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String stripped = text.replaceAll("@[\\p{L}\\p{N}_-]+", " ").strip();
        if (stripped.isEmpty()) {
            return true;
        }
        if (IntentDetector.refersToDocuments(stripped)) {
            return false;
        }
        if (META.matcher(stripped).find() || SMALL_TALK.matcher(stripped).matches()) {
            return true;
        }
        return distinctiveTerms(stripped).isEmpty();
    }

    /** The message's subject words, stemmed, without repeats and without filler. */
    static Set<String> distinctiveTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                terms.add(stem(token));
            }
        }
        return terms;
    }

    /**
     * The passages that bear on the query: score above the floor and, for a query with more than
     * four distinctive terms, at least two of them present in the passage's text, heading or title.
     */
    public static List<KnowledgeClient.Passage> relevant(String query, List<KnowledgeClient.Passage> passages) {
        if (passages == null || passages.isEmpty() || isConversational(query)) {
            return List.of();
        }
        String stripped = query.replaceAll("@[\\p{L}\\p{N}_-]+", " ").strip();
        boolean action = isConnectorAction(stripped);
        Set<String> terms = action ? subjectTerms(stripped) : distinctiveTerms(stripped);
        if (terms.isEmpty()) {
            return List.of();
        }
        // An action in a connected service is grounded in a document only by what it is about, and
        // then by two of those words at least, when it has two: a single shared word such as a
        // project's name is not reason enough to put a slide in front of the agent.
        int required = action ? Math.min(2, terms.size()) : terms.size() <= 4 ? 1 : 2;
        return passages.stream()
                .filter(p -> p.score() >= MIN_SCORE)
                .filter(p -> covered(terms, p) >= required)
                .toList();
    }

    /**
     * Whether the message asks for something to be done in a connected service, such as "create a
     * new repo named test repo", rather than asking what the documents say. A message that names
     * documents is never one.
     */
    static boolean isConnectorAction(String text) {
        if (text == null || IntentDetector.refersToDocuments(text)) {
            return false;
        }
        return ACTION.matcher(text).find() && CONNECTOR_THING.matcher(text).find();
    }

    /* The distinctive terms of an action request, without the words that only say what to do. */
    private static Set<String> subjectTerms(String text) {
        Set<String> filler = new LinkedHashSet<>();
        ACTION_FILLER.forEach(word -> filler.add(stem(word)));
        Set<String> terms = new LinkedHashSet<>();
        for (String term : distinctiveTerms(text)) {
            if (!filler.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static int covered(Set<String> terms, KnowledgeClient.Passage passage) {
        Set<String> present = new LinkedHashSet<>();
        for (String text : new String[] {passage.documentTitle(), passage.heading(), passage.content()}) {
            if (text == null) {
                continue;
            }
            Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                present.add(stem(matcher.group()));
            }
        }
        int count = 0;
        for (String term : terms) {
            if (present.contains(term)) {
                count++;
            }
        }
        return count;
    }

    static String stem(String word) {
        String w = word;
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 5 && w.endsWith("ing")) {
            w = w.substring(0, w.length() - 3);
        } else if (w.length() > 4 && w.endsWith("ed")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 4 && w.endsWith("es")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.length() > 4 && w.endsWith("e")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }
}
