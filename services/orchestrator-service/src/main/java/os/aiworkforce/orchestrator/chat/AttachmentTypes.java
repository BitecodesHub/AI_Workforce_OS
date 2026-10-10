// @find: allowed attachment types, accepted file types, refused file types, macro files blocked, docm xlsm refused, file type allow list, AttachmentTypes, chat file size, supported formats
// @what: Decides which file types Chat accepts, by detected content rather than file name.
// @flow: Called by AttachmentService when an upload is checked.
package os.aiworkforce.orchestrator.chat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which files Chat accepts, decided by what a file is rather than what it is called.
 *
 * <p>The type comes from the knowledge service's content detection, so a program renamed {@code
 * invoice.pdf} is detected as a program and refused. Macro-enabled Office files ({@code .docm},
 * {@code .xlsm}) are detected as their own types and are not on this list, so they are refused too.
 */
final class AttachmentTypes {

    private AttachmentTypes() {}

    /** The most one file may be. */
    static final long MAX_BYTES = 25L * 1024 * 1024;
    /** The most files one message may carry. */
    static final int MAX_PER_MESSAGE = 10;

    static final String UNSUPPORTED =
            " is not a file type Chat can read. Attach a PDF, Word, PowerPoint, Excel or CSV file, text, or an image.";

    private static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "document"),
            Map.entry("application/msword", "document"),
            Map.entry("application/rtf", "document"),
            Map.entry("application/vnd.oasis.opendocument.text", "document"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "presentation"),
            Map.entry("application/vnd.ms-powerpoint", "presentation"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "presentation"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "spreadsheet"),
            Map.entry("application/vnd.ms-excel", "spreadsheet"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "spreadsheet"),
            Map.entry("text/csv", "spreadsheet"),
            Map.entry("text/tab-separated-values", "spreadsheet"),
            Map.entry("text/plain", "text"),
            Map.entry("text/markdown", "text"),
            Map.entry("text/x-web-markdown", "text"),
            Map.entry("application/json", "text"),
            Map.entry("text/html", "text"),
            Map.entry("application/xhtml+xml", "text"),
            Map.entry("image/png", "image"),
            Map.entry("image/jpeg", "image"),
            Map.entry("image/webp", "image"),
            Map.entry("image/gif", "image"),
            Map.entry("image/heic", "image"),
            Map.entry("image/heif", "image"));

    /** The bare type, without parameters such as a character set. */
    static String bare(String mediaType) {
        if (mediaType == null) {
            return "application/octet-stream";
        }
        int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    /** {@code pdf}, {@code document}, {@code presentation}, {@code spreadsheet}, {@code text} or {@code image}. */
    static Optional<String> kindOf(String mediaType) {
        return Optional.ofNullable(KINDS.get(bare(mediaType)));
    }

    /**
     * The standard anti-virus test file. There is no virus scanner in this platform; this refuses
     * the one file every scanner is tested with, so a test of the upload path shows a refusal
     * rather than a stored "virus", and the list of what is checked stays honest.
     */
    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"
            .getBytes(StandardCharsets.US_ASCII);

    static boolean isTestVirus(byte[] content) {
        outer:
        for (int i = 0; i <= content.length - EICAR.length && i < 1024; i++) {
            for (int j = 0; j < EICAR.length; j++) {
                if (content[i + j] != EICAR[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** "PDF, 3 pages" and the like, for the header a run reads above a file's text. */
    static String describe(String kind, Integer pages) {
        String label = switch (kind) {
            case "pdf" -> "PDF";
            case "document" -> "Word document";
            case "presentation" -> "presentation";
            case "spreadsheet" -> "spreadsheet";
            case "image" -> "image";
            default -> "text file";
        };
        if (pages != null && pages > 0) {
            return label + ", " + pages + (pages == 1 ? " page" : " pages");
        }
        return label;
    }
}
