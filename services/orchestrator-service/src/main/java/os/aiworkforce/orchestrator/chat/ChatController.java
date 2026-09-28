package os.aiworkforce.orchestrator.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.web.GoalController;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Conversations between a person and the workforce, and the messages inside them.
 *
 * <p>Reading and listing are open to the whole workspace - this is a shared surface, not a private
 * inbox - but only the person who started a conversation can delete it. Sending a message answers
 * quickly: the routing decision and the first replies it produces are made before this returns,
 * and the goal itself runs afterwards, on its own.
 */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Chat")
public class ChatController {

    private static final int LIST_LIMIT = 50;

    private final Conversations conversations;
    private final ChatMessages messages;
    private final Goals goals;
    private final Tasks tasks;
    private final Runs runs;
    private final CoordinatorService coordinator;

    public ChatController(
            Conversations conversations, ChatMessages messages, Goals goals, Tasks tasks, Runs runs,
            CoordinatorService coordinator) {
        this.conversations = conversations;
        this.messages = messages;
        this.goals = goals;
        this.tasks = tasks;
        this.runs = runs;
        this.coordinator = coordinator;
    }

    public record ConversationView(
            UUID id, String title, UUID createdBy, Instant createdAt, Instant updatedAt, String lastMessagePreview) {}

    public record ChatMessageView(
            UUID id, int position, String authorKind, UUID authorId, UUID agentId, String kind,
            String content, Map<String, Object> detail, UUID goalId, Instant createdAt) {}

    public record ConversationDetail(
            ConversationView conversation, List<ChatMessageView> messages, List<GoalController.GoalView> goals) {}

    public record CreateConversationRequest(@Size(max = 200) String title) {}

    public record SendMessageRequest(@NotBlank @Size(max = 10_000) String text, List<UUID> agentIds) {}

    public record RerouteRequest(@NotNull UUID agentId) {}

    public record MessagesResult(List<ChatMessageView> messages) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Conversations in this workspace, most recently active first")
    public List<ConversationView> list() {
        return conversations.findByOrgIdOrderByUpdatedAtDesc(orgId(), PageRequest.of(0, LIST_LIMIT)).stream()
                .map(ChatController::toView)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Start a conversation")
    public ConversationView create(@Valid @RequestBody CreateConversationRequest request) {
        Conversation conversation = new Conversation();
        conversation.setId(UuidV7.generate());
        conversation.setOrgId(orgId());
        conversation.setTitle(request.title() == null ? "" : request.title().strip());
        conversations.save(conversation);
        return toView(conversation);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "A conversation, its messages, and the goals it started")
    public ConversationDetail get(@PathVariable UUID id) {
        UUID orgId = orgId();
        Conversation conversation = conversations.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", id));
        List<ChatMessage> messageRows = messages.findByConversationIdOrderByPosition(id);
        List<ChatMessageView> messageViews = messageRows.stream().map(ChatController::toView).toList();

        List<UUID> goalIds = messageRows.stream()
                .map(ChatMessage::getGoalId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // Every goal id here came from this conversation's own messages, which were themselves
        // written only for goals created in this org - but the lookup itself does not carry an
        // org filter, so one is applied to the result rather than trusted from the caller's side.
        List<GoalController.GoalView> goalViews = goals.findAllById(goalIds).stream()
                .filter(goal -> orgId.equals(goal.getOrgId()))
                .sorted(Comparator.comparing(Goal::getCreatedAt))
                .map(this::toGoalView)
                .toList();

        return new ConversationDetail(toView(conversation), messageViews, goalViews);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a message; the coordinator's reply is created before this returns")
    public MessagesResult send(
            @PathVariable UUID id, @Valid @RequestBody SendMessageRequest request, HttpServletRequest httpRequest) {
        List<ChatMessage> created = coordinator.handleMessage(
                orgId(), id, request.text(), request.agentIds(), httpRequest.getHeader("Authorization"));
        return new MessagesResult(created.stream().map(ChatController::toView).toList());
    }

    @PostMapping("/{id}/messages/{messageId}/reroute")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Send a routing message's work to a different agent instead")
    public MessagesResult reroute(
            @PathVariable UUID id, @PathVariable UUID messageId, @Valid @RequestBody RerouteRequest request) {
        List<ChatMessage> created = coordinator.reroute(orgId(), id, messageId, request.agentId());
        return new MessagesResult(created.stream().map(ChatController::toView).toList());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Delete a conversation; only the person who started it may")
    public void delete(@PathVariable UUID id) {
        UUID orgId = orgId();
        Conversation conversation = conversations.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", id));
        Actor actor = RequestContext.requireActor();
        if (!actor.id().equals(conversation.getCreatedBy())) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "Only the person who started this conversation can delete it.");
        }
        conversations.delete(conversation);
    }

    private GoalController.GoalView toGoalView(Goal goal) {
        List<GoalController.TaskView> taskViews = tasks.findByGoalIdOrderByPosition(goal.getId()).stream()
                .map(this::toTaskView)
                .toList();
        return new GoalController.GoalView(
                goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(),
                goal.getRequestedBy(), goal.getSource(), goal.getConversationId(), goal.getScheduleId(),
                goal.getCreatedAt(), goal.getCompletedAt(), taskViews);
    }

    private GoalController.TaskView toTaskView(Task task) {
        UUID runId = runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId()).map(Run::getId).orElse(null);
        return new GoalController.TaskView(
                task.getId(), task.getAgentId(), task.getTitle(), task.getStatus(), task.getPosition(),
                task.getDependsOn(), task.getAttempt(), task.getMaxAttempts(), task.getResult(),
                task.getFailureReason(), task.getStartedAt(), task.getCompletedAt(), runId);
    }

    private static ConversationView toView(Conversation conversation) {
        return new ConversationView(
                conversation.getId(), conversation.getTitle(), parseUuidOrNull(conversation.getCreatedBy()),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), conversation.getLastMessagePreview());
    }

    private static ChatMessageView toView(ChatMessage message) {
        return new ChatMessageView(
                message.getId(), message.getPosition(), message.getAuthorKind(), message.getAuthorId(),
                message.getAgentId(), message.getKind(), message.getContent(), message.getDetail(),
                message.getGoalId(), message.getCreatedAt());
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
