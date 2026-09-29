package os.aiworkforce.knowledge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls readable text out of a file, or explains why it could not.
 *
 * <p>The explanation matters as much as the text. A corpus where a fifth of the documents silently
 * produced nothing will answer some questions badly, and the only way anybody finds out which is
 * if each failure says what it was: an encrypted file needs a password, a scanned page needs
 * optical character recognition, and an empty file needs replacing. "Ingestion failed" sends
 * somebody looking in the wrong place for all three.
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    /**
     * Below this many characters per page, a PDF is almost certainly a scan.
     *
     * <p>Scanned pages are not empty - they carry a few stray characters from headers, page
     * numbers or OCR artefacts already embedded by the scanner. Treating them as successfully
     * extracted produces chunks of noise that match nothing and dilute every search.
     */
    private static final int MIN_CHARS_PER_PAGE = 60;

    private final Tika tika = new Tika();

    /**
     * @param text the extracted text, empty when the document is not indexable
     * @param mediaType what the file actually is, detected from content rather than the extension
     * @param pageCount pages, where the format has them
     * @param contentHash identifies the exact bytes, so an unchanged file is skipped on re-ingest
     * @param skipReason why it cannot be indexed, written for a person to act on
     */
    public record Extraction(String text, String mediaType, Integer pageCount, String contentHash, String skipReason) {

        public boolean isIndexable() {
            return skipReason == null && text != null && !text.isBlank();
        }
    }

    public Extraction extract(byte[] content, String filename) {
        String hash = sha256(content);

        if (content.length == 0) {
            return new Extraction("", "application/octet-stream", null, hash, "The file is empty.");
        }

        String mediaType;
        try {
            mediaType = tika.detect(content, filename);
        } catch (RuntimeException e) {
            mediaType = "application/octet-stream";
        }

        if (mediaType.startsWith("application/pdf")) {
            return extractPdf(content, hash, mediaType);
        }
        if (mediaType.startsWith("image/")) {
            // Detected before Tika is asked for text, because Tika returns an empty string for an
            // image and that is indistinguishable from a genuinely empty document.
            return new Extraction(
                    "",
                    mediaType,
                    null,
                    hash,
                    "The file is an image with no text layer. Run it through optical character "
                            + "recognition before indexing it.");
        }

        try (InputStream stream = new ByteArrayInputStream(content)) {
            String text = tika.parseToString(stream);
            if (text == null || text.isBlank()) {
                return new Extraction("", mediaType, null, hash, "No readable text could be extracted from this file.");
            }
            return new Extraction(normalise(text), mediaType, null, hash, null);
        } catch (Exception e) {
            log.debug("Extraction failed for {}: {}", filename, e.toString());
            return new Extraction(
                    "",
                    mediaType,
                    null,
                    hash,
                    "The file could not be read: " + e.getClass().getSimpleName() + ".");
        }
    }

    /**
     * PDFs are handled directly rather than through Tika.
     *
     * <p>Two reasons. Page numbers are needed for citations and Tika does not surface them, and
     * an encrypted PDF has to be distinguished from an unreadable one because the remedy is
     * completely different.
     */
    private Extraction extractPdf(byte[] content, String hash, String mediaType) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                return new Extraction(
                        "",
                        mediaType,
                        document.getNumberOfPages(),
                        hash,
                        "The PDF is password protected, so its contents cannot be read.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            int pages = document.getNumberOfPages();

            if (text == null || text.isBlank()) {
                return new Extraction(
                        "",
                        mediaType,
                        pages,
                        hash,
                        "The PDF has no text layer. It is most likely a scan, and needs optical "
                                + "character recognition before it can be indexed.");
            }

            if (pages > 0 && text.length() / pages < MIN_CHARS_PER_PAGE) {
                return new Extraction(
                        "",
                        mediaType,
                        pages,
                        hash,
                        "The PDF holds very little text for its length, which usually means it is a "
                                + "scan. Run optical character recognition over it before indexing.");
            }

            return new Extraction(normalise(text), mediaType, pages, hash, null);
        } catch (IOException e) {
            return new Extraction("", mediaType, null, hash, "The PDF is damaged and could not be opened.");
        }
    }

    /**
     * Tidies extracted text without changing what it says.
     *
     * <p>PDF extraction produces hard line breaks mid-sentence, which split a sentence across two
     * chunks and make the retrieved passage read as gibberish. Joining them back is the single
     * largest improvement available to chunk quality, and it changes no words.
     */
    private static String normalise(String text) {
        return text
                // A line break between two lower-case letters is a wrapped line, not a paragraph.
                .replaceAll("(?<=[a-z,;])\\n(?=[a-z])", " ")
                // A hyphen at a line end is a word split across lines.
                .replaceAll("(?<=[a-z])-\\n(?=[a-z])", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** Identifies the exact bytes, so an unchanged file is skipped rather than re-embedded. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
