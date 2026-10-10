// @find: tests for documents prompt, chat, numbers passages in order, cuts long passage content, caps whole prompt, no passages, DocumentsPromptTest, DocumentsPrompt
// @what: Tests for DocumentsPrompt in the orchestrator chat package (4 test methods).
// @flow: Exercises DocumentsPrompt
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentsPromptTest {

    private static Map<String, Object> passage(String title, Integer page, String heading, String content) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("documentTitle", title);
        p.put("pageNumber", page);
        p.put("heading", heading);
        p.put("content", content);
        return p;
    }

    // @find: test numbers passages in order, documents prompt
    @Test
    @DisplayName("passages are numbered in order, each labelled with its document title, page and heading")
    void numbersPassagesInOrder() {
        List<Map<String, Object>> passages = List.of(
                passage("Leave policy", 2, "Annual leave", "Employees accrue leave monthly."),
                passage("Handbook", null, null, "General information."));

        String prompt = DocumentsPrompt.build("How much leave do I get?", passages);

        assertThat(prompt).contains("Question: How much leave do I get?");
        assertThat(prompt).contains("[1] Leave policy, page 2, Annual leave");
        assertThat(prompt).contains("Employees accrue leave monthly.");
        assertThat(prompt).contains("[2] Handbook");
        assertThat(prompt).contains("General information.");
        assertThat(prompt.indexOf("[1] Leave policy")).isLessThan(prompt.indexOf("[2] Handbook"));
    }

    // @find: test cuts long passage content, documents prompt
    @Test
    @DisplayName("a passage's content is cut at 1,200 characters, with an ellipsis")
    void cutsLongPassageContent() {
        String longContent = "x".repeat(2_000);
        List<Map<String, Object>> passages = List.of(passage("Doc", null, null, longContent));

        String prompt = DocumentsPrompt.build("q", passages);

        assertThat(prompt).contains("x".repeat(1_200) + "…");
        assertThat(prompt).doesNotContain("x".repeat(1_201));
    }

    // @find: test caps whole prompt, documents prompt
    @Test
    @DisplayName("the whole prompt is capped at 9,000 characters")
    void capsWholePrompt() {
        List<Map<String, Object>> passages = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            passages.add(passage("Doc " + i, i, "Heading " + i, "y".repeat(1_000)));
        }

        String prompt = DocumentsPrompt.build("q", passages);

        assertThat(prompt.length()).isLessThanOrEqualTo(9_000);
    }

    // @find: test no passages, documents prompt
    @Test
    @DisplayName("no passages still produces a well formed prompt with the question")
    void noPassages() {
        String prompt = DocumentsPrompt.build("What is our policy?", List.of());

        assertThat(prompt).contains("Question: What is our policy?");
        assertThat(prompt).contains("Passages:");
    }
}
