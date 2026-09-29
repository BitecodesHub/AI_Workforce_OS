package os.aiworkforce.orchestrator.chat;

import java.util.List;
import java.util.Map;

/**
 * Builds the instruction an agent is given to answer a question from the workspace's own document
 * passages, and nothing else - the whole point of "Answer from these passages" is that the agent
 * is not left free to fall back on general knowledge without saying so.
 */
final class DocumentsPrompt {

    private DocumentsPrompt() {}

    private static final int MAX_TOTAL_CHARS = 9_000;
    private static final int MAX_PASSAGE_CHARS = 1_200;

    private static final String HEADER = "Answer the question below using only these passages from the "
            + "workspace's documents. Cite the passage number and document title for each point, for example "
            + "[2] Leave policy. Say plainly what the passages do not cover.\n\nQuestion: ";

    /**
     * @param passages each holding at least {@code documentTitle} and {@code content}, and
     *     optionally {@code pageNumber} and {@code heading}
     */
    static String build(String query, List<Map<String, Object>> passages) {
        StringBuilder text =
                new StringBuilder(HEADER).append(query == null ? "" : query).append("\n\nPassages:\n");
        int index = 1;
        for (Map<String, Object> passage : passages) {
            String title = stringOf(passage.get("documentTitle"));
            Object page = passage.get("pageNumber");
            String heading = stringOf(passage.get("heading"));
            StringBuilder label =
                    new StringBuilder("[").append(index).append("] ").append(title);
            if (page != null) {
                label.append(", page ").append(page);
            }
            if (heading != null && !heading.isBlank()) {
                label.append(", ").append(heading);
            }
            text.append(label)
                    .append('\n')
                    .append(cut(stringOf(passage.get("content"))))
                    .append("\n\n");
            index++;
        }
        String result = text.toString().stripTrailing();
        return result.length() <= MAX_TOTAL_CHARS
                ? result
                : result.substring(0, MAX_TOTAL_CHARS).stripTrailing();
    }

    private static String cut(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_PASSAGE_CHARS ? content : content.substring(0, MAX_PASSAGE_CHARS) + "…";
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
