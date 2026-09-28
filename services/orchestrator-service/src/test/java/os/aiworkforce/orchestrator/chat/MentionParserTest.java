package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;

class MentionParserTest {

    private static Agent agent(String key, String name) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        return agent;
    }

    private final Agent support = agent("support", "Customer Support");
    private final Agent hr = agent("hr", "HR");
    private final Agent research = agent("research", "Research");
    private final List<Agent> agents = List.of(support, hr, research);

    @Test
    @DisplayName("matches a key written as a single word")
    void matchesKey() {
        MentionParser.Result result = MentionParser.parse("@hr onboard the new starter", agents);

        assertThat(result.agents()).containsExactly(hr);
        assertThat(result.text()).isEqualTo("onboard the new starter");
    }

    @Test
    @DisplayName("matches a two-word name written with no space")
    void matchesCamelCaseName() {
        MentionParser.Result result = MentionParser.parse("@CustomerSupport please reply", agents);

        assertThat(result.agents()).containsExactly(support);
        assertThat(result.text()).isEqualTo("please reply");
    }

    @Test
    @DisplayName("matches a two-word name written with a hyphen")
    void matchesHyphenatedName() {
        MentionParser.Result result = MentionParser.parse("@customer-support please reply", agents);

        assertThat(result.agents()).containsExactly(support);
    }

    @Test
    @DisplayName("matches a two-word name written as two words followed by a space")
    void matchesTwoWordName() {
        MentionParser.Result result = MentionParser.parse("@Customer Support please reply to Jordan", agents);

        assertThat(result.agents()).containsExactly(support);
        assertThat(result.text()).isEqualTo("please reply to Jordan");
    }

    @Test
    @DisplayName("leaves an unrecognised token as ordinary text")
    void leavesUnknownTokenAlone() {
        MentionParser.Result result = MentionParser.parse("email jordan@example.com about the invoice", agents);

        assertThat(result.agents()).isEmpty();
        assertThat(result.text()).isEqualTo("email jordan@example.com about the invoice");
    }

    @Test
    @DisplayName("collects several mentions in the order written, without duplicates")
    void collectsSeveralMentionsInOrder() {
        MentionParser.Result result = MentionParser.parse(
                "@research find competitors then @support draft a reply, cc @research", agents);

        assertThat(result.agents()).containsExactly(research, support);
    }

    @Test
    @DisplayName("an empty message or an empty agent list matches nothing")
    void emptyInputsMatchNothing() {
        assertThat(MentionParser.parse("", agents).agents()).isEmpty();
        assertThat(MentionParser.parse("@hr do it", List.of()).agents()).isEmpty();
        assertThat(MentionParser.parse(null, agents).text()).isEmpty();
    }
}
