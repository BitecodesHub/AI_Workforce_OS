package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a model may ask a person, parsed strictly on the server because the schema it is shown
 * has to stay loose enough for every provider to accept.
 */
class AskPersonToolTest {

    private final AskPersonTool tool = new AskPersonTool(new ObjectMapper());

    private static String option(String label, String description) {
        return "{\"label\":\"" + label + "\",\"description\":\"" + description + "\"}";
    }

    private static String question(String header, String question, String... options) {
        return "{\"header\":\"" + header + "\",\"question\":\"" + question + "\",\"options\":["
                + String.join(",", options) + "]}";
    }

    private static final String TEAM = option("The whole team", "Plain language.");
    private static final String MANAGERS = option("Managers", "Decisions first.");
    private static final String CUSTOMER = option("A customer", "Formal.");

    @Test
    @DisplayName("a valid single question is parsed as asked, with no option recommended")
    void parsesValidSingleQuestion() throws Exception {
        AskPersonTool.Ask ask =
                tool.parse("{\"questions\":[" + question("Audience", "Who is this for?", TEAM, MANAGERS) + "]}");

        assertThat(ask.questions()).hasSize(1);
        AskPersonTool.Question q = ask.questions().getFirst();
        assertThat(q.id()).isEqualTo("q1");
        assertThat(q.header()).isEqualTo("Audience");
        assertThat(q.question()).isEqualTo("Who is this for?");
        assertThat(q.multiSelect()).isFalse();
        assertThat(q.options()).extracting(AskPersonTool.Option::label).containsExactly("The whole team", "Managers");
        assertThat(q.options()).noneMatch(AskPersonTool.Option::recommended);
    }

    @Test
    @DisplayName("a single question sent as the whole object is read as a list of one")
    void wrapsTopLevelQuestion() throws Exception {
        AskPersonTool.Ask ask = tool.parse(question("Audience", "Who is this for?", TEAM, MANAGERS));

        assertThat(ask.questions()).extracting(AskPersonTool.Question::id).containsExactly("q1");
    }

    @Test
    @DisplayName("refuses no questions or more than four")
    void rejectsZeroOrFiveQuestions() {
        String one = question("Audience", "Who is this for?", TEAM, MANAGERS);

        assertThatThrownBy(() -> tool.parse("{\"questions\":[]}"))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Ask between one and four questions.");
        assertThatThrownBy(() -> tool.parse("{\"questions\":[" + String.join(",", one, one, one, one, one) + "]}"))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Ask between one and four questions.");
    }

    @Test
    @DisplayName("refuses one option or five")
    void rejectsOneOrFiveOptions() {
        assertThatThrownBy(() -> tool.parse(question("Audience", "Who is this for?", TEAM)))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Give each question two to four options.");
        assertThatThrownBy(() -> tool.parse(question(
                        "Audience",
                        "Who is this for?",
                        TEAM,
                        MANAGERS,
                        CUSTOMER,
                        option("Investors", "Numbers."),
                        option("Press", "Short."))))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Give each question two to four options.");
    }

    @Test
    @DisplayName("refuses an option with no description")
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> tool.parse(question("Audience", "Who is this for?", TEAM, option("Managers", " "))))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Give each option a one-line description.");
    }

    @Test
    @DisplayName("refuses two options with the same label, whatever their case")
    void rejectsDuplicateLabels() {
        assertThatThrownBy(() ->
                        tool.parse(question("Audience", "Who is this for?", MANAGERS, option("managers", "Again."))))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Option labels must differ within a question.");
    }

    @Test
    @DisplayName("refuses an Other option, because the person can always write their own answer")
    void rejectsOtherLabel() {
        assertThatThrownBy(() ->
                        tool.parse(question("Audience", "Who is this for?", TEAM, option("Other", "Something else."))))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("Do not add an Other option; the person can always write their own answer.");
    }

    @Test
    @DisplayName("refuses arguments that are not a JSON object")
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> tool.parse("not json"))
                .isInstanceOf(AskPersonTool.Invalid.class)
                .hasMessage("The arguments were not valid JSON. Send an object with a questions array.");
        assertThatThrownBy(() -> tool.parse("[1,2]")).isInstanceOf(AskPersonTool.Invalid.class);
    }

    @Test
    @DisplayName("moves the option recommended by index to the front, flagged, keeping the others in order")
    void movesRecommendedFirstByIndex() throws Exception {
        String json = "{\"header\":\"Audience\",\"question\":\"Who is this for?\",\"recommended\":2,\"options\":["
                + String.join(",", TEAM, MANAGERS, CUSTOMER) + "]}";

        AskPersonTool.Question q = tool.parse(json).questions().getFirst();

        assertThat(q.options())
                .extracting(AskPersonTool.Option::label)
                .containsExactly("A customer", "The whole team", "Managers");
        assertThat(q.options()).extracting(AskPersonTool.Option::recommended).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("reads a (Recommended) suffix as the recommendation, and an index outranks it")
    void movesRecommendedFirstBySuffix() throws Exception {
        AskPersonTool.Question bySuffix = tool.parse(question(
                        "Audience", "Who is this for?", TEAM, option("Managers (Recommended)", "Decisions first.")))
                .questions()
                .getFirst();

        assertThat(bySuffix.options())
                .extracting(AskPersonTool.Option::label)
                .containsExactly("Managers", "The whole team");
        assertThat(bySuffix.options().getFirst().recommended()).isTrue();

        String both = "{\"header\":\"Audience\",\"question\":\"Who is this for?\",\"recommended\":0,\"options\":["
                + String.join(",", TEAM, option("Managers (recommended)", "Decisions first.")) + "]}";
        AskPersonTool.Question byIndex = tool.parse(both).questions().getFirst();

        assertThat(byIndex.options())
                .extracting(AskPersonTool.Option::label)
                .containsExactly("The whole team", "Managers");
        assertThat(byIndex.options())
                .extracting(AskPersonTool.Option::recommended)
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("shortens a long header and a long description at a word, rather than refusing them")
    void trimsLongHeaderAndDescription() throws Exception {
        String longDescription = "word ".repeat(40).strip();

        AskPersonTool.Question q = tool.parse(question(
                        "Reporting period for the review", "Which period?", TEAM, option("Managers", longDescription)))
                .questions()
                .getFirst();

        assertThat(q.header()).isEqualTo("Reporting period");
        assertThat(q.header().length()).isLessThanOrEqualTo(16);
        String description = q.options().get(1).description();
        assertThat(description.length()).isLessThanOrEqualTo(160);
        assertThat(description).endsWith("word");
    }

    @Test
    @DisplayName("numbers the questions q1 to qN in the order they were asked")
    void assignsIdsInOrder() throws Exception {
        String first = question("Audience", "Who is this for?", TEAM, MANAGERS);
        String second = "{\"header\":\"Include\",\"question\":\"What should it include?\",\"multiSelect\":true,"
                + "\"options\":[" + option("A summary", "Three lines.") + "," + option("Next steps", "Who does what.")
                + "]}";

        AskPersonTool.Ask ask = tool.parse("{\"questions\":[" + first + "," + second + "]}");

        assertThat(ask.questions()).extracting(AskPersonTool.Question::id).containsExactly("q1", "q2");
        assertThat(ask.questions().get(1).multiSelect()).isTrue();
    }
}
