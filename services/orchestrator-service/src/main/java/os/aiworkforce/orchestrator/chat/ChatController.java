package os.aiworkforce.orchestrator.chat;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Conversations between a person and the workforce, and the messages inside them.
 *
 * <p>Reading and listing are open to the whole workspace - this is a shared surface, not a private
 * inbox - personalised only by what {@link ConversationQueries} adds on top: pins, archives, read
 * markers and what needs this particular person. Renaming, pinning, archiving, marking read and
 * deleting go through {@link ConversationAdmin}; deciding what happens to a message, rerouting it,
 * stopping or retrying its goal, and answering from a document's passages all go through {@link
 * CoordinatorService}, which is the only place that starts or stops work from chat.
 */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Chat")
public class ChatController {

    private static final int DEFAULT_DETAIL_LIMIT = 200;
    private static final int DEFAULT_MESSAGES_LIMIT = 100;

    private final Conversations conversations;
    private final ConversationQueries queries;
    private final ConversationAdmin admin;
    private final CoordinatorService coordinator;

    public ChatController(
            Conversations conversations,
            ConversationQueries queries,
            ConversationAdmin admin,
            CoordinatorService coordinator) {
        this.conversations = conversations;
        this.queries = queries;
        this.admin = admin;
        this.coordinator = coordinator;
    }

    public record CreateConversationRequest(@Size(max = 200) String title) {}

    public record SendMessageRequest(@NotBlank @Size(max = 10_000) String text, List<UUID> agentIds) {}

    public record RerouteRequest(@NotNull UUID agentId) {}

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {}

    public record ReadRequest(@Min(0) int position) {}

    public record AnswerFromDocumentsRequest(UUID agentId) {}

    public record MessagesResult(List<ConversationQueries.ChatMessageView> messages) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(
            summary = "Conversations in this workspace: pinned, needing you, and the rest, most recently active first")
    public ConversationQueries.ConversationPage list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queries.list(orgId(), RequestContext.requireActor(), q, scope, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Start a conversation")
    public ConversationQueries.ConversationView create(@Valid @RequestBody CreateConversationRequest request) {
        Conversation conversation = new Conversation();
        conversation.setId(UuidV7.generate());
        conversation.setOrgId(orgId());
        conversation.setTitle(request.title() == null ? "" : request.title().strip());
        conversations.save(conversation);
        return queries.view(orgId(), RequestContext.requireActor(), conversation);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "A conversation, its recent messages, the goals it started, and its open questions")
    public ConversationQueries.ConversationDetail get(
            @PathVariable UUID id, @RequestParam(defaultValue = "" + DEFAULT_DETAIL_LIMIT) int limit) {
        return queries.detail(orgId(), RequestContext.requireActor(), id, limit);
    }

    @GetMapping("/{id}/messages")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "An older page of a conversation's messages")
    public ConversationQueries.MessagesPage earlierMessages(
            @PathVariable UUID id,
            @RequestParam int before,
            @RequestParam(defaultValue = "" + DEFAULT_MESSAGES_LIMIT) int limit) {
        return queries.messagesPage(orgId(), id, before, limit);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Rename a conversation")
    public ConversationQueries.ConversationView rename(
            @PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return admin.rename(orgId(), RequestContext.requireActor(), id, request.title());
    }

    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Pin a conversation to the top of this person's own list")
    public void pin(@PathVariable UUID id) {
        admin.pin(orgId(), RequestContext.requireActor(), id);
    }

    @DeleteMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Unpin a conversation")
    public void unpin(@PathVariable UUID id) {
        admin.unpin(orgId(), RequestContext.requireActor(), id);
    }

    @PutMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Archive a conversation out of this person's own list")
    public void archive(@PathVariable UUID id) {
        admin.archive(orgId(), RequestContext.requireActor(), id);
    }

    @DeleteMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Bring an archived conversation back")
    public void unarchive(@PathVariable UUID id) {
        admin.unarchive(orgId(), RequestContext.requireActor(), id);
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Mark a conversation read up to a position, for this person only")
    public void markRead(@PathVariable UUID id, @Valid @RequestBody ReadRequest request) {
        admin.markRead(orgId(), RequestContext.requireActor(), id, request.position());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Delete a conversation, stopping its active work first")
    public void delete(@PathVariable UUID id) {
        admin.delete(orgId(), RequestContext.requireActor(), id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a message; the coordinator's reply is created before this returns")
    public MessagesResult send(
            @PathVariable UUID id, @Valid @RequestBody SendMessageRequest request, HttpServletRequest httpRequest) {
        List<ChatMessage> created = coordinator.handleMessage(
                orgId(), id, request.text(), request.agentIds(), httpRequest.getHeader("Authorization"));
        return toResult(created);
    }

    @PostMapping("/{id}/messages/{messageId}/reroute")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a routing message's work to a different agent instead")
    public MessagesResult reroute(
            @PathVariable UUID id, @PathVariable UUID messageId, @Valid @RequestBody RerouteRequest request) {
        return toResult(coordinator.reroute(orgId(), id, messageId, request.agentId()));
    }

    @PostMapping("/{id}/messages/{messageId}/answer-from-documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(
            value = {Permission.Codes.CHAT_USE, Permission.Codes.TASK_CREATE},
            mode = RequiresPermission.Mode.ALL)
    @Operation(summary = "Ask an agent to answer a documents card's passages, and nothing else")
    public MessagesResult answerFromDocuments(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @RequestBody(required = false) AnswerFromDocumentsRequest request) {
        UUID agentId = request == null ? null : request.agentId();
        return toResult(coordinator.answerFromDocuments(orgId(), id, messageId, agentId));
    }

    @PostMapping("/{id}/goals/{goalId}/stop")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Stop the work a chat message started")
    public CoordinatorService.GoalActionResult stopGoal(@PathVariable UUID id, @PathVariable UUID goalId) {
        return coordinator.stopGoal(orgId(), id, goalId);
    }

    @PostMapping("/{id}/goals/{goalId}/retry")
    @RequiresPermission(
            value = {Permission.Codes.CHAT_USE, Permission.Codes.TASK_CREATE},
            mode = RequiresPermission.Mode.ALL)
    @Operation(summary = "Try a goal a chat message started again, from the step that did not finish")
    public CoordinatorService.GoalActionResult retryGoal(@PathVariable UUID id, @PathVariable UUID goalId) {
        return coordinator.retryGoal(orgId(), id, goalId);
    }

    private static MessagesResult toResult(List<ChatMessage> created) {
        return new MessagesResult(created.stream().map(ChatController::toView).toList());
    }

    private static ConversationQueries.ChatMessageView toView(ChatMessage message) {
        return new ConversationQueries.ChatMessageView(
                message.getId(),
                message.getPosition(),
                message.getAuthorKind(),
                message.getAuthorId(),
                message.getAgentId(),
                message.getKind(),
                message.getContent(),
                message.getDetail(),
                message.getGoalId(),
                message.getCreatedAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
