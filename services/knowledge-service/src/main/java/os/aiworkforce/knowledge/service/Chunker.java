package os.aiworkforce.knowledge.service;

import os.aiworkforce.knowledge.repository.Chunks;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Cuts a document into passages that can be retrieved and cited.
 *
 * <p>Chunking decides how good retrieval can possibly be, and the two obvious approaches both
 * fail. Cutting on a fixed character count splits sentences mid-clause, so a retrieved passage
 * starts halfway through the thought it was meant to convey. Cutting on paragraphs produces
 * chunks of wildly different sizes, and a three-word paragraph carries almost no signal to match
 * against.
 *
 * <p>This splits on structure first - headings, then paragraphs, then sentences - and only falls
 * back to a hard character cut when a single sentence exceeds the budget. Consecutive small
 * pieces are merged up to the target size, so the result is both coherent and evenly sized.
 *
 * <p>Chunks overlap. Without overlap, a fact stated across a chunk boundary is retrievable from
 * neither side, and that failure is invisible: the search simply returns nothing relevant.
 */
@Component
public class Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6}\\s+.+|[A-Z][A-Z \\d.-]{6,})$", Pattern.MULTILINE);
    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"'(\\[])");

    /**
     * @param content the passage
     * @param position its order within the document
     * @param heading the nearest heading above it, which gives a retrieved passage its context
     * @param charStart offset in the original text, so a citation can point at the exact place
     * @param charEnd end offset
     */
    public record Chunk(String content, int position, String heading, int charStart, int charEnd) {

        public int tokenEstimate() {
            return Math.max(1, content.length() / 3);
        }
    }

    public List<Chunk> chunk(String text, int targetSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int target = Math.max(200, targetSize);
        int lap = Math.min(Math.max(0, overlap), target / 3);

        List<Section> sections = splitByHeading(text);
        List<Chunk> chunks = new ArrayList<>();
        int position = 0;

        for (Section section : sections) {
            for (Piece piece : splitToBudget(section.body(), section.offset(), target)) {
                String content = withOverlap(chunks, piece.text(), lap);
                chunks.add(new Chunk(content, position++, section.heading(), piece.start(), piece.end()));
            }
        }
        return chunks;
    }

    private record Section(String heading, String body, int offset) {}

    private record Piece(String text, int start, int end) {}

    /**
     * Splits on headings, keeping each heading with the text beneath it.
     *
     * <p>The heading is carried onto every chunk in the section. A passage that reads "the limit
     * is 30 days" is useless without knowing it sat under "Refund policy", and the model has no
     * way to recover that from the passage alone.
     */
    private List<Section> splitByHeading(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(text);

        int previousEnd = 0;
        String currentHeading = null;
        while (matcher.find()) {
            if (matcher.start() > previousEnd) {
                String body = text.substring(previousEnd, matcher.start());
                if (!body.isBlank()) {
                    sections.add(new Section(currentHeading, body, previousEnd));
                }
            }
            currentHeading = matcher.group().replaceAll("^#+\\s*", "").strip();
            previousEnd = matcher.end();
        }
        String tail = text.substring(previousEnd);
        if (!tail.isBlank()) {
            sections.add(new Section(currentHeading, tail, previousEnd));
        }
        return sections.isEmpty() ? List.of(new Section(null, text, 0)) : sections;
    }

    /** Merges paragraphs up to the budget, splitting sentences only where one is oversized. */
    private List<Piece> splitToBudget(String body, int offset, int target) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferStart = offset;
        int cursor = offset;

        for (String paragraph : PARAGRAPH.split(body)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                cursor += paragraph.length();
                continue;
            }

            if (trimmed.length() > target) {
                if (!buffer.isEmpty()) {
                    pieces.add(new Piece(buffer.toString().strip(), bufferStart, cursor));
                    buffer.setLength(0);
                }
                // A single paragraph over budget is split on sentences, and only a sentence that
                // is itself over budget is cut mid-text.
                for (String sentence : SENTENCE.split(trimmed)) {
                    if (buffer.length() + sentence.length() > target && !buffer.isEmpty()) {
                        pieces.add(new Piece(buffer.toString().strip(), bufferStart, cursor));
                        buffer.setLength(0);
                        bufferStart = cursor;
                    }
                    if (sentence.length() > target) {
                        for (int i = 0; i < sentence.length(); i += target) {
                            pieces.add(new Piece(
                                    sentence.substring(i, Math.min(sentence.length(), i + target)),
                                    cursor + i,
                                    cursor + Math.min(sentence.length(), i + target)));
                        }
                    } else {
                        buffer.append(sentence).append(' ');
                    }
                }
            } else if (buffer.length() + trimmed.length() > target) {
                pieces.add(new Piece(buffer.toString().strip(), bufferStart, cursor));
                buffer.setLength(0);
                bufferStart = cursor;
                buffer.append(trimmed).append("\n\n");
            } else {
                buffer.append(trimmed).append("\n\n");
            }
            cursor += paragraph.length() + 2;
        }

        if (!buffer.isEmpty()) {
            pieces.add(new Piece(buffer.toString().strip(), bufferStart, cursor));
        }
        return pieces;
    }

    /**
     * Prepends the tail of the previous chunk.
     *
     * <p>This is what stops a fact stated across a boundary from being unfindable. The overlap is
     * cut at a word boundary, because a chunk beginning mid-word matches nothing and reads badly
     * when it is shown as a citation.
     */
    private String withOverlap(List<Chunk> existing, String text, int overlap) {
        if (overlap <= 0 || existing.isEmpty()) {
            return text;
        }
        String previous = existing.get(existing.size() - 1).content();
        if (previous.length() <= overlap) {
            return previous + "\n" + text;
        }
        String tail = previous.substring(previous.length() - overlap);
        int wordBoundary = tail.indexOf(' ');
        if (wordBoundary > 0) {
            tail = tail.substring(wordBoundary + 1);
        }
        return tail + "\n" + text;
    }
}
