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

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Attach a file to the message being written, in a conversation or a chat not started yet")
    public AttachmentService.AttachmentView upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) UUID conversationId) {
        return attachments.upload(orgId(), RequestContext.requireActor(), conversationId, file.getOriginalFilename(), bytes(file));
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Attach a file to the message being written in this conversation")
    public AttachmentService.AttachmentView uploadTo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return attachments.upload(orgId(), RequestContext.requireActor(), id, file.getOriginalFilename(), bytes(file));
    }

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

    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Take back a file attached but not sent")
    public void delete(@PathVariable UUID attachmentId) {
        attachments.delete(orgId(), RequestContext.requireActor(), attachmentId);
    }

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

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The file could not be read.", e);
        }
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
