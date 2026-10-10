// @find: tests for passage relevance set, chat, labelled set passes, set covers every kind, PassageRelevanceSetTest, PassageRelevanceSet
// @what: Tests for PassageRelevanceSet in the orchestrator chat package (2 test methods).
// @flow: Exercises PassageRelevanceSet
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relevance gate against a labelled set of real questions and the passages live retrieval
 * actually offered for them (see {@code labelled-set.json}): document questions and paraphrases
 * that share no keyword with their passage must be grounded in the right document; small talk,
 * questions about the assistant, off-topic questions and actions in a connected service must not
 * be grounded in anything.
 *
 * <p>Each item is replayed the way chat sees it: the keyword hits, plus the passages found by
 * meaning at or above the floor the knowledge service applies for the embedding model, each with
 * its similarity. The set as the gate scored it with keyword search only (before embeddings were
 * real) is computed too, so a change that loses ground shows as a number, not a feeling.
 */
class PassageRelevanceSetTest {

    private static JsonNode set;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = PassageRelevanceSetTest.class.getResourceAsStream("/relevance/labelled-set.json")) {
            set = new ObjectMapper().readTree(in);
        }
    }

    record Outcome(String query, String expect, boolean passed, List<String> grounded) {}

    private static List<Outcome> score(boolean withMeaning) {
        double floor = set.path("meaningFloor").asDouble();
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode item : set.path("items")) {
            String query = item.path("query").asText();
            List<KnowledgeClient.Passage> offered = new ArrayList<>();
            int rank = 0;
            for (JsonNode passage : item.path("passages")) {
                boolean keyword = passage.path("keyword").asBoolean(false);
                Double similarity = passage.path("similarity").isNumber() ? passage.path("similarity").asDouble() : null;
                boolean viaMeaning = withMeaning && similarity != null && similarity >= floor;
                if (!keyword && !viaMeaning) {
                    continue;
                }
                offered.add(new KnowledgeClient.Passage(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        passage.path("title").asText(),
                        null,
                        null,
                        passage.path("heading").isTextual() ? passage.path("heading").asText() : null,
                        passage.path("content").asText(),
                        1.0 - (rank++ * 0.1),
                        false,
                        viaMeaning ? similarity : null));
            }
            List<String> grounded = PassageRelevance.relevant(query, offered).stream()
                    .map(KnowledgeClient.Passage::documentTitle)
                    .toList();
            String expect = item.path("expect").asText();
            boolean passed = expect.equals("ground")
                    ? grounded.contains(item.path("document").asText())
                    : grounded.isEmpty();
            outcomes.add(new Outcome(query, expect, passed, grounded));
        }
        return outcomes;
    }

    // @find: test labelled set passes, passage relevance set
    @Test
    @DisplayName("Every labelled question is grounded, or not, as labelled")
    void labelledSetPasses() {
        List<Outcome> before = score(false);
        List<Outcome> after = score(true);
        long passedBefore = before.stream().filter(Outcome::passed).count();
        long passedAfter = after.stream().filter(Outcome::passed).count();
        System.out.printf(
                "Relevance set: %d of %d with keyword search only, %d of %d with search by meaning%n",
                passedBefore, before.size(), passedAfter, after.size());
        before.stream()
                .filter(outcome -> !outcome.passed())
                .forEach(outcome -> System.out.println("  keyword only misses: " + outcome.query()));

        assertThat(set.path("items").size()).isGreaterThanOrEqualTo(20);
        assertThat(after)
                .as("labelled questions the gate gets wrong")
                .filteredOn(outcome -> !outcome.passed())
                .isEmpty();
    }

    // @find: test set covers every kind, passage relevance set
    @Test
    @DisplayName("The set covers each kind of message the gate must tell apart")
    void setCoversEveryKind() {
        List<String> kinds = new ArrayList<>();
        set.path("items").forEach(item -> kinds.add(item.path("kind").asText()));
        assertThat(kinds).contains("document", "paraphrase", "small_talk", "meta", "off_topic", "action");
    }
}
