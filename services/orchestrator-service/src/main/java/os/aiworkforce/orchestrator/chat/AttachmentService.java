// @find: attachment service, upload chat file, attach file to message, who can read attachment, delete attachment, save attachment to knowledge, sweep unsent attachments, AttachmentService, bind attachment to message, link attachment to goal
// @what: Takes in chat attachments, decides who may read them, binds them to the message that sends them and cleans up unsent ones.
// @flow: Called by AttachmentController and CoordinatorService; uses ChatAttachments, AttachmentReader and the knowledge service.
package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Files a person attaches to a chat message: taking them in, deciding who may read them, and
 * handing them to the message that sends them.
 *
 * <p>Who may read an attachment follows its conversation. A file not sent yet is the uploader's
 * alone - nobody else in the thread has been shown it - and a sent one is read by whoever may read
 * the conversation, private threads included. Anybody else is told it does not exist, exactly as
 * for the conversation itself, so a private thread's files are not confirmed by their ids.
 *
 * <p>A file is accepted for what it is, not what it is called: the knowledge service detects the
 * type from the bytes and reads the text in the same call, and a type that is not on {@link
 * AttachmentTypes}'s list is refused before anything is stored. There is no virus scanner in this
 * platform; nothing attached is ever run, only read as text or shown to a model as a picture.
 *
 * <p>Attachments are not part of the workspace's documents. A person who wants one there asks for
 * it ({@link #saveToKnowledge}), and it is uploaded as them, so the knowledge service's own rule
 * on who may add documents decides.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** The knowledge source files saved from chat go into, created the first time one is saved. */
    static final String KNOWLEDGE_SOURCE_NAME = "Saved from Chat";
    /** How long a file attached but never sent is kept. */
    static final Duration UNSENT_KEPT_FOR = Duration.ofHours(24);

    /**
     * What a person sees of an attachment: the chip in the composer, the card on a message.
     *
     * @param imageReadable for an image, whether its format is one a model that reads images can be
     *     shown; HEIC, for one, is not
     */
    public record AttachmentView(
            UUID id,
            UUID conversationId,
            String name,
            String mimeType,
            long size,
            String kind,
            String status,
            Integer pageCount,
            String problem,
            String notice,
            boolean imageReadable,
            boolean savedToKnowledge,
            Instant createdAt) {

        static AttachmentView of(ChatAttachments.Row row) {
            return new AttachmentView(
                    row.id(),
                    row.conversationId(),
                    row.name(),
                    row.mediaType(),
                    row.size(),
                    row.kind(),
                    row.status(),
                    row.pageCount(),
                    row.problem(),
                    row.notice(),
                    row.isImage() && os.aiworkforce.llm.model.ImagePart.isViewable(row.mediaType()),
                    row.knowledgeDocumentId() != null,
                    row.createdAt());
        }
    }

    /** A file and the bytes to send for a download. */
    public record Download(ChatAttachments.Row row, byte[] content) {}

    /** @param sourceName the knowledge source it went into */
    public record Saved(UUID documentId, String sourceName) {}

    private final ChatAttachments attachments;
    private final AttachmentReader reader;
    private final ConversationAccess access;
    private final os.aiworkforce.orchestrator.repository.Conversations conversations;
    private final WebClient knowledge;

    @Autowired
    public AttachmentService(
            ChatAttachments attachments,
            AttachmentReader reader,
            ConversationAccess access,
            os.aiworkforce.orchestrator.repository.Conversations conversations,
            WebClient.Builder builder,
            PlatformProperties properties) {
        this.attachments = attachments;
        this.reader = reader;
        this.access = access;
        this.conversations = conversations;
        this.knowledge = builder.clone().baseUrl(properties.services().knowledge()).build();
    }

    // ---- Taking a file in ----------------------------------------------------------------------

    /**
     * Stores one file for a message about to be written.
     *
     * @param conversationId the conversation it is for, or null for a chat its first message will create
     */
    // @find: upload attachment, add file to chat, accept attachment, size and type check
    public AttachmentView upload(UUID orgId, Actor actor, UUID conversationId, String filename, byte[] content) {
        String me = requirePerson(actor);
        if (conversationId != null) {
            access.require(orgId, actor, conversationId);
        }
        String name = cleanName(filename);
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, name + " is empty.");
        }
        if (content.length > AttachmentTypes.MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, name + " is larger than 25 MB.");
        }
        if (AttachmentTypes.isTestVirus(content)) {
            throw new ApiException(
                    ErrorCode.UNSUPPORTED_MEDIA_TYPE, name + " matches a known virus test signature, so it was not attached.");
        }

        AttachmentReader.Read read = reader.read(orgId, name, content);
        String mediaType = AttachmentTypes.bare(read.mediaType());
        String kind = AttachmentTypes.kindOf(mediaType)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, name + AttachmentTypes.UNSUPPORTED));

        boolean image = "image".equals(kind);
        String text = image ? null : read.text();
        boolean readable = image || (text != null && !text.isBlank());
        String problem = readable ? null : plainProblem(read.problem());
        String notice = read.truncated()
                ? "Only the first " + String.format(java.util.Locale.ENGLISH, "%,d", AttachmentReader.MAX_TEXT_CHARS)
                        + " characters of this file can be used."
                : read.notice();

        UUID id = UuidV7.generate();
        attachments.insert(new ChatAttachments.NewAttachment(
                id,
                orgId,
                conversationId,
                me,
                name,
                mediaType,
                kind,
                content,
                read.contentHash() == null ? "" : read.contentHash(),
                readable ? "ready" : "unreadable",
                readable ? text : null,
                read.pageCount(),
                problem,
                notice));
        log.info("Attachment {} ({}, {} bytes, {}) stored in workspace {}", id, kind, content.length, mediaType, orgId);
        return AttachmentView.of(attachments.find(orgId, id).orElseThrow());
    }

    // ---- Reading -------------------------------------------------------------------------------

    // @find: view attachment, attachment details
    public AttachmentView view(UUID orgId, Actor actor, UUID id) {
        return AttachmentView.of(readable(orgId, actor, id));
    }

    // @find: download attachment, read attachment content
    public Download download(UUID orgId, Actor actor, UUID id) {
        ChatAttachments.Row row = readable(orgId, actor, id);
        byte[] content = attachments.content(orgId, id).orElseThrow(() -> ApiException.notFound("attachment", id));
        return new Download(row, content);
    }

    /** The file, when this person may read it; otherwise "not found". */
    private ChatAttachments.Row readable(UUID orgId, Actor actor, UUID id) {
        ChatAttachments.Row row = attachments.find(orgId, id).orElseThrow(() -> ApiException.notFound("attachment", id));
        if (!row.isSent() || row.conversationId() == null) {
            if (!row.uploadedBy().equals(actor.humanId())) {
                throw ApiException.notFound("attachment", id);
            }
            return row;
        }
        try {
            access.requireForRead(orgId, actor, row.conversationId());
        } catch (ApiException hidden) {
            throw ApiException.notFound("attachment", id);
        }
        return row;
    }

    // ---- Removing ------------------------------------------------------------------------------

    /** Takes back a file attached but not sent. A sent file goes with its conversation. */
    // @find: delete attachment, remove file from chat
    public void delete(UUID orgId, Actor actor, UUID id) {
        ChatAttachments.Row row = attachments.find(orgId, id).orElseThrow(() -> ApiException.notFound("attachment", id));
        if (!row.uploadedBy().equals(actor.humanId())) {
            throw ApiException.notFound("attachment", id);
        }
        if (row.isSent()) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "This file was sent with a message. It is removed when the conversation is deleted.");
        }
        attachments.delete(orgId, id);
    }

    /** Files attached and never sent are let go after a day. */
    // @find: scheduled sweep, delete unsent attachments, clean up abandoned uploads, hourly job
    @Scheduled(fixedDelayString = "${aiwos.chat.attachment-sweep-interval:PT1H}", initialDelayString = "PT5M")
    public void sweepUnsent() {
        try {
            int removed = attachments.deleteUnsentBefore(Instant.now().minus(UNSENT_KEPT_FOR));
            if (removed > 0) {
                log.info("Removed {} chat attachment(s) that were never sent", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove unsent chat attachments: {}", e.getMessage());
        }
    }

    // ---- Sending -------------------------------------------------------------------------------

    /**
     * Checks the files a message is about to carry, before it is written: each must be this
     * person's, not sent yet, for this conversation (or for none yet), and readable. Inside the
     * transaction that writes the message, so a refusal leaves nothing behind.
     *
     * @return the files, in the order they were attached, for the message's detail
     */
    // @find: check attachments before send, validate attachment ids for message
    public List<ChatAttachments.Row> checkForSend(UUID orgId, Actor actor, UUID conversationId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
        if (distinct.size() > AttachmentTypes.MAX_PER_MESSAGE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A message can carry at most 10 files.");
        }
        String me = requirePerson(actor);
        List<ChatAttachments.Row> rows = attachments.findAll(orgId, distinct);
        if (rows.size() != distinct.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One of the attached files is no longer available. Attach it again.");
        }
        for (ChatAttachments.Row row : rows) {
            boolean usable = row.uploadedBy().equals(me)
                    && !row.isSent()
                    && (row.conversationId() == null || row.conversationId().equals(conversationId));
            if (!usable) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "One of the attached files is no longer available. Attach it again.");
            }
            if (!row.isReady()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, row.name() + " could not be read, so it cannot be sent. Remove it and try again.");
            }
        }
        return rows;
    }

    /** Ties the checked files to the message that carries them. */
    // @find: bind attachments to message, attach files to sent message
    public void bind(UUID orgId, UUID conversationId, UUID messageId, List<ChatAttachments.Row> rows) {
        if (!rows.isEmpty()) {
            attachments.bindToMessage(
                    orgId, conversationId, messageId, rows.stream().map(ChatAttachments.Row::id).toList());
        }
    }

    /** Gives a message's files to the work it started, so its runs read them. */
    // @find: link attachment to goal, attach file to run
    public void linkGoal(UUID orgId, UUID messageId, UUID goalId) {
        attachments.linkGoal(orgId, messageId, goalId);
    }

    /**
     * Work sent to a different agent takes its files along: those of the goal it replaces, or -
     * when the coordinator could not choose and nothing was started - those of the message the
     * person sent just above the choice.
     */
    // @find: follow reroute, move attachments to new goal after reroute
    public void followReroute(UUID orgId, UUID conversationId, UUID previousGoalId, int routingPosition, UUID goalId) {
        int moved = previousGoalId == null ? 0 : attachments.moveGoal(orgId, previousGoalId, goalId);
        if (moved == 0) {
            attachments.linkLatestUserMessage(orgId, conversationId, routingPosition, goalId);
        }
    }

    /** What a sent message records about each file, for its card in the thread. */
    static List<Map<String, Object>> detailOf(List<ChatAttachments.Row> rows) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (ChatAttachments.Row row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id().toString());
            item.put("name", row.name());
            item.put("mimeType", row.mediaType());
            item.put("size", row.size());
            item.put("kind", row.kind());
            item.put("pageCount", row.pageCount());
            detail.add(item);
        }
        return detail;
    }

    /**
     * What the coordinator and the planner read for a message with files: what the person wrote,
     * or a plain request to look at the files when they wrote nothing, and the files' names, so the
     * work is routed knowing what it is about.
     */
    static String requestWithNames(String text, List<ChatAttachments.Row> rows) {
        if (rows.isEmpty()) {
            return text;
        }
        String base = text == null || text.isBlank()
                ? (rows.size() == 1
                        ? "Look at the attached file and tell me what it contains."
                        : "Look at the attached files and tell me what they contain.")
                : text.strip();
        StringBuilder names = new StringBuilder();
        for (ChatAttachments.Row row : rows) {
            names.append(names.isEmpty() ? "" : ", ")
                    .append(row.name())
                    .append(" (")
                    .append(AttachmentTypes.describe(row.kind(), row.pageCount()))
                    .append(')');
        }
        return base + "\n\nAttached: " + names + ".";
    }

    // ---- Saving to the knowledge base -----------------------------------------------------------

    /**
     * Adds a sent or unsent file to the workspace's documents, as the person asking, into the
     * {@value #KNOWLEDGE_SOURCE_NAME} source. The knowledge service decides whether they may.
     */
    // @find: save attachment to knowledge base, add chat file to documents
    public Saved saveToKnowledge(UUID orgId, Actor actor, UUID id, String authorization) {
        Download file = download(orgId, actor, id);
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Sign in again to save files to knowledge.");
        }
        try {
            UUID sourceId = chatSource(orgId, authorization);
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            String name = file.row().name();
            body.part("file", new ByteArrayResource(file.content()) {
                        @Override
                        public String getFilename() {
                            return name;
                        }
                    })
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            body.part("mode", "keep_both");
            JsonNode result = knowledge.post()
                    .uri("/api/sources/" + sourceId + "/documents")
                    .header("Authorization", authorization)
                    .header("X-Workspace-Id", orgId.toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();
            UUID documentId = result == null || result.path("documentId").isMissingNode() || result.path("documentId").isNull()
                    ? null
                    : UUID.fromString(result.path("documentId").asText());
            if (documentId == null) {
                String detail = result == null ? null : result.path("detail").asText(null);
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        detail == null || detail.isBlank() ? "The knowledge base could not take this file." : detail);
            }
            attachments.markSaved(orgId, id, documentId);
            return new Saved(documentId, KNOWLEDGE_SOURCE_NAME);
        } catch (WebClientResponseException.Forbidden | WebClientResponseException.Unauthorized refused) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED,
                    "Your role cannot add documents to the knowledge base. Ask someone who manages knowledge.");
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not save attachment {} to knowledge in workspace {}: {}", id, orgId, e.getMessage());
            throw new ApiException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE, "The knowledge base cannot be reached right now. Try again in a minute.");
        }
    }

    private UUID chatSource(UUID orgId, String authorization) {
        JsonNode sources = knowledge.get()
                .uri("/api/sources")
                .header("Authorization", authorization)
                .header("X-Workspace-Id", orgId.toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        if (sources != null && sources.isArray()) {
            for (JsonNode source : sources) {
                boolean agentOwned = !source.path("agentId").isMissingNode() && !source.path("agentId").isNull();
                if (KNOWLEDGE_SOURCE_NAME.equals(source.path("name").asText()) && !agentOwned) {
                    return UUID.fromString(source.path("id").asText());
                }
            }
        }
        JsonNode created = knowledge.post()
                .uri("/api/sources")
                .header("Authorization", authorization)
                .header("X-Workspace-Id", orgId.toString())
                .bodyValue(Map.of("name", KNOWLEDGE_SOURCE_NAME))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        if (created == null || created.path("id").isMissingNode()) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "The knowledge base could not create a place for this file.");
        }
        return UUID.fromString(created.path("id").asText());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** The conversation, when this person may write to it; used by the upload endpoint with a path id. */
    Conversation requireConversation(UUID orgId, Actor actor, UUID conversationId) {
        return access.require(orgId, actor, conversationId);
    }

    private static String requirePerson(Actor actor) {
        String me = actor.humanId();
        if (me == null || me.isBlank()) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only a signed-in person can attach files.");
        }
        return me;
    }

    /** A file name safe to show and to send back in a header: no path, no control characters, bounded. */
    static String cleanName(String filename) {
        String name = filename == null ? "" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isBlank()) {
            name = "file";
        }
        return name.length() <= 200 ? name : name.substring(0, 200);
    }

    /** The knowledge service's reason, without its advice about indexing, which does not apply here. */
    private static String plainProblem(String problem) {
        if (problem == null || problem.isBlank()) {
            return "No readable text could be found in this file.";
        }
        return problem.replace(" before indexing it.", ".")
                .replace(" before it can be indexed.", ".")
                .replace(" before indexing.", ".")
                .replace("so its contents cannot be read", "so it cannot be read")
                .replace("be indexed", "be read");
    }
}
