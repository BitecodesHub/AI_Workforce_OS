package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Long files: read in full up to the limit, and said to be cut when they reach it.
 *
 * <p>The Tika facade this replaced stopped at 100,000 characters and reported success, so a long
 * handbook was indexed to about page 40 without a word. These pin both halves of the fix: well past
 * that point nothing is lost, and past the configured limit the document is still indexed and
 * carries a notice rather than a skip reason.
 */
class TextExtractorTest {

    /** Distinct numbered lines, so a missing stretch would show in the count. */
    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("Line ").append(i).append(" of the staff handbook covers leave and travel.\n");
        }
        return text.toString();
    }

    @Test
    @DisplayName("plain text well past 150,000 characters is extracted in full under the default limit")
    void longTextIsKeptWhole() {
        String text = lines(4_000);
        assertThat(text.length()).isGreaterThan(150_000);

        TextExtractor.Extraction extraction = new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS)
                .extract(text.getBytes(StandardCharsets.UTF_8), "handbook.txt");

        assertThat(extraction.isIndexable()).isTrue();
        assertThat(extraction.notice()).isNull();
        assertThat(extraction.text()).contains("Line 0 of the staff handbook").contains("Line 3999 of the staff handbook");
        assertThat(extraction.text().length()).isGreaterThan(150_000);
    }

    @Test
    @DisplayName("HTML past the old 100,000-character stop is read to the end as well")
    void longHtmlIsKeptWhole() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 3_000; i++) {
            html.append("<p>Paragraph ").append(i).append(" explains the expenses policy in some detail.</p>");
        }
        html.append("</body></html>");

        TextExtractor.Extraction extraction = new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS)
                .extract(html.toString().getBytes(StandardCharsets.UTF_8), "policy.html");

        assertThat(extraction.notice()).isNull();
        assertThat(extraction.text()).contains("Paragraph 2999 explains");
    }

    @Test
    @DisplayName("text over the limit is cut there, still indexed, and says how much was kept")
    void overTheLimitCarriesANotice() {
        String text = lines(4_000);

        TextExtractor.Extraction extraction =
                new TextExtractor(30_000).extract(text.getBytes(StandardCharsets.UTF_8), "handbook.txt");

        assertThat(extraction.isIndexable()).isTrue();
        assertThat(extraction.skipReason()).isNull();
        assertThat(extraction.text().length()).isLessThanOrEqualTo(30_000);
        assertThat(extraction.text()).contains("Line 0 of the staff handbook").doesNotContain("Line 3999");
        assertThat(extraction.notice())
                .isEqualTo("Only the first 30,000 characters (about 10 pages) were indexed. Split the file into "
                        + "smaller ones to index the rest.");
    }

    @Test
    @DisplayName("a file under the limit has no notice, and an empty one is skipped with its reason")
    void noticeOnlyWhenCut() {
        TextExtractor extractor = new TextExtractor(30_000);

        assertThat(extractor.extract("A short note.".getBytes(StandardCharsets.UTF_8), "note.txt").notice())
                .isNull();

        TextExtractor.Extraction empty = extractor.extract(new byte[0], "empty.txt");
        assertThat(empty.isIndexable()).isFalse();
        assertThat(empty.skipReason()).isEqualTo("The file is empty.");
        assertThat(empty.notice()).isNull();
    }
}
