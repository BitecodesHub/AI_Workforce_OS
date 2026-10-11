// @find: chat, conversations, messages, send message, create conversation, rename, pin, archive, delete conversation, participants, visibility, queue, reroute, stop goal, retry goal, ChatController, /api/conversations, Chat page
// @what: REST API for conversations and their messages: list, create, read, rename, pin, archive, share, send, queue, reroute, stop and retry.
// @flow: Called by the Chat page; delegates to ConversationQueries for reads, ConversationAdmin for changes, CoordinatorService for sending and goal actions, and ChatQueue for waiting messages.
package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
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

    /** One answer at a time per conversation; absent only where a test builds this by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatQueue chatQueue;

    /** Wires the repositories and services the endpoints delegate to. */
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

    /** Body for starting a conversation; the title is optional and may be left empty to be named later. */
    public record CreateConversationRequest(@Size(max = 200) String title) {}

    /**
     * The body of a send: the words, the optional agents to address, and everything attached.
     *
     * @param text what the person wrote; may be empty when files are attached or a skill is chosen
     * @param attachmentIds files uploaded for this message (see {@link AttachmentController}), at most 10
     * @param knowledge workspace sources or documents chosen to ground this message, at most 20
     * @param repo a GitHub repository (optional branch and path) the work is about
     * @param skillId a skill whose playbook the run follows
     * @param agentIds agents to address, added to any @mentions in the text (ids not in the workspace
     *     are ignored); when neither names an agent, the coordinator decides who takes the message
     */
    public record SendMessageRequest(
            @Size(max = 10_000) String text,
            List<UUID> agentIds,
            @Size(max = 10) List<UUID> attachmentIds,
            @Size(max = 20) List<MessageContext.KnowledgeRef> knowledge,
            MessageContext.RepoRef repo,
            UUID skillId) {

        /** The shape before files could be attached. */
        public SendMessageRequest(String text, List<UUID> agentIds) {
            this(text, agentIds, null);
        }

        /** The shape before the "+" menu could attach knowledge, a repository or a skill. */
        public SendMessageRequest(String text, List<UUID> agentIds, List<UUID> attachmentIds) {
            this(text, agentIds, attachmentIds, null, null, null);
        }

        /** What was attached besides files, checked. */
        public MessageContext context() {
            return MessageContext.of(knowledge, repo, skillId);
        }
    }

    /** Body for sending a routed message's work to a different agent. */
    public record RerouteRequest(@NotNull UUID agentId) {}

    /** Body for renaming a conversation. */
    public record RenameRequest(@NotBlank @Size(max = 200) String title) {}

    /** Body for marking a conversation read up to a message position. */
    public record ReadRequest(@Min(0) int position) {}

    /** Body for changing who can read a conversation ({@code private} or {@code workspace}). */
    public record VisibilityRequest(@NotBlank String visibility) {}

    /** Body for adding people (user ids, one to 50) to a conversation. */
    public record ParticipantsRequest(@NotNull @Size(min = 1, max = 50) List<String> userIds) {}

    /** The people added to a conversation, besides its creator. */
    public record ParticipantsResult(List<String> userIds) {}

    /** Body for answering a documents card; the agent is optional and the default agent is used when absent. */
    public record AnswerFromDocumentsRequest(UUID agentId) {}

    /**
     * The messages a send, reroute or similar action created, or the one message that was queued instead.
     *
     * @param queued the message as it waits its turn, when the conversation already had work in
     *     progress and nothing was added to the thread yet; null otherwise
     */
    public record MessagesResult(List<ConversationQueries.ChatMessageView> messages, ChatQueue.QueuedMessageView queued) {

        public MessagesResult(List<ConversationQueries.ChatMessageView> messages) {
            this(messages, null);
        }
    }

    /** Body for changing the text of a message waiting in the queue. */
    public record EditQueuedRequest(@Size(max = 10_000) String text) {}

    /**
     * Lists the conversations of this workspace for the caller: pinned, needing the caller, and the rest, with optional search and scope.
     *
     * <p>Route: GET /api/conversations. Permission: chat:use.
     * Response: a {@link ConversationQueries.ConversationPage}. Read-only; a validation error when {@code q} or {@code scope} is invalid.
     */
    // @find: list conversations, search chats, GET /api/conversations, chat sidebar
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

    /**
     * Creates an empty conversation owned by the caller, with the workspace's default visibility.
     *
     * <p>Route: POST /api/conversations. Permission: chat:use.
     * Response: 201 with the new {@link ConversationQueries.ConversationView}. Writes one conversation row.
     */
    // @find: create conversation, new chat, POST /api/conversations
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Start a conversation")
    public ConversationQueries.ConversationView create(@Valid @RequestBody CreateConversationRequest request) {
        Conversation conversation = new Conversation();
        conversation.setId(UuidV7.generate());
        conversation.setOrgId(orgId());
        conversation.setTitle(request.title() == null ? "" : request.title().strip());
        conversation.setVisibility(admin.defaultVisibility(orgId()));
        conversations.save(conversation);
        return queries.view(orgId(), RequestContext.requireActor(), conversation);
    }

    /**
     * A conversation, or only what changed in it. Without {@code after} and {@code since} this is
     * the recent window; with both it is the messages after that position and the goals, tasks and
     * questions changed since that moment, which is what an open thread polls for instead of
     * reading the whole window again (see {@link ConversationQueries#detail}).
     *
     * <p>Route: GET /api/conversations/{id}. Permission: chat:use. Response: a {@link ConversationQueries.ConversationDetail}.
     * Read-only; not found when the caller cannot read the conversation.
     *
     * @param after the position of the newest message the reader already holds
     * @param since the {@code generatedAt} of the response the reader last merged
     */
    // @find: open conversation, get conversation detail with messages, GET /api/conversations/{id}
    @GetMapping("/{id}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(
            summary =
                    "A conversation, its recent messages, the goals it started, and its open questions; or, with after and since, only what changed")
    public ConversationQueries.ConversationDetail get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "" + DEFAULT_DETAIL_LIMIT) int limit,
            @RequestParam(required = false) Integer after,
            @RequestParam(required = false) Instant since) {
        return queries.detail(
                orgId(), RequestContext.requireActor(), id, limit, after == null ? null : Math.max(after, -1), since);
    }

    /** The whole recent window of a conversation, for callers inside the package; not itself a route. */
    ConversationQueries.ConversationDetail get(UUID id, int limit) {
        return get(id, limit, null, null);
    }

    /**
     * Returns an older page of messages before the given position.
     *
     * <p>Route: GET /api/conversations/{id}/messages. Permission: chat:use.
     * Response: a {@link ConversationQueries.MessagesPage}. Read-only; not found when the caller cannot read the conversation.
     */
    // @find: earlier messages, load older messages, GET /api/conversations/{id}/messages
    @GetMapping("/{id}/messages")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "An older page of a conversation's messages")
    public ConversationQueries.MessagesPage earlierMessages(
            @PathVariable UUID id,
            @RequestParam int before,
            @RequestParam(defaultValue = "" + DEFAULT_MESSAGES_LIMIT) int limit) {
        return queries.messagesPage(orgId(), RequestContext.requireActor(), id, before, limit);
    }

    /**
     * Renames a conversation through {@link ConversationAdmin}.
     *
     * <p>Route: PATCH /api/conversations/{id}. Permission: chat:use.
     * Response: the updated view. Only its creator or a holder of workspace:update may rename it.
     */
    // @find: rename conversation, change chat title, PATCH /api/conversations/{id}
    @PatchMapping("/{id}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Rename a conversation")
    public ConversationQueries.ConversationView rename(
            @PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return admin.rename(orgId(), RequestContext.requireActor(), id, request.title());
    }

    /**
     * Makes a conversation private or shared with the whole workspace.
     *
     * <p>Route: PUT /api/conversations/{id}/visibility. Permission: chat:use.
     * Response: the updated view. Only its creator or a holder of chat:read_all may change it. Writes an audit entry when the visibility actually changes.
     */
    // @find: set conversation visibility, private or workspace chat, PUT /api/conversations/{id}/visibility
    @PutMapping("/{id}/visibility")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Share a conversation with the whole workspace, or make it private again")
    public ConversationQueries.ConversationView setVisibility(
            @PathVariable UUID id, @Valid @RequestBody VisibilityRequest request) {
        return admin.setVisibility(orgId(), RequestContext.requireActor(), id, request.visibility());
    }

    /**
     * Lists the people added to a conversation.
     *
     * <p>Route: GET /api/conversations/{id}/participants. Permission: chat:use.
     * Response: a {@link ParticipantsResult}. Anyone who can read the conversation may list them.
     */
    // @find: list conversation participants, who is in this chat, GET /api/conversations/{id}/participants
    @GetMapping("/{id}/participants")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "The people added to a conversation, besides the person who started it")
    public ParticipantsResult participants(@PathVariable UUID id) {
        return new ParticipantsResult(admin.participantIds(orgId(), RequestContext.requireActor(), id));
    }

    /**
     * Adds people so they can read a private conversation.
     *
     * <p>Route: POST /api/conversations/{id}/participants. Permission: chat:use.
     * Response: the full participant list after the change. Only the conversation's creator or a holder of chat:read_all may do this. Writes participant rows and an audit entry through {@link ConversationAdmin}.
     */
    // @find: add participants to conversation, share chat with people, POST /api/conversations/{id}/participants
    @PostMapping("/{id}/participants")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Add people to a conversation so they can read it")
    public ParticipantsResult addParticipants(
            @PathVariable UUID id, @Valid @RequestBody ParticipantsRequest request) {
        return new ParticipantsResult(
                admin.addParticipants(orgId(), RequestContext.requireActor(), id, request.userIds()));
    }

    /**
     * Removes one person from a conversation.
     *
     * <p>Route: DELETE /api/conversations/{id}/participants/{userId}. Permission: chat:use.
     * Response: 204 with no body. Only the conversation's creator or a holder of chat:read_all may do this; deletes the participant row.
     */
    // @find: remove participant from conversation, DELETE /api/conversations/{id}/participants/{userId}
    @DeleteMapping("/{id}/participants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Take a person off a conversation")
    public void removeParticipant(@PathVariable UUID id, @PathVariable String userId) {
        admin.removeParticipant(orgId(), RequestContext.requireActor(), id, userId);
    }

    /**
     * Pins a conversation in the caller's own list only; other people are unaffected.
     *
     * <p>Route: PUT /api/conversations/{id}/pin. Permission: chat:use.
     * Response: 204 with no body. Writes the caller's conversation mark; refused to a caller with no person behind them.
     */
    // @find: pin conversation, PUT /api/conversations/{id}/pin
    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Pin a conversation to the top of this person's own list")
    public void pin(@PathVariable UUID id) {
        admin.pin(orgId(), RequestContext.requireActor(), id);
    }

    /**
     * Removes the caller's pin.
     *
     * <p>Route: DELETE /api/conversations/{id}/pin. Permission: chat:use.
     * Response: 204 with no body. Writes the caller's conversation mark.
     */
    // @find: unpin conversation, DELETE /api/conversations/{id}/pin
    @DeleteMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Unpin a conversation")
    public void unpin(@PathVariable UUID id) {
        admin.unpin(orgId(), RequestContext.requireActor(), id);
    }

    /**
     * Archives a conversation out of the caller's own list only.
     *
     * <p>Route: PUT /api/conversations/{id}/archive. Permission: chat:use.
     * Response: 204 with no body. Writes the caller's conversation mark.
     */
    // @find: archive conversation, PUT /api/conversations/{id}/archive
    @PutMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Archive a conversation out of this person's own list")
    public void archive(@PathVariable UUID id) {
        admin.archive(orgId(), RequestContext.requireActor(), id);
    }

    /**
     * Returns an archived conversation to the caller's list.
     *
     * <p>Route: DELETE /api/conversations/{id}/archive. Permission: chat:use.
     * Response: 204 with no body. Writes the caller's conversation mark.
     */
    // @find: unarchive conversation, DELETE /api/conversations/{id}/archive
    @DeleteMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Bring an archived conversation back")
    public void unarchive(@PathVariable UUID id) {
        admin.unarchive(orgId(), RequestContext.requireActor(), id);
    }

    /**
     * Records how far the caller has read, which drives the unread marker.
     *
     * <p>Route: PUT /api/conversations/{id}/read. Permission: chat:use.
     * Response: 204 with no body. Writes the caller's conversation mark; a caller with no person behind them is ignored without error.
     */
    // @find: mark conversation read, unread marker, PUT /api/conversations/{id}/read
    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Mark a conversation read up to a position, for this person only")
    public void markRead(@PathVariable UUID id, @Valid @RequestBody ReadRequest request) {
        admin.markRead(orgId(), RequestContext.requireActor(), id, request.position());
    }

    /**
     * Deletes a conversation after stopping its active work.
     *
     * <p>Route: DELETE /api/conversations/{id}. Permission: chat:use.
     * Response: 204 with no body. Only its creator or a holder of workspace:update may delete it; refused with a conflict while somebody else's work is running unless the caller holds task:cancel. Cancels active goals, detaches goals and questions, and writes an audit entry after commit, all in {@link ConversationAdmin}.
     */
    // @find: delete conversation, remove chat, DELETE /api/conversations/{id}
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Delete a conversation, stopping its active work first")
    public void delete(@PathVariable UUID id) {
        admin.delete(orgId(), RequestContext.requireActor(), id);
    }

    /**
     * Sends a message into a conversation. The coordinator records it, routes it, and creates its reply before this returns; if the conversation already has work in progress the message is queued instead.
     *
     * <p>Route: POST /api/conversations/{id}/messages. Permission: chat:use.
     * Response: 202 with the created messages, or with the queued message and no messages when it had to wait.
     * Validation error when the text is blank and there are neither files nor a skill. May start a goal, call the model, and write messages.
     */
    // @find: send message, post chat message, ask the workforce, POST /api/conversations/{id}/messages
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a message; the coordinator's reply is created before this returns")
    public MessagesResult send(
            @PathVariable UUID id, @Valid @RequestBody SendMessageRequest request, HttpServletRequest httpRequest) {
        List<UUID> attachmentIds = request.attachmentIds() == null ? List.of() : request.attachmentIds();
        MessageContext context = request.context();
        if ((request.text() == null || request.text().isBlank()) && attachmentIds.isEmpty() && context.skillId() == null) {
            throw os.aiworkforce.platform.error.ApiException.validation("text", "must not be blank");
        }
        CoordinatorService.SendOutcome outcome = coordinator.send(
                orgId(),
                id,
                request.text(),
                request.agentIds(),
                attachmentIds,
                context,
                httpRequest.getHeader("Authorization"));
        if (outcome.queued() != null && chatQueue != null) {
            return new MessagesResult(
                    List.of(),
                    chatQueue.view(
                            outcome.queued(),
                            conversations.findByIdAndOrgId(id, orgId()).orElse(null),
                            RequestContext.requireActor()));
        }
        return toResult(outcome.messages());
    }

    /**
     * Changes the text of a message waiting its turn.
     *
     * <p>Route: PATCH /api/conversations/{id}/queue/{queuedId}. Permission: chat:use.
     * Response: the updated queued message. Not found when the queue is not wired.
     */
    // @find: edit queued message, change waiting message, PATCH /api/conversations/{id}/queue/{queuedId}
    @PatchMapping("/{id}/queue/{queuedId}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Change the text of a message waiting its turn")
    public ChatQueue.QueuedMessageView editQueued(
            @PathVariable UUID id, @PathVariable UUID queuedId, @Valid @RequestBody EditQueuedRequest request) {
        return requireQueue().edit(orgId(), id, queuedId, request.text(), RequestContext.requireActor());
    }

    /**
     * Cancels a message waiting its turn.
     *
     * <p>Route: DELETE /api/conversations/{id}/queue/{queuedId}. Permission: chat:use.
     * Response: 204 with no body.
     */
    // @find: cancel queued message, remove waiting message, DELETE /api/conversations/{id}/queue/{queuedId}
    @DeleteMapping("/{id}/queue/{queuedId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Cancel a message waiting its turn")
    public void cancelQueued(@PathVariable UUID id, @PathVariable UUID queuedId) {
        requireQueue().cancel(orgId(), id, queuedId, RequestContext.requireActor());
    }

    /**
     * Stops the work in progress and starts the waiting message immediately, through {@link CoordinatorService}.
     *
     * <p>Route: POST /api/conversations/{id}/queue/{queuedId}/start-now. Permission: chat:use.
     * Response: 202 with the messages created. Stops running work as a side effect.
     */
    // @find: start queued message now, Start now anyway, POST /api/conversations/{id}/queue/{queuedId}/start-now
    @PostMapping("/{id}/queue/{queuedId}/start-now")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Stop the work in progress and start this waiting message now")
    public MessagesResult startQueuedNow(@PathVariable UUID id, @PathVariable UUID queuedId) {
        return toResult(coordinator.startQueuedNow(orgId(), id, queuedId).messages());
    }

    /** The queue, or a not-found error when it is not wired. */
    private ChatQueue requireQueue() {
        if (chatQueue == null) {
            throw os.aiworkforce.platform.error.ApiException.notFound("queue", "chat");
        }
        return chatQueue;
    }

    /**
     * Sends a routing message's work to a different agent instead.
     *
     * <p>Route: POST /api/conversations/{id}/messages/{messageId}/reroute. Permission: chat:use.
     * Response: the messages created. Starts a goal through {@link CoordinatorService}.
     */
    // @find: reroute message to another agent, choose agent, POST /api/conversations/{id}/messages/{messageId}/reroute
    @PostMapping("/{id}/messages/{messageId}/reroute")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a routing message's work to a different agent instead")
    public MessagesResult reroute(
            @PathVariable UUID id, @PathVariable UUID messageId, @Valid @RequestBody RerouteRequest request) {
        return toResult(coordinator.reroute(orgId(), id, messageId, request.agentId()));
    }

    /**
     * Asks an agent to answer from the passages on a documents card and nothing else.
     *
     * <p>Route: POST /api/conversations/{id}/messages/{messageId}/answer-from-documents. Permission: chat:use and task:create (both required).
     * Response: 202 with the messages created. Starts a goal through {@link CoordinatorService}.
     */
    // @find: answer from documents, Answer from these passages, POST /api/conversations/{id}/messages/{messageId}/answer-from-documents
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

    /**
     * Stops the work a chat message started.
     *
     * <p>Route: POST /api/conversations/{id}/goals/{goalId}/stop. Permission: chat:use.
     * Response: a {@link CoordinatorService.GoalActionResult}. Cancels the goal's active runs.
     */
    // @find: stop goal from chat, cancel running work, POST /api/conversations/{id}/goals/{goalId}/stop
    @PostMapping("/{id}/goals/{goalId}/stop")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Stop the work a chat message started")
    public CoordinatorService.GoalActionResult stopGoal(@PathVariable UUID id, @PathVariable UUID goalId) {
        return coordinator.stopGoal(orgId(), id, goalId);
    }

    /**
     * Retries a goal from the step that did not finish.
     *
     * <p>Route: POST /api/conversations/{id}/goals/{goalId}/retry. Permission: chat:use and task:create (both required).
     * Response: a {@link CoordinatorService.GoalActionResult}. Starts new runs.
     */
    // @find: retry goal from chat, run again, POST /api/conversations/{id}/goals/{goalId}/retry
    @PostMapping("/{id}/goals/{goalId}/retry")
    @RequiresPermission(
            value = {Permission.Codes.CHAT_USE, Permission.Codes.TASK_CREATE},
            mode = RequiresPermission.Mode.ALL)
    @Operation(summary = "Try a goal a chat message started again, from the step that did not finish")
    public CoordinatorService.GoalActionResult retryGoal(@PathVariable UUID id, @PathVariable UUID goalId) {
        return coordinator.retryGoal(orgId(), id, goalId);
    }

    /** Wraps created messages as a send response with nothing queued. */
    private static MessagesResult toResult(List<ChatMessage> created) {
        return new MessagesResult(created.stream().map(ChatController::toView).toList());
    }

    /** Copies a stored message into its view record. */
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

    /** The caller's workspace id from the request context. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
