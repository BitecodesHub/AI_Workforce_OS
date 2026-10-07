package os.aiworkforce.mcp.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/**
 * What a tool is called in plain words, for a person rather than a model.
 *
 * <p>The model is shown each tool as {@code server__tool}, and it sometimes repeats that name in
 * an answer ("github__create_issue: ..."). A person should read "Create an issue in GitHub"
 * instead, the same way the console's own labels read. This is the server's counterpart of the
 * web's tool labels: the phrase comes from the tool's name, the service's name from the catalog.
 */
public final class ToolLabels {

    private ToolLabels() {}

    /* Nouns that are abbreviations in a tool name and words in a sentence. */
    private static final Map<String, String> NOUNS = Map.ofEntries(
            Map.entry("pull", "pull request"),
            Map.entry("pulls", "pull requests"),
            Map.entry("repo", "repository"),
            Map.entry("repos", "repositories"),
            Map.entry("pr", "pull request"),
            Map.entry("prs", "pull requests"));

    /* Verbs that read better as another word. */
    private static final Map<String, String> VERBS = Map.of("get", "read", "search", "search");

    /* Verbs whose single object takes an article: "create an issue", not "create issue". */
    private static final Set<String> TAKES_ARTICLE = Set.of(
            "create", "draft", "send", "post", "add", "get", "save", "update", "delete", "remove", "archive",
            "cancel", "refund", "merge", "schedule", "append", "reply", "read");

    /* The tools the platform itself offers every agent, which are not on any connector. */
    private static final Map<String, String> INTERNAL = Map.of(
            "person.ask_question", "Ask you a question",
            "memory.remember", "Remember a fact for next time",
            "memory.recall", "Recall what it remembered",
            "knowledge.search", "Search the workspace's documents");

    private static final ConnectorCatalog CATALOG = new ConnectorCatalog();

    /* Every qualified name the platform knows, so a dotted "gmail.send_message" is recognised but
       "example.com" or "notes.md" is never touched. */
    private static final Set<String> KNOWN = known();

    /** server__tool, the form the model is shown. */
    private static final Pattern WIRE =
            Pattern.compile("(`?)(?<![\\w.])([a-z][a-z0-9-]*)__([a-z][a-z0-9_]*)(?![\\w])(`?)");

    /** server.tool, for a known server only. */
    private static final Pattern DOTTED =
            Pattern.compile("(`?)(?<![\\w.@/])([a-z][a-z0-9-]*)\\.([a-z][a-z0-9_]*)(?![\\w(])(`?)");

    /*
     * A line that names a tool and then says what it does: "- github__create_issue: Open an issue."
     * The name is dropped and the description kept, since it already says it in plain words.
     */
    private static final Pattern NAMED_LINE = Pattern.compile(
            "(?m)^(\\s*(?:[-*\u2022]|\\d+[.)])?\\s*)(?:\\*\\*|`)*([a-z][a-z0-9-]*(?:__|\\.)[a-z][a-z0-9_]*)(?:\\*\\*|`)*"
                    + "\\s*(?:[:\u2013\u2014]|\\s-)\\s+(\\S)");

    /** "Create an issue in GitHub". */
    public static String label(String server, String tool) {
        if (server == null || tool == null) {
            return "an action";
        }
        String internal = INTERNAL.get(server + "." + tool);
        if (internal != null) {
            return internal;
        }
        return capitalised(phrase(tool)) + " in " + serverName(server);
    }

    /** "create an issue": the tool's name as an action, lower case. */
    public static String phrase(String tool) {
        String[] words = tool.toLowerCase(Locale.ROOT).split("_+");
        if (words.length == 0 || words[0].isEmpty()) {
            return tool;
        }
        String verb = words[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < words.length; i++) {
            rest.add(NOUNS.getOrDefault(words[i], words[i]));
        }
        StringBuilder phrase = new StringBuilder(VERBS.getOrDefault(verb, verb));
        if (rest.isEmpty()) {
            return phrase.toString();
        }
        String object = String.join(" ", rest);
        boolean plural = object.endsWith("s") && !object.endsWith("ss");
        boolean preposition = Set.of("on", "in", "to", "from", "for", "with").contains(rest.get(0));
        if (TAKES_ARTICLE.contains(verb) && !plural && !preposition) {
            phrase.append(' ').append("aeiou".indexOf(object.charAt(0)) >= 0 ? "an" : "a");
        }
        return phrase.append(' ').append(object).toString();
    }

    /** "GitHub" for github; the server's own name, capitalised, when the catalog has no entry. */
    public static String serverName(String server) {
        return CATALOG.find(server).map(ConnectorInfo::displayName).orElseGet(() -> capitalised(server));
    }

    /**
     * The text with every internal tool name a person could see replaced by its plain label. A line
     * that only names a tool before describing it keeps the description. Text with no tool name in
     * it is returned unchanged.
     */
    public static String humanise(String text) {
        if (text == null || text.isEmpty() || (!text.contains("__") && !mentionsDotted(text))) {
            return text;
        }
        Matcher named = NAMED_LINE.matcher(text);
        StringBuilder lines = new StringBuilder();
        while (named.find()) {
            String id = named.group(2);
            boolean tool = id.contains("__") || KNOWN.contains(id);
            String replacement = tool
                    ? named.group(1) + named.group(3).toUpperCase(Locale.ROOT)
                    : named.group();
            named.appendReplacement(lines, Matcher.quoteReplacement(replacement));
        }
        named.appendTail(lines);
        String result = replace(WIRE, lines.toString(), true);
        return replace(DOTTED, result, false);
    }

    private static String replace(Pattern pattern, String text, boolean wire) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String server = matcher.group(2);
            String tool = matcher.group(3);
            boolean known = KNOWN.contains(server + "." + tool);
            if (!wire && !known) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String label = label(server, tool);
            // Mid-sentence, the label reads in lower case: "I can create an issue in GitHub".
            boolean startsSentence = startsSentence(text, matcher.start());
            String written = startsSentence || INTERNAL.containsKey(server + "." + tool)
                    ? label
                    : Character.toLowerCase(label.charAt(0)) + label.substring(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(written));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean startsSentence(String text, int at) {
        int i = at - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '`' || text.charAt(i) == '*')) {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char before = text.charAt(i);
        return before == '\n' || before == '.' || before == ':' || before == '-' || before == '\u2022'
                || before == '!' || before == '?';
    }

    private static boolean mentionsDotted(String text) {
        Matcher matcher = DOTTED.matcher(text);
        while (matcher.find()) {
            if (KNOWN.contains(matcher.group(2) + "." + matcher.group(3))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> known() {
        Set<String> names = new HashSet<>(INTERNAL.keySet());
        Map<String, List<ToolDefinition>> definitions = new HashMap<>(SandboxServerRegistry.definitions());
        definitions.values().forEach(tools -> tools.forEach(tool -> names.add(tool.qualifiedName())));
        return Set.copyOf(names);
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
