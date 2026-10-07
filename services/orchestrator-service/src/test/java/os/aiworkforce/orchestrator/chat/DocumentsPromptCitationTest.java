package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DocumentsPromptCitationTest {

    private static final List<Map<String, Object>> PASSAGES =
            List.of(Map.<String, Object>of("documentTitle", "Leave policy", "content", "Staff get 20 days."));

    @Test
    void documentsPromptTellsTheModelToCiteOnlySupportingPassages() {
        String prompt = DocumentsPrompt.build("How much leave?", PASSAGES);
        assertThat(prompt)
                .contains("only for a statement that passage actually supports")
                .contains("never list or cite a passage that does not help")
                .contains("answer from general knowledge or say you do not know, without citing anything");
    }

    @Test
    void passageBlockInstructionCarriesTheSameRule() {
        String block = CoordinatorService.withKnowledge("", PASSAGES);
        assertThat(block).contains(DocumentsPrompt.CITATION_RULE).contains(DocumentsPrompt.UNTRUSTED_RULE);
    }
}
