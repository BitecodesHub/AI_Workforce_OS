package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;

class RuleRouterTest {

    private static Agent agent(String key, String name, String category) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        agent.setCategory(category);
        agent.setStatus("active");
        return agent;
    }

    private static Agent generalEmployee(String status) {
        Agent general = agent("general", "General Employee", "operations");
        general.setFallback(true);
        general.setStatus(status);
        return general;
    }

    private final Agent support = agent("support", "Customer Support", "support");
    private final Agent hr = agent("hr", "HR", "operations");
    private final Agent engineering = agent("engineering-manager", "Engineering Manager", "engineering");
    private final Agent research = agent("research", "Research", "research");
    private final List<Agent> agents = List.of(support, hr, engineering, research);
    private final Map<UUID, List<String>> noTools = Map.of();

    @Test
    @DisplayName("a synonym match sends the clause to the right agent, and names the words that matched")
    void synonymMatchWins() {
        RuleRouter.Result result =
                RuleRouter.route("There is a refund request from an angry customer", agents, noTools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(support);
        assertThat(result.steps().getFirst().matched()).contains("refund", "customer");
    }

    @Test
    @DisplayName("splits on 'then' and routes each clause to its own agent, in order")
    void splitsClausesAndChains() {
        RuleRouter.Result result = RuleRouter.route(
                "research our top competitors then draft a reply for the customer complaint", agents, noTools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).agent()).isEqualTo(research);
        assertThat(result.steps().get(1).agent()).isEqualTo(support);
    }

    @Test
    @DisplayName("a granted tool server's own word adds a small amount of signal")
    void toolServerWordAddsScore() {
        Map<UUID, List<String>> tools = Map.of(engineering.getId(), List.of("github"));

        RuleRouter.Result result = RuleRouter.route("open the github pull request please", agents, tools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps().getFirst().agent()).isEqualTo(engineering);
    }

    @Test
    @DisplayName("a request about a repository goes to the Engineering Manager")
    void repositoryRequestsGoToEngineering() {
        Map<UUID, List<String>> tools = Map.of(engineering.getId(), List.of("github"));
        for (String text : List.of("create a new repo named test repo by aiworkforce",
                "list the branches of the website repository")) {
            RuleRouter.Result result = RuleRouter.route(text, agents, tools);

            assertThat(result.needsChoice()).as(text).isFalse();
            assertThat(result.steps().getFirst().agent()).as(text).isEqualTo(engineering);
        }
    }

    @Test
    @DisplayName("a tie between two equally-plausible agents asks rather than guesses")
    void tieAsksRatherThanGuesses() {
        // Two different agents that happen to score identically against this clause - same key,
        // so the same synonyms apply, and the same name, so the same words match.
        Agent supportTwin = agent("support", "Customer Support", "support");
        List<Agent> tied = List.of(support, supportTwin);

        RuleRouter.Result result = RuleRouter.route("a customer needs a refund", tied, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.steps()).isEmpty();
        assertThat(result.alternatives())
                .extracting(RuleRouter.ScoredAgent::agent)
                .containsExactlyInAnyOrder(support, supportTwin);
    }

    @Test
    @DisplayName("nothing scoring at all offers every active agent as an alternative")
    void nothingScoredOffersEveryAgent() {
        RuleRouter.Result result = RuleRouter.route("please take care of this for me", agents, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.alternatives())
                .extracting(RuleRouter.ScoredAgent::agent)
                .containsExactlyInAnyOrderElementsOf(agents);
        assertThat(result.alternatives()).allMatch(scored -> scored.score() == 0);
    }

    @Test
    @DisplayName("a score exactly at the floor still wins, as long as no other agent matches it")
    void atFloorScoreStillWinsWhenUnique() {
        // Only HR's category word appears, worth exactly MIN_SCORE - enough to win on its own,
        // with nothing else scoring at all.
        RuleRouter.Result result = RuleRouter.route("please handle the operations request", agents, noTools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps().getFirst().agent()).isEqualTo(hr);
        assertThat(result.steps().getFirst().matched()).containsExactly("operations");
    }

    @Test
    @DisplayName("a plainly HR request routes to HR even when it never says HR")
    void peopleAndEmploymentWordsRouteToHr() {
        for (String text : List.of(
                "How much annual leave do new employees get during probation?",
                "Draft an offer letter for the new recruit",
                "Summarise our parental leave policy for staff")) {
            RuleRouter.Result result = RuleRouter.route(text, agents, noTools);
            assertThat(result.needsChoice()).as(text).isFalse();
            assertThat(result.steps().getFirst().agent()).as(text).isEqualTo(hr);
        }
    }

    @Test
    @DisplayName("a paused agent is never offered, even when its words would otherwise win")
    void pausedAgentExcluded() {
        support.setStatus("paused");

        RuleRouter.Result result = RuleRouter.route("a customer needs a refund", agents, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.alternatives())
                .extracting(RuleRouter.ScoredAgent::agent)
                .doesNotContain(support);
    }

    @Test
    @DisplayName("a message nothing matches goes whole to the fallback rather than asking")
    void fallbackTakesUnmatchedText() {
        Agent general = generalEmployee("active");
        String text = "please take care of this for me";

        RuleRouter.Result result = RuleRouter.route(text, List.of(general, hr, support), noTools, general);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(general);
        assertThat(result.steps().getFirst().fallback()).isTrue();
        assertThat(result.steps().getFirst().instruction()).isEqualTo(text);
        assertThat(result.allFallback()).isTrue();
    }

    @Test
    @DisplayName("the fallback is never scored, even when its own words would otherwise tie it with a specialist")
    void fallbackIsNeverScored() {
        // General's category is "operations", same word that would otherwise win the floor score
        // below for HR. If the fallback were scored like a specialist, this would be a tie.
        Agent general = generalEmployee("active");
        List<Agent> withGeneral = List.of(general, hr, engineering, research);

        RuleRouter.Result result =
                RuleRouter.route("please handle the operations request", withGeneral, noTools, general);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(hr);
        assertThat(result.steps().getFirst().fallback()).isFalse();
    }

    @Test
    @DisplayName("an unmatched clause merges into the routed step next to it rather than getting its own step")
    void unmatchedClauseMergesIntoRoutedStep() {
        RuleRouter.Result result = RuleRouter.route("think it over then email the candidate", agents, noTools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(hr);
        assertThat(result.steps().getFirst().instruction()).isEqualTo("think it over. email the candidate");
    }

    @Test
    @DisplayName("a message split into three unmatched clauses is still one fallback step, not three")
    void fullyUnmatchedIsOneFallbackStep() {
        Agent general = generalEmployee("active");
        String text = "water the plants; feed the cat; lock the door";

        RuleRouter.Result result = RuleRouter.route(text, List.of(general, hr, support, engineering), noTools, general);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(general);
        assertThat(result.steps().getFirst().instruction()).isEqualTo(text);
    }

    @Test
    @DisplayName("a tie still asks, and lists the fallback last with a score of zero")
    void tieStillAsksAndListsFallbackLast() {
        Agent general = generalEmployee("active");
        Agent supportTwin = agent("support", "Customer Support", "support");

        RuleRouter.Result result =
                RuleRouter.route("a customer needs a refund", List.of(general, support, supportTwin), noTools, general);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.reason()).isEqualTo(RuleRouter.Reason.TIE);
        assertThat(result.alternatives()).hasSize(3);
        assertThat(result.alternatives().get(0).agent()).isIn(support, supportTwin);
        assertThat(result.alternatives().get(1).agent()).isIn(support, supportTwin);
        assertThat(result.alternatives().get(2).agent()).isEqualTo(general);
        assertThat(result.alternatives().get(2).score()).isZero();
    }

    @Test
    @DisplayName("a paused fallback is treated as no fallback at all")
    void pausedFallbackIsIgnored() {
        // Every specialist is paused too, so with an active fallback this would route to it (rule
        // 2). A paused fallback must not take that place - the workspace genuinely has nobody.
        Agent general = generalEmployee("paused");
        Agent pausedHr = agent("hr", "HR", "operations");
        pausedHr.setStatus("paused");

        RuleRouter.Result result =
                RuleRouter.route("please take care of this for me", List.of(general, pausedHr), noTools, general);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.reason()).isEqualTo(RuleRouter.Reason.NO_AGENTS);
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("'prepare' does not match the engineering synonym 'pr', so it falls back rather than misrouting")
    void prepareDoesNotMatchPr() {
        Agent general = generalEmployee("active");

        RuleRouter.Result result =
                RuleRouter.route("Prepare a lunch menu for the team", List.of(general, engineering), noTools, general);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().agent()).isEqualTo(general);
        assertThat(result.steps().getFirst().fallback()).isTrue();
    }

    @Test
    @DisplayName("the plural 'tickets' still matches the support synonym 'ticket'")
    void ticketsStillMatchesTicket() {
        RuleRouter.Result result = RuleRouter.route("Log two tickets for review", agents, noTools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps().getFirst().agent()).isEqualTo(support);
        assertThat(result.steps().getFirst().matched()).contains("ticket");
    }

    @Test
    @DisplayName("'doctor' does not match the drive tool synonym 'doc'")
    void doctorDoesNotMatchDoc() {
        // Support wins outright on "customer" and "complaint". If "doc" wrongly matched "doctor",
        // research would pick up a non-zero score too and show up as an alternative.
        Map<UUID, List<String>> tools = Map.of(research.getId(), List.of("drive"));

        RuleRouter.Result result = RuleRouter.route("book the doctor for a customer complaint", agents, tools);

        assertThat(result.needsChoice()).isFalse();
        assertThat(result.steps().getFirst().agent()).isEqualTo(support);
        assertThat(result.alternatives())
                .extracting(RuleRouter.ScoredAgent::agent)
                .doesNotContain(research);
    }
}
