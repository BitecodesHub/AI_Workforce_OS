// @find: knowledge base, knowledge, documents, sources, text extraction, extract text from file, PDF, Word docx, Excel, PowerPoint, CSV, plain text, scanned PDF, skip reason, content hash sha256, page count, max characters, file types supported, upload parsing, TextExtractor
// @what: Turns an uploaded file's bytes into plain text, reporting media type, page count, hash and why a file was skipped.
// @flow: Called by IngestionService.ingest and InternalExtractController (chat attachments).
package os.aiworkforce.knowledge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/**
 * Pulls readable text out of a file, or explains why it could not.
 *
 * <p>The explanation matters as much as the text. A corpus where a fifth of the documents silently
 * produced nothing will answer some questions badly, and the only way anybody finds out which is
 * if each failure says what it was: an encrypted file needs a password, a scanned page needs
 * optical character recognition, and an empty file needs replacing. "Ingestion failed" sends
 * somebody looking in the wrong place for all three.
 *
 * <p>Text is kept up to a stated limit, {@code knowledge.extraction.max-chars}, and a document cut
 * at it says so. The Tika facade this used to call stopped at 100,000 characters without a word,
 * so a 300-page handbook reported success while only its first 40 or so pages could be found. A
 * limit is still needed - a 25 MB spreadsheet can expand to tens of millions of characters, every
 * one of them chunked and embedded inside the upload - but it is generous, and never silent.
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

    /** The limit when none is configured: roughly 1,700 printed pages. */
    public static final int DEFAULT_MAX_CHARS = 5_000_000;

    /**
     * About how much text a printed page holds, for describing a cut in pages when the format
     * has none. Five hundred words of six characters, near enough for "about N pages".
     */
    static final int CHARS_PER_PAGE = 3_000;

    /** Detection only. Text goes through {@link #parser}, where the limit can be seen being hit. */
    private final Tika tika = new Tika();

    private final Parser parser = new AutoDetectParser();
    private final int maxChars;

    public TextExtractor(@Value("${knowledge.extraction.max-chars:" + DEFAULT_MAX_CHARS + "}") int maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * @param text the extracted text, empty when the document is not indexable
     * @param mediaType what the file actually is, detected from content rather than the extension
     * @param pageCount pages, where the format has them
     * @param contentHash identifies the exact bytes, so an unchanged file is skipped on re-ingest
     * @param skipReason why it cannot be indexed, written for a person to act on
     * @param notice something worth knowing that does not stop indexing, such as the text having
     *     been cut at the limit; never a reason to skip, which is what {@code skipReason} is for
     * @param textPages the pages {@code text} spans, for placing a passage on a page: fewer than
     *     {@code pageCount} only when a long PDF was cut at the limit
     */
    public record Extraction(
            String text,
            String mediaType,
            Integer pageCount,
            String contentHash,
            String skipReason,
            String notice,
            Integer textPages) {

        /** An extraction with nothing to note: indexed whole, or not at all. */
        public Extraction(String text, String mediaType, Integer pageCount, String contentHash, String skipReason) {
            this(text, mediaType, pageCount, contentHash, skipReason, null, pageCount);
        }

        public boolean isIndexable() {
            return skipReason == null && text != null && !text.isBlank();
        }
    }

    // @find: extract text from uploaded file, read PDF docx xlsx pptx, unsupported file skipped
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

        try {
            Parsed parsed = parse(content, filename);
            if (parsed.text() == null || parsed.text().isBlank()) {
                return new Extraction("", mediaType, null, hash, "No readable text could be extracted from this file.");
            }
            String notice = parsed.truncated() ? truncationNotice(Math.max(1, maxChars / CHARS_PER_PAGE)) : null;
            return new Extraction(normalise(parsed.text()), mediaType, null, hash, null, notice, null);
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

    private record Parsed(String text, boolean truncated) {}

    /**
     * Parses with a limit it can see being reached.
     *
     * <p>The limit surfaces as a SAX exception from inside the parse, sometimes wrapped by the
     * parser that was running, so the whole cause chain is checked. Everything written before it is
     * kept: that is the part of the document that will be indexed. A parse that ends normally with
     * the limit filled counts as cut too, because a parser reading an embedded file can swallow
     * the exception and carry on.
     */
    private Parsed parse(byte[] content, String filename) throws IOException, SAXException, TikaException {
        BodyContentHandler handler = new BodyContentHandler(new WriteOutContentHandler(maxChars > 0 ? maxChars : -1));
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        ParseContext context = new ParseContext();
        // Set as the facade did, so text inside embedded files (a sheet in a deck) is still read.
        context.set(Parser.class, parser);
        try (InputStream stream = new ByteArrayInputStream(content)) {
            parser.parse(stream, handler, metadata, context);
            String text = handler.toString();
            return new Parsed(text, maxChars > 0 && text.length() >= maxChars);
        } catch (SAXException | TikaException | IOException e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                return new Parsed(handler.toString(), true);
            }
            throw e;
        }
    }

    /** Said in characters, which is exact, and in pages, which is what a person can picture. */
    private String truncationNotice(long aboutPages) {
        return String.format(
                Locale.ENGLISH,
                "Only the first %,d characters (about %,d %s) were indexed. Split the file into smaller "
                        + "ones to index the rest.",
                maxChars,
                aboutPages,
                aboutPages == 1 ? "page" : "pages");
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

            if (maxChars > 0 && text.length() > maxChars) {
                // Cut after the scan check above, which needs the whole text. The pages the kept
                // part spans are estimated from its share of the text, so citations stay in range.
                int textPages = Math.max(1, (int) Math.round((double) pages * maxChars / text.length()));
                return new Extraction(
                        normalise(text.substring(0, maxChars)),
                        mediaType,
                        pages,
                        hash,
                        null,
                        truncationNotice(textPages),
                        textPages);
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

    // @find: content hash, detect unchanged file on re-upload
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
