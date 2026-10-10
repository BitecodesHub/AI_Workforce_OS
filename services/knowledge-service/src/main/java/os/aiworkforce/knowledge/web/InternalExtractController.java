// @find: attachments extraction, extract text from attachment, chat attachment text, read uploaded file in chat, POST /internal/knowledge/extract, PDF docx text, TextExtractor, InternalExtractController, knowledge, documents
// @what: Internal endpoint that returns the plain text of a file without storing it, used for chat attachments.
// @flow: Called by orchestrator-service; delegates to TextExtractor.
package os.aiworkforce.knowledge.web;

import java.io.IOException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.knowledge.service.TextExtractor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Reads a file's text for another service, without adding it to the knowledge base.
 *
 * <p>Chat lets a person attach a file to one message. That file belongs to the conversation, not
 * to the workspace's documents, so it is neither stored nor indexed here; but reading a PDF, a
 * Word file or a spreadsheet is exactly what {@link TextExtractor} already does for an upload, and
 * doing it in a second place would mean two sets of parsers to keep patched and two answers to
 * "why could this file not be read". So the orchestrator sends the bytes here and gets back what
 * the file really is - detected from its content, not its name - and its text, or the reason there
 * is none.
 *
 * <p>Service tokens only. Nothing about the workspace is read or written, so no person's
 * permissions are asked: the orchestrator has already decided this person may attach the file.
 */
@RestController
@RequestMapping("/internal/knowledge")
@Tag(name = "Internal")
public class InternalExtractController {

    /** The most text returned when the caller does not say; about 65 printed pages. */
    static final int DEFAULT_MAX_CHARS = 200_000;
    /** The most text ever returned, whatever the caller asks for. */
    static final int MAX_CHARS = 1_000_000;

    /**
     * @param mediaType what the file is, detected from its bytes
     * @param text the readable text, cut at the limit; empty when there is none
     * @param pageCount pages, for a format that has them
     * @param contentHash identifies the exact bytes
     * @param problem why no text could be read, in words a person can act on; null when it was read
     * @param notice something worth knowing that did not stop the reading
     * @param truncated whether {@code text} was cut at the limit
     */
    public record Extracted(
            String mediaType,
            String text,
            Integer pageCount,
            String contentHash,
            String problem,
            String notice,
            boolean truncated) {}

    private final TextExtractor extractor;

    public InternalExtractController(TextExtractor extractor) {
        this.extractor = extractor;
    }

    // @find: extract text from attachment, POST /internal/knowledge/extract
    @PostMapping(value = "/extract", consumes = "multipart/form-data")
    @Operation(summary = "Internal: detect what a file is and read its text, without indexing it")
    public Extracted extract(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "maxChars", required = false) Integer maxChars) {
        requireService();
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload"
                : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The file could not be read.", e);
        }
        return extract(bytes, name, maxChars, extractor);
    }

    /** The reading itself, apart from the web layer, so it can be tested on bytes alone. */
    static Extracted extract(byte[] bytes, String name, Integer maxChars, TextExtractor extractor) {
        int limit = maxChars == null || maxChars <= 0 ? DEFAULT_MAX_CHARS : Math.min(maxChars, MAX_CHARS);
        TextExtractor.Extraction found = extractor.extract(bytes, name);
        String text = found.text() == null ? "" : found.text();
        boolean truncated = text.length() > limit;
        if (truncated) {
            text = text.substring(0, limit);
        }
        String problem = found.isIndexable() ? null : found.skipReason();
        if (problem == null && text.isBlank()) {
            problem = "No readable text could be found in this file.";
        }
        return new Extracted(
                found.mediaType(),
                found.isIndexable() ? text : "",
                found.pageCount(),
                found.contentHash(),
                problem,
                found.notice(),
                truncated);
    }

    /** A person's own token must never reach this, whatever permissions it carries. */
    private static void requireService() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
    }
}
