package os.aiworkforce.llm.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolNamesTest {

    @Test
    @DisplayName("a dotted platform tool name goes out in a form every provider accepts and comes back unchanged")
    void roundTrips() {
        String wire = ToolNames.toWire("gmail.draft_message");
        assertThat(wire).isEqualTo("gmail__draft_message").matches("[a-zA-Z0-9_-]{1,64}");
        assertThat(ToolNames.fromWire(wire)).isEqualTo("gmail.draft_message");
    }

    @Test
    @DisplayName("names without a server prefix, and nulls, are left alone")
    void leavesOthersAlone() {
        assertThat(ToolNames.toWire("draft_message")).isEqualTo("draft_message");
        assertThat(ToolNames.fromWire("draft_message")).isEqualTo("draft_message");
        assertThat(ToolNames.toWire(null)).isNull();
        assertThat(ToolNames.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("a tool a person answers is recognised in its dotted and its wire form, and nothing else is")
    void isPersonToolMatchesBothForms() {
        assertThat(ToolNames.isPersonTool("person.ask_question")).isTrue();
        assertThat(ToolNames.isPersonTool("person__ask_question")).isTrue();
        assertThat(ToolNames.isPersonTool(ToolNames.toWire("person.ask_question")))
                .isTrue();
        assertThat(ToolNames.isPersonTool("gmail.send_message")).isFalse();
        assertThat(ToolNames.isPersonTool("personnel.list")).isFalse();
        assertThat(ToolNames.isPersonTool("person")).isFalse();
        assertThat(ToolNames.isPersonTool(null)).isFalse();
    }
}
