// @find: chat attachments, attach file to message, upload attachment, download attachment, delete attachment, save attachment to knowledge, AttachmentController, POST /api/conversations/attachments, GET /api/conversations/attachments/{id}/content, composer paperclip, file chip
// @what: REST endpoints for uploading, viewing, downloading, deleting and saving to the knowledge base the files attached to chat messages.
// @flow: Called by the Chat page composer; delegates to AttachmentService.
package os.aiworkforce.orchestrator.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Files attached to chat messages.
 *
 * <p>Uploaded one at a time while the message is being written, then named by id when it is sent
 * ({@code attachmentIds} on {@code POST /api/conversations/{id}/messages}). A file for a chat that
 * does not exist yet is uploaded without a conversation and bound to the one its first message
 * creates. Who may read a file follows its conversation; see {@link AttachmentService}.
 */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Chat")
public class AttachmentController {

    private final AttachmentService attachments;

    /**
     * Creates the controller with the service that holds all attachment rules; the controller only translates HTTP.
     */
    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    /**
     * Uploads one file, optionally into an existing conversation.
     *
     * <p>Route: {@code POST /api/conversations/attachments} (multipart, part {@code file}, optional
     * {@code conversationId}). Permission: {@code CHAT_USE}. Returns 201 with the stored attachment's
     * view. {@link AttachmentService#upload} detects the type, extracts text and stores the row; with no
     * conversation id the file stays a draft until its first message binds it. An unreadable upload fails
     * with {@code MALFORMED_REQUEST}; refusals of type, size or count come from the service as {@link
     * ApiException}.
     */
    // @find: upload attachment, attach file before chat exists, POST /api/conversations/attachments, composer paperclip
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Attach a file to the message being written, in a conversation or a chat not started yet")
    public AttachmentService.AttachmentView upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) UUID conversationId) {
        return attachments.upload(orgId(), RequestContext.requireActor(), conversationId, file.getOriginalFilename(), bytes(file));
    }

    /**
     * Uploads one file into a named conversation.
     *
     * <p>Route: {@code POST /api/conversations/{id}/attachments} (multipart, part {@code file}).
     * Permission: {@code CHAT_USE}. Returns 201 with the attachment's view. Behaves as {@link #upload},
     * except that the conversation is fixed by the path and its access rules are applied by the service.
     */
    // @find: upload attachment to a conversation, attach file, POST /api/conversations/{id}/attachments
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Attach a file to the message being written in this conversation")
    public AttachmentService.AttachmentView uploadTo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return attachments.upload(orgId(), RequestContext.requireActor(), id, file.getOriginalFilename(), bytes(file));
    }

    /**
     * Returns the details of one attachment without its bytes.
     *
     * <p>Route: {@code GET /api/conversations/attachments/{attachmentId}}. Permission: {@code CHAT_USE}.
     * The service refuses anyone who may not see the file.
     */
    // @find: get attachment details, attachment chip, GET /api/conversations/attachments/{id}
    @GetMapping("/attachments/{attachmentId}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "An attached file's details")
    public AttachmentService.AttachmentView get(@PathVariable UUID attachmentId) {
        return attachments.view(orgId(), RequestContext.requireActor(), attachmentId);
    }

    /**
     * The file itself. Served with its detected type, never sniffed again by the browser, and with
     * a sandbox policy, so an HTML file opened from here cannot run script as the console.
     */
    // @find: download attachment, view attachment content, open file, GET /api/conversations/attachments/{id}/content
    @GetMapping("/attachments/{attachmentId}/content")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Download or preview an attached file")
    public ResponseEntity<byte[]> content(
            @PathVariable UUID attachmentId, @RequestParam(defaultValue = "false") boolean download) {
        AttachmentService.Download file = attachments.download(orgId(), RequestContext.requireActor(), attachmentId);
        String type = file.row().mediaType();
        boolean inline = !download && (type.startsWith("image/") || type.equals("application/pdf") || type.startsWith("text/plain"));
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.row().name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(safeType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(file.content());
    }

    /**
     * Removes an attachment that has not been sent.
     *
     * <p>Route: {@code DELETE /api/conversations/attachments/{attachmentId}}. Permission: {@code
     * CHAT_USE}. Returns 204. The service decides whether the file may be deleted.
     */
    // @find: delete attachment, remove attached file, DELETE /api/conversations/attachments/{id}
    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Take back a file attached but not sent")
    public void delete(@PathVariable UUID attachmentId) {
        attachments.delete(orgId(), RequestContext.requireActor(), attachmentId);
    }

    /**
     * Saves an attached file into the workspace knowledge base.
     *
     * <p>Route: {@code POST /api/conversations/attachments/{attachmentId}/knowledge}. Permission: {@code
     * CHAT_USE}. The caller's own {@code Authorization} header is forwarded, so the knowledge service
     * applies the caller's rights. Returns the {@link AttachmentService.Saved} result.
     */
    // @find: save attachment to knowledge base, add chat file to documents, POST /api/conversations/attachments/{id}/knowledge
    @PostMapping("/attachments/{attachmentId}/knowledge")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Add an attached file to the workspace's knowledge base, as the person asking")
    public AttachmentService.Saved saveToKnowledge(@PathVariable UUID attachmentId, HttpServletRequest request) {
        return attachments.saveToKnowledge(
                orgId(), RequestContext.requireActor(), attachmentId, request.getHeader("Authorization"));
    }

    /** HTML is sent as plain text: it is a file to read, not a page for the console's origin to render. */
    private static MediaType safeType(String type) {
        if (type.equals("text/html") || type.equals("application/xhtml+xml")) {
            return MediaType.TEXT_PLAIN;
        }
        try {
            return MediaType.parseMediaType(type);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Reads the uploaded bytes into memory; an I/O failure becomes a {@code MALFORMED_REQUEST} error. */
    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The file could not be read.", e);
        }
    }

    /** The workspace of the current request, taken from the request context. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
