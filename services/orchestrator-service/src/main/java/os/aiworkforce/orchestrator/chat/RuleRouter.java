package os.aiworkforce.orchestrator.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import os.aiworkforce.orchestrator.domain.Agent;

/**
 * Chooses an agent for a piece of work without calling a model, by scoring how much of a clause's
 * own words point at each active specialist.
 *
 * <p>Used only when nobody was mentioned and the model router either has no live provider or
 * declined to answer with valid JSON. A clause routes when one agent's score reaches the floor
 * and clearly beats the next-best agent; a tie between two equally-plausible agents is handed back
 * as a choice, because guessing is worse than asking. A clause that matches nobody merges into a
 * neighbouring routed clause, or - when the workspace has an active General Employee - the whole
 * message goes to it instead of a dead end.
 */
public final class RuleRouter {

    private RuleRouter() {}

    /** Below this, a clause names nobody in particular. */
    private static final int MIN_SCORE = 2;

    private static final List<String> CLAUSE_SEPARATORS = List.of(" and then ", " then ", "; ", ", then ");

    /** At most this many clauses are read from one message; a longer chain is not one somebody typed. */
    private static final int MAX_CLAUSES = 3;

    /** The most alternatives offered when something scored; a longer list is not a short choice any more. */
    private static final int MAX_ALTERNATIVES = 3;

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "hr",
                    List.of(
                            "email to new starter",
                            "onboard",
                            "onboarding",
                            "hire",
                            "hiring",
                            "candidate",
                            "interview",
                            "welcome",
                            "leave",
                            "payroll",
                            // People-and-employment words a request to HR uses without saying "HR".
                            "hr",
                            "human resources",
                            "staff",
                            "employee",
                            "employees",
                            "recruit",
                            "recruiting",
                            "recruitment",
                            "salary",
                            "annual leave",
                            "sick leave",
                            "parental",
                            "maternity",
                            "vacation",
                            "holiday",
                            "benefits",
                            "performance review",
                            "probation",
                            "offer letter",
                            "resignation",
                            "timesheet"),
            "support", List.of("customer", "ticket", "refund", "complaint", "reply", "replies", "support"),
            "engineering-manager",
                    List.of(
                            "pull request",
                            "pr",
                            "jira",
                            "ticket board",
                            "standup",
                            "sprint",
                            "deploy",
                            "bug",
                            "github",
                            "repo",
                            "repos",
                            "repository",
                            "repositories",
                            "branch",
                            "code"),
            "research",
                    List.of("research", "report", "competitor", "market", "analysis", "summarise sources", "compare"));

    private static final Map<String, List<String>> TOOL_SYNONYMS = Map.of(
            "gmail", List.of("email"),
            "slack", List.of("channel", "slack"),
            "github", List.of("github", "repo", "repository", "pull request", "branch"),
            "jira", List.of("jira"),
            "calendar", List.of("meeting", "interview"),
            "drive", List.of("document", "doc"));

    /** One compiled word-boundary pattern per phrase, built once and reused for every clause scored. */
    private static final Map<String, Pattern> PHRASE_PATTERNS = new ConcurrentHashMap<>();

    /**
     * @param matched the words that scored, in the order they were found
     * @param fallback true when this step was handed to the workspace's General Employee rather
     *     than won on its own words
     */
    public record Routed(Agent agent, String instruction, List<String> matched, boolean fallback) {
        public Routed(Agent agent, String instruction, List<String> matched) {
            this(agent, instruction, matched, false);
        }
    }

    public record ScoredAgent(Agent agent, int score) {}

    /** Why a message could not be routed on its own. */
    public enum Reason {
        NONE,
        TIE,
        NO_MATCH,
        NO_AGENTS
    }

    /**
     * @param steps one entry per routed clause, in order, when at least one clause routed
     * @param alternatives the candidates worth offering a person when nothing did, or when a tie
     *     needs breaking - the top scored agents, or every active specialist (each scored zero)
     *     when nothing scored at all
     */
    public record Result(List<Routed> steps, boolean needsChoice, List<ScoredAgent> alternatives, Reason reason) {

        /** True once every step in a routed result went to the fallback rather than being won. */
        public boolean allFallback() {
            return !steps.isEmpty() && steps.stream().allMatch(Routed::fallback);
        }

        static Result unrouted(List<ScoredAgent> alternatives) {
            return unrouted(alternatives, Reason.NO_MATCH);
        }

        static Result unrouted(List<ScoredAgent> alternatives, Reason reason) {
            return new Result(List.of(), true, alternatives, reason);
        }

        static Result routed(List<Routed> steps, List<ScoredAgent> alternatives) {
            return new Result(steps, false, alternatives, Reason.NONE);
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
        return route(text, agents, agentToolServers, null);
    }

    /**
     * @param fallback the workspace's active General Employee, or null when it has none. A
     *     fallback that is not active is treated the same as null - a paused or retired General
     *     never takes work, however this is called.
     */
    public static Result route(
            String text, List<Agent> agents, Map<UUID, List<String>> agentToolServers, Agent fallback) {
        Agent activeFallback = fallback != null && fallback.isActive() ? fallback : null;
        List<Agent> specialists = agents.stream()
                .filter(agent -> "active".equals(agent.getStatus()))
                .filter(agent -> activeFallback == null || !agent.getId().equals(activeFallback.getId()))
                .toList();

        if (specialists.isEmpty()) {
            if (activeFallback != null) {
                return Result.routed(List.of(new Routed(activeFallback, text, List.of(), true)), List.of());
            }
            return Result.unrouted(List.of(), Reason.NO_AGENTS);
        }

        List<String> clauses = clausesOf(text);
        List<ClauseScore> outcomes = new ArrayList<>();
        for (String clause : clauses) {
            List<Scored> scored = scoreClause(clause, specialists, agentToolServers);
            scored.sort(Comparator.comparingInt(Scored::score).reversed());
            outcomes.add(new ClauseScore(clause, scored));
        }

        ClauseScore tie =
                outcomes.stream().filter(ClauseScore::isTie).findFirst().orElse(null);
        if (tie != null) {
            return Result.unrouted(tieAlternatives(tie, activeFallback), Reason.TIE);
        }

        boolean anyRouted = outcomes.stream().anyMatch(ClauseScore::isRouted);
        if (anyRouted) {
            List<Routed> steps = buildSteps(outcomes);
            return Result.routed(steps, topAlternatives(outcomes, steps));
        }

        if (activeFallback != null) {
            List<ScoredAgent> alternatives = topAlternatives(outcomes, List.of());
            if (alternatives.isEmpty()) {
                alternatives = specialists.stream()
                        .limit(4)
                        .map(agent -> new ScoredAgent(agent, 0))
                        .toList();
            }
            return Result.routed(List.of(new Routed(activeFallback, text, List.of(), true)), alternatives);
        }
        List<ScoredAgent> alternatives = outcomes.isEmpty()
                ? specialists.stream().map(agent -> new ScoredAgent(agent, 0)).toList()
                : alternativesFor(outcomes.get(0).scored(), specialists);
        return Result.unrouted(alternatives, Reason.NO_MATCH);
    }

    /** One clause together with every specialist's score against it, highest first. */
    private record ClauseScore(String clause, List<Scored> scored) {
        Scored winner() {
            return scored.isEmpty() ? null : scored.get(0);
        }

        Scored runnerUp() {
            return scored.size() > 1 ? scored.get(1) : null;
        }

        boolean isTie() {
            Scored winner = winner();
            Scored runnerUp = runnerUp();
            return winner != null
                    && runnerUp != null
                    && winner.score() == runnerUp.score()
                    && winner.score() >= MIN_SCORE;
        }

        boolean isRouted() {
            Scored winner = winner();
            return winner != null && winner.score() >= MIN_SCORE && !isTie();
        }
    }

    /** Merges every unmatched clause into its nearest routed neighbour (A1.4 step 5). */
    private static List<Routed> buildSteps(List<ClauseScore> outcomes) {
        List<Routed> steps = new ArrayList<>();
        StringBuilder leadingPrefix = new StringBuilder();
        for (ClauseScore outcome : outcomes) {
            if (outcome.isRouted()) {
                Scored winner = outcome.winner();
                String instruction = outcome.clause();
                if (leadingPrefix.length() > 0) {
                    instruction = leadingPrefix + ". " + instruction;
                    leadingPrefix.setLength(0);
                }
                steps.add(new Routed(winner.agent(), instruction, winner.matched()));
            } else if (steps.isEmpty()) {
                if (leadingPrefix.length() > 0) {
                    leadingPrefix.append(". ");
                }
                leadingPrefix.append(outcome.clause());
            } else {
                Routed previous = steps.remove(steps.size() - 1);
                steps.add(new Routed(
                        previous.agent(), previous.instruction() + ", then " + outcome.clause(), previous.matched()));
            }
        }
        return steps;
    }

    private static List<ScoredAgent> tieAlternatives(ClauseScore tie, Agent fallback) {
        int topScore = tie.winner().score();
        List<Scored> tiedFirst = new ArrayList<>();
        List<Scored> others = new ArrayList<>();
        for (Scored scored : tie.scored()) {
            if (scored.score() == topScore) {
                tiedFirst.add(scored);
            } else if (scored.score() > 0) {
                others.add(scored);
            }
        }
        List<Scored> ordered = new ArrayList<>(tiedFirst);
        ordered.addAll(others);
        List<ScoredAgent> alternatives = new ArrayList<>(ordered.stream()
                .limit(MAX_ALTERNATIVES)
                .map(scored -> new ScoredAgent(scored.agent(), scored.score()))
                .toList());
        if (fallback != null) {
            alternatives.add(new ScoredAgent(fallback, 0));
        }
        return List.copyOf(alternatives);
    }

    /** The top scored specialists across every clause, highest score first, excluding a step's own agent. */
    private static List<ScoredAgent> topAlternatives(List<ClauseScore> outcomes, List<Routed> steps) {
        Set<UUID> used = steps.stream()
                .filter(step -> !step.fallback())
                .map(step -> step.agent().getId())
                .collect(Collectors.toSet());
        Map<UUID, Scored> bestPerAgent = new LinkedHashMap<>();
        for (ClauseScore outcome : outcomes) {
            for (Scored scored : outcome.scored()) {
                if (used.contains(scored.agent().getId())) {
                    continue;
                }
                bestPerAgent.merge(scored.agent().getId(), scored, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return bestPerAgent.values().stream()
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(MAX_ALTERNATIVES)
                .map(scored -> new ScoredAgent(scored.agent(), scored.score()))
                .toList();
    }

    private static List<ScoredAgent> alternativesFor(List<Scored> scored, List<Agent> specialists) {
        boolean anyScored = scored.stream().anyMatch(s -> s.score() > 0);
        if (!anyScored) {
            return specialists.stream().map(agent -> new ScoredAgent(agent, 0)).toList();
        }
        return scored.stream()
                .limit(MAX_ALTERNATIVES)
                .map(s -> new ScoredAgent(s.agent(), s.score()))
                .toList();
    }

    private record Scored(Agent agent, int score, List<String> matched) {}

    private static List<Scored> scoreClause(
            String clause, List<Agent> agents, Map<UUID, List<String>> agentToolServers) {
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
            if (agent.getCategory() != null
                    && containsWord(lower, agent.getCategory().toLowerCase(Locale.ROOT))) {
                score += 2;
                matched.add(agent.getCategory().toLowerCase(Locale.ROOT));
            }
            for (String synonym : SYNONYMS.getOrDefault(agent.getKey(), List.of())) {
                if (containsPhrase(lower, synonym)) {
                    score += 2;
                    matched.add(synonym);
                }
            }
            for (String server : agentToolServers.getOrDefault(agent.getId(), List.of())) {
                for (String toolWord : TOOL_SYNONYMS.getOrDefault(server, List.of())) {
                    if (containsPhrase(lower, toolWord)) {
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
        for (String source :
                List.of(agent.getKey() == null ? "" : agent.getKey(), agent.getName() == null ? "" : agent.getName())) {
            for (String word : source.toLowerCase(Locale.ROOT).split("[\\s-]+")) {
                if (word.length() > 1) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    private static boolean containsWord(String lower, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                .matcher(lower)
                .find();
    }

    /**
     * Whether a phrase appears in {@code lower} as whole words, allowing a trailing "s" or "es" so
     * a plural still matches ("tickets" still matches "ticket"), but never as part of a longer word
     * ("prepare" never matches "pr", "doctor" never matches "doc").
     */
    private static boolean containsPhrase(String lower, String phrase) {
        return phrasePattern(phrase).matcher(lower).find();
    }

    private static Pattern phrasePattern(String phrase) {
        return PHRASE_PATTERNS.computeIfAbsent(
                phrase, p -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?:s|es)?(?![\\p{L}\\p{N}])"));
    }
}
