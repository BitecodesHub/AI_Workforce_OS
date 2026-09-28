package os.aiworkforce.orchestrator.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import os.aiworkforce.orchestrator.domain.Agent;

/**
 * Chooses an agent for a piece of work without calling a model, by scoring how much of a clause's
 * own words point at each active agent.
 *
 * <p>Used only when nobody was mentioned and the model router either has no live provider or
 * declined to answer with valid JSON. A clause routes when one agent's score reaches the floor
 * and clearly beats the next-best agent; otherwise the whole message is handed back unrouted,
 * because guessing between two equally-plausible agents is worse than asking.
 */
public final class RuleRouter {

    private RuleRouter() {}

    /** Below this, a clause names nobody in particular. */
    private static final int MIN_SCORE = 2;

    private static final List<String> CLAUSE_SEPARATORS = List.of(" and then ", " then ", "; ", ", then ");

    /** At most this many clauses are read from one message; a longer chain is not one somebody typed. */
    private static final int MAX_CLAUSES = 3;

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "hr", List.of(
                    "email to new starter", "onboard", "onboarding", "hire", "hiring", "candidate",
                    "interview", "welcome", "leave", "payroll"),
            "support", List.of("customer", "ticket", "refund", "complaint", "reply", "support"),
            "engineering-manager", List.of(
                    "pull request", "pr", "jira", "ticket board", "standup", "sprint", "deploy", "bug",
                    "github", "code"),
            "research", List.of("research", "report", "competitor", "market", "analysis", "summarise sources", "compare"));

    private static final Map<String, List<String>> TOOL_SYNONYMS = Map.of(
            "gmail", List.of("email"),
            "slack", List.of("channel", "slack"),
            "github", List.of("github"),
            "jira", List.of("jira"),
            "calendar", List.of("meeting", "interview"),
            "drive", List.of("document", "doc"));

    /** @param matched the words that scored, in the order they were found */
    public record Routed(Agent agent, String instruction, List<String> matched) {}

    public record ScoredAgent(Agent agent, int score) {}

    /**
     * @param steps one entry per clause, in order, when every clause routed
     * @param alternatives the candidates worth offering a person when nothing did - the top three
     *     scored agents, or every active agent (each scored zero) when every score was zero
     */
    public record Result(List<Routed> steps, boolean needsChoice, List<ScoredAgent> alternatives) {

        static Result unrouted(List<ScoredAgent> alternatives) {
            return new Result(List.of(), true, alternatives);
        }

        static Result routed(List<Routed> steps) {
            return new Result(steps, false, List.of());
        }
    }

    public static List<String> clausesOf(String text) {
        List<String> clauses = new ArrayList<>(List.of(text));
        for (String separator : CLAUSE_SEPARATORS) {
            List<String> split = new ArrayList<>();
            for (String clause : clauses) {
                for (String part : clause.split(Pattern.quote(separator), -1)) {
                    split.add(part);
                }
            }
            clauses = split;
        }
        return clauses.stream()
                .map(String::strip)
                .filter(clause -> !clause.isBlank())
                .limit(MAX_CLAUSES)
                .toList();
    }

    public static Result route(String text, List<Agent> agents, Map<UUID, List<String>> agentToolServers) {
        List<Agent> active = agents.stream().filter(agent -> "active".equals(agent.getStatus())).toList();
        List<String> clauses = clausesOf(text);
        if (active.isEmpty() || clauses.isEmpty()) {
            return Result.unrouted(active.stream().map(agent -> new ScoredAgent(agent, 0)).toList());
        }

        List<Routed> steps = new ArrayList<>();
        for (String clause : clauses) {
            List<Scored> scored = scoreClause(clause, active, agentToolServers);
            scored.sort(Comparator.comparingInt(Scored::score).reversed());
            Scored winner = scored.isEmpty() ? null : scored.get(0);
            if (winner == null || winner.score() < MIN_SCORE) {
                return Result.unrouted(alternativesFor(scored, active));
            }
            boolean tie = scored.size() > 1 && scored.get(1).score() == winner.score();
            if (tie) {
                return Result.unrouted(alternativesFor(scored, active));
            }
            steps.add(new Routed(winner.agent(), clause, winner.matched()));
        }
        return Result.routed(steps);
    }

    private static List<ScoredAgent> alternativesFor(List<Scored> scored, List<Agent> active) {
        boolean anyScored = scored.stream().anyMatch(s -> s.score() > 0);
        if (!anyScored) {
            return active.stream().map(agent -> new ScoredAgent(agent, 0)).toList();
        }
        return scored.stream().limit(3).map(s -> new ScoredAgent(s.agent(), s.score())).toList();
    }

    private record Scored(Agent agent, int score, List<String> matched) {}

    private static List<Scored> scoreClause(String clause, List<Agent> agents, Map<UUID, List<String>> agentToolServers) {
        String lower = clause.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        for (Agent agent : agents) {
            int score = 0;
            Set<String> matched = new LinkedHashSet<>();

            for (String word : nameWordsOf(agent)) {
                if (containsWord(lower, word)) {
                    score += 3;
                    matched.add(word);
                }
            }
            if (agent.getCategory() != null && containsWord(lower, agent.getCategory().toLowerCase(Locale.ROOT))) {
                score += 2;
                matched.add(agent.getCategory().toLowerCase(Locale.ROOT));
            }
            for (String synonym : SYNONYMS.getOrDefault(agent.getKey(), List.of())) {
                if (lower.contains(synonym)) {
                    score += 2;
                    matched.add(synonym);
                }
            }
            for (String server : agentToolServers.getOrDefault(agent.getId(), List.of())) {
                for (String toolWord : TOOL_SYNONYMS.getOrDefault(server, List.of())) {
                    if (lower.contains(toolWord)) {
                        score += 1;
                        matched.add(toolWord);
                    }
                }
            }
            scored.add(new Scored(agent, score, List.copyOf(matched)));
        }
        return scored;
    }

    /** The individual words of an agent's key and name - "engineering-manager" gives "engineering", "manager". */
    private static Set<String> nameWordsOf(Agent agent) {
        Set<String> words = new LinkedHashSet<>();
        for (String source : List.of(agent.getKey() == null ? "" : agent.getKey(),
                agent.getName() == null ? "" : agent.getName())) {
            for (String word : source.toLowerCase(Locale.ROOT).split("[\\s-]+")) {
                if (word.length() > 1) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    private static boolean containsWord(String lower, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find();
    }
}
