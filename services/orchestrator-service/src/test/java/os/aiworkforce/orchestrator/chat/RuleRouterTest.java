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

    private final Agent support = agent("support", "Customer Support", "support");
    private final Agent hr = agent("hr", "HR", "operations");
    private final Agent engineering = agent("engineering-manager", "Engineering Manager", "engineering");
    private final Agent research = agent("research", "Research", "research");
    private final List<Agent> agents = List.of(support, hr, engineering, research);
    private final Map<UUID, List<String>> noTools = Map.of();

    @Test
    @DisplayName("a synonym match sends the clause to the right agent, and names the words that matched")
    void synonymMatchWins() {
        RuleRouter.Result result = RuleRouter.route(
                "There is a refund request from an angry customer", agents, noTools);

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
    @DisplayName("a tie between two equally-plausible agents asks rather than guesses")
    void tieAsksRatherThanGuesses() {
        // Two different agents that happen to score identically against this clause - same key,
        // so the same synonyms apply, and the same name, so the same words match.
        Agent supportTwin = agent("support", "Customer Support", "support");
        List<Agent> tied = List.of(support, supportTwin);

        RuleRouter.Result result = RuleRouter.route("a customer needs a refund", tied, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.steps()).isEmpty();
        assertThat(result.alternatives()).extracting(RuleRouter.ScoredAgent::agent)
                .containsExactlyInAnyOrder(support, supportTwin);
    }

    @Test
    @DisplayName("nothing scoring at all offers every active agent as an alternative")
    void nothingScoredOffersEveryAgent() {
        RuleRouter.Result result = RuleRouter.route("please take care of this for me", agents, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.alternatives()).extracting(RuleRouter.ScoredAgent::agent)
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
    @DisplayName("a paused agent is never offered, even when its words would otherwise win")
    void pausedAgentExcluded() {
        support.setStatus("paused");

        RuleRouter.Result result = RuleRouter.route("a customer needs a refund", agents, noTools);

        assertThat(result.needsChoice()).isTrue();
        assertThat(result.alternatives()).extracting(RuleRouter.ScoredAgent::agent).doesNotContain(support);
    }
}
