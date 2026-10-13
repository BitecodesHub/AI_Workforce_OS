// @find: rule router, route without a model, score agents, keyword routing, ask user to choose agent, tie between agents, clauses, RuleRouter, fallback routing, needs choice
// @what: Chooses an agent for each part of a message by scoring its words against active agents, or asks the person to choose when unsure.
// @flow: Called by CoordinatorService when no mention is given and the model router has no answer.
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

    /** Not instantiable; every method is static and the router holds no state. */
    private RuleRouter() {}

    /** Below this, a clause names nobody in particular. */
    private static final int MIN_SCORE = 2;

    /** The phrases a message is split on into separate pieces of work, tried in this order. */
    private static final List<String> CLAUSE_SEPARATORS = List.of(" and then ", " then ", "; ", ", then ");

    /** At most this many clauses are read from one message; a longer chain is not one somebody typed. */
    private static final int MAX_CLAUSES = 3;

    /** The most alternatives offered when something scored; a longer list is not a short choice any more. */
    private static final int MAX_ALTERNATIVES = 3;

    /** Extra phrases, by agent key, that point at that agent; each match scores {@code 2}. */
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

    /** Words that point at an agent holding a tool of that server name; each match scores {@code 1}. */
    private static final Map<String, List<String>> TOOL_SYNONYMS = Map.of(
            "gmail", List.of("email"),
            "slack", List.of("channel", "slack"),
            "github", List.of("github", "repo", "repository", "pull request", "branch"),
            "jira", List.of("jira"),
            "calendar", List.of("meeting", "interview"),
            "drive", List.of("document", "doc"),
            "browser", List.of("website", "web page", "wikipedia", "youtube", "browser", "online"));

    /** One compiled word-boundary pattern per phrase, built once and reused for every clause scored. */
    private static final Map<String, Pattern> PHRASE_PATTERNS = new ConcurrentHashMap<>();

    /**
     * One routed step: the agent chosen, its part of the message, and why.
     *
     * @param matched the words that scored, in the order they were found
     * @param fallback true when this step was handed to the workspace's General Employee rather
     *     than won on its own words
     */
    public record Routed(Agent agent, String instruction, List<String> matched, boolean fallback) {
        public Routed(Agent agent, String instruction, List<String> matched) {
            this(agent, instruction, matched, false);
        }
    }

    /** An agent with the score it earned against a clause, for offering a person a choice. */
    public record ScoredAgent(Agent agent, int score) {}

    /** Why a message could not be routed on its own. */
    public enum Reason {
        NONE,
        TIE,
        NO_MATCH,
        NO_AGENTS
    }

    /**
     * The outcome of routing: the routed steps, or a request for the person to choose, with the candidates and the reason.
     *
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

        /** An unrouted result for a message that matched nobody. */
        static Result unrouted(List<ScoredAgent> alternatives) {
            return unrouted(alternatives, Reason.NO_MATCH);
        }

        /** An unrouted result with the given reason; the person must choose. */
        static Result unrouted(List<ScoredAgent> alternatives, Reason reason) {
            return new Result(List.of(), true, alternatives, reason);
        }

        /** A routed result; no choice is needed, and the alternatives are only offered as other options. */
        static Result routed(List<Routed> steps, List<ScoredAgent> alternatives) {
            return new Result(steps, false, alternatives, Reason.NONE);
        }
    }

    /**
     * Splits a message into at most {@value #MAX_CLAUSES} clauses at "and then", "then", semicolons and
     * ", then", dropping blank pieces. Pure; used by {@link #route} and by the coordinator for display.
     */
    // @find: split message into clauses
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

    /** Routes with no General Employee fallback; see the four-argument overload. */
    // @find: route message to agents by rules
    public static Result route(String text, List<Agent> agents, Map<UUID, List<String>> agentToolServers) {
        return route(text, agents, agentToolServers, null);
    }

    /**
     * Chooses agents for a message by rules alone.
     *
     * <p>Flow: keeps only active specialists (the fallback is excluded from scoring), splits the
     * message into clauses and scores each against every specialist. A tie on any clause returns
     * an unrouted result with the tied agents to choose from; otherwise clauses that scored
     * enough become steps, with unmatched clauses merged into a neighbour. When nothing routed the
     * message goes whole to the fallback if there is one, else comes back unrouted with
     * alternatives. Called by {@link CoordinatorService}. Pure: no database, HTTP or model call.
     *
     * @param agentToolServers the tool-server names each agent holds, by agent id, used as a weak signal
     * @param fallback the workspace's active General Employee, or null when it has none. A
     *     fallback that is not active is treated the same as null - a paused or retired General
     *     never takes work, however this is called.
     */
    // @find: route message to agents by rules with tool servers
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

    /**
     * One clause together with every specialist's score against it, highest first.
     * A clause is routed when the best score reaches {@link #MIN_SCORE} and is not shared with the
     * runner-up; it is a tie when the two best scores are equal and reach that floor.
     */
    private record ClauseScore(String clause, List<Scored> scored) {
        /** The best-scoring agent, or null when there are none. */
        Scored winner() {
            return scored.isEmpty() ? null : scored.get(0);
        }

        /** The second-best agent, or null when there is only one. */
        Scored runnerUp() {
            return scored.size() > 1 ? scored.get(1) : null;
        }

        /** Whether the best two scores are equal and at least the floor. */
        boolean isTie() {
            Scored winner = winner();
            Scored runnerUp = runnerUp();
            return winner != null
                    && runnerUp != null
                    && winner.score() == runnerUp.score()
                    && winner.score() >= MIN_SCORE;
        }

        /** Whether one agent clearly won this clause. */
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

    /** The agents to offer when a clause tied: the tied leaders first, then others that scored, at most three, plus the fallback when there is one. */
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

    /** The top scored specialists across every clause, highest score first, at most three, excluding the agents already given a step. */
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

    /** The alternatives for an unrouted message: the top scorers, or every specialist at zero when nothing scored. */
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

    /** One agent's score against one clause, with the words that produced it. */
    private record Scored(Agent agent, int score, List<String> matched) {}

    /**
     * Scores every specialist against one clause: 3 for each word of its key or name, 2 for its
     * category, 2 for each synonym phrase, and 1 for each word tied to a tool server it holds.
     */
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

    /** Whether the word appears as a whole word in the text. */
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

    /** The cached whole-word pattern, with an optional plural ending, for a phrase. */
    private static Pattern phrasePattern(String phrase) {
        return PHRASE_PATTERNS.computeIfAbsent(
                phrase, p -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?:s|es)?(?![\\p{L}\\p{N}])"));
    }
}
