package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.MessageFeedback;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.MessageFeedbacks;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Thumbs up and thumbs down on an agent's answer, with an optional reason.
 *
 * <p>The only quality signal the product collects, so it is kept deliberately plain: one vote per
 * person per answer, a second vote replaces the first, and withdrawing it deletes the row. Only an
 * agent's own answer can be rated, in a conversation of this workspace, by someone who can use
 * chat - the same checks the other endpoints under a message make. The agent and the run an answer
 * came from are read from the stored message and never taken from the client, so a rating cannot
 * be pointed at an agent or a run it was not given about.
 *
 * <p>A rating is audited when it commits. The orchestrator's audit client sends the outcome the
 * audit store accepts, {@code succeeded}, and names the verdict, {@code positive} or {@code
 * negative}, in the entry's own detail: the store rejects any other outcome, and an entry it
 * rejected would be missing from the activity log with nothing to say so.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Feedback")
public class FeedbackController {

    /** The audit action for a rating. */
    public static final String RATED = "chat.answer.rated";

    /** What the audit store accepts for an event that happened; the verdict itself is in the detail. */
    static final String AUDIT_OUTCOME = "succeeded";

    private final Conversations conversations;
    private final ChatMessages messages;
    private final MessageFeedbacks feedbacks;
    private final Runs runs;
    private final AuditClient audit;

    public FeedbackController(
            Conversations conversations,
            ChatMessages messages,
            MessageFeedbacks feedbacks,
            Runs runs,
            AuditClient audit) {
        this.conversations = conversations;
        this.messages = messages;
        this.feedbacks = feedbacks;
        this.runs = runs;
        this.audit = audit;
    }

    /**
     * @param rating {@code 1} for a thumbs up, {@code -1} for a thumbs down
     * @param reason why, in a few words or a sentence; optional
     */
    public record RateRequest(
            @NotNull Integer rating, @Size(max = MessageFeedback.REASON_MAX) String reason) {}

    /** One person's vote on one answer. */
    public record FeedbackView(UUID messageId, int rating, String reason, Instant updatedAt) {}

    /** What the caller has rated in a conversation. */
    public record ConversationFeedback(List<FeedbackView> ratings) {}

    /**
     * One rating on a run's answer, as the run's trace shows it.
     *
     * @param userId who rated, so the trace can name them where the viewer may see names
     */
    public record RunRating(UUID messageId, UUID userId, int rating, String reason, Instant updatedAt) {}

    /** Every rating the answers of one run have. */
    public record RunFeedback(List<RunRating> ratings) {}

    @PostMapping("/conversations/{id}/messages/{messageId}/feedback")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Transactional
    @Operation(summary = "Rate an agent's answer, or change the rating already given")
    public FeedbackView rate(
            @PathVariable UUID id, @PathVariable UUID messageId, @Valid @RequestBody RateRequest request) {
        UUID orgId = orgId();
        short rating = ratingOf(request.rating());
        String reason = reasonOf(request.reason());
        Actor actor = RequestContext.requireActor();
        UUID userId = personOf(actor);
        ChatMessage answer = answerIn(orgId, id, messageId);

        UUID agentId = agentOf(answer);
        UUID runId = runOf(orgId, answer);
        feedbacks.upsert(UuidV7.generate(), orgId, id, messageId, userId, agentId, runId, rating, reason);
        MessageFeedback saved = feedbacks.findByOrgIdAndMessageIdAndUserId(orgId, messageId, userId)
                .orElseThrow(() -> ApiException.notFound("feedback", messageId));

        Map<String, Object> detail = new LinkedHashMap<>();
        String verdict = rating == MessageFeedback.POSITIVE ? "positive" : "negative";
        detail.put("outcome", verdict);
        detail.put("conversationId", id.toString());
        if (agentId != null) {
            detail.put("agentId", agentId.toString());
        }
        if (runId != null) {
            detail.put("runId", runId.toString());
        }
        detail.put("hasReason", reason != null);
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, RATED, "chat_message", messageId.toString(), AUDIT_OUTCOME, detail));

        return view(saved);
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Transactional
    @Operation(summary = "Withdraw the caller's rating of an agent's answer")
    public void withdraw(@PathVariable UUID id, @PathVariable UUID messageId) {
        UUID orgId = orgId();
        UUID userId = personOf(RequestContext.requireActor());
        answerIn(orgId, id, messageId);
        // Nothing to withdraw is not a failure: the person's aim, no vote of theirs on this
        // answer, holds either way, and a double click should not show an error.
        feedbacks.withdraw(orgId, messageId, userId);
    }

    @GetMapping("/conversations/{id}/feedback")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Transactional(readOnly = true)
    @Operation(summary = "The ratings the caller has given in one conversation")
    public ConversationFeedback inConversation(@PathVariable UUID id) {
        UUID orgId = orgId();
        visibleConversation(orgId, id);
        UUID userId = personOf(RequestContext.requireActor());
        return new ConversationFeedback(feedbacks.findByOrgIdAndConversationIdAndUserId(orgId, id, userId).stream()
                .map(FeedbackController::view)
                .toList());
    }

    @GetMapping("/runs/{runId}/feedback")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Transactional(readOnly = true)
    @Operation(summary = "The ratings the answers of one run have, with their reasons")
    public RunFeedback onRun(@PathVariable UUID runId) {
        UUID orgId = orgId();
        os.aiworkforce.orchestrator.domain.Run run =
                runs.findByIdAndOrgId(runId, orgId).orElseThrow(() -> ApiException.notFound("run", runId));
        if (access != null && run.getTaskId() != null
                && access.hiddenTaskIds(orgId, RequestContext.requireActor()).contains(run.getTaskId())) {
            throw ApiException.notFound("run", runId);
        }
        return new RunFeedback(feedbacks.findByOrgIdAndRunIdOrderByUpdatedAtDesc(orgId, runId).stream()
                .map(f -> new RunRating(f.getMessageId(), f.getUserId(), f.getRating(), f.getReason(), f.getUpdatedAt()))
                .toList());
    }

    /** Who may read which conversation; absent only where a test builds this by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationAccess access;

    private void visibleConversation(UUID orgId, UUID id) {
        if (access != null) {
            access.require(orgId, RequestContext.requireActor(), id);
        } else {
            conversations.findByIdAndOrgId(id, orgId).orElseThrow(() -> ApiException.notFound("conversation", id));
        }
    }

    // ---- Checks ----------------------------------------------------------------------------

    /**
     * The message, once it is known to be an agent's answer in a conversation of this workspace.
     * A conversation or message of another workspace is not found rather than refused, so its
     * existence is not confirmed.
     */
    private ChatMessage answerIn(UUID orgId, UUID conversationId, UUID messageId) {
        visibleConversation(orgId, conversationId);
        ChatMessage message = messages.findByIdAndConversationId(messageId, conversationId)
                .filter(found -> orgId.equals(found.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("message", messageId));
        if (!"answer".equals(message.getKind()) || !"agent".equals(message.getAuthorKind())) {
            throw ApiException.validation("messageId", "Only an agent's answer can be rated.");
        }
        return message;
    }

    private static short ratingOf(Integer value) {
        if (value == null || (value != MessageFeedback.POSITIVE && value != MessageFeedback.NEGATIVE)) {
            throw ApiException.validation("rating", "Rate with 1 for a thumbs up or -1 for a thumbs down.");
        }
        return value.shortValue();
    }

    /** The reason trimmed, or null when there is none: an empty string is no reason. */
    private static String reasonOf(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MessageFeedback.REASON_MAX) {
            throw ApiException.validation(
                    "reason", "Keep the reason to " + MessageFeedback.REASON_MAX + " characters or fewer.");
        }
        return trimmed;
    }

    /** The person behind the request: a rating is a person's opinion, so nobody else may give one. */
    private static UUID personOf(Actor actor) {
        String human = actor.humanId();
        if (human == null || actor.kind() != Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only a signed-in person can rate an answer.");
        }
        try {
            return UUID.fromString(human);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only a signed-in person can rate an answer.");
        }
    }

    /** The agent that wrote the answer: the message's own column, else the one its detail names. */
    private static UUID agentOf(ChatMessage answer) {
        if (answer.getAgentId() != null) {
            return answer.getAgentId();
        }
        return uuidIn(answer.getDetail().get("agentId"));
    }

    /** The run behind the answer, when the message names one that is of this workspace. */
    private UUID runOf(UUID orgId, ChatMessage answer) {
        UUID runId = uuidIn(answer.getDetail().get("runId"));
        if (runId == null) {
            return null;
        }
        return runs.findByIdAndOrgId(runId, orgId).map(run -> run.getId()).orElse(null);
    }

    private static UUID uuidIn(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static FeedbackView view(MessageFeedback feedback) {
        return new FeedbackView(
                feedback.getMessageId(), feedback.getRating(), feedback.getReason(), feedback.getUpdatedAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
