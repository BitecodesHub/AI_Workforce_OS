package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One turn in a conversation.
 *
 * <p>Never updated once written, like a run step, and for the same reason: a record of what was
 * said is worth nothing if it can be rewritten afterwards. {@code detail} carries whatever shape
 * this kind of message needs - a routing receipt, cited passages, a schedule preview - because
 * that shape differs completely by kind.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private int position;

    @Column(name = "author_kind", nullable = false)
    private String authorKind;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false, columnDefinition = "text")
    private String content = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> detail = Map.of();

    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public static ChatMessage of(
            UUID orgId, UUID conversationId, int position, String authorKind, UUID authorId, UUID agentId,
            String kind, String content, Map<String, Object> detail, UUID goalId) {
        ChatMessage message = new ChatMessage();
        message.orgId = orgId;
        message.conversationId = conversationId;
        message.position = position;
        message.authorKind = authorKind;
        message.authorId = authorId;
        message.agentId = agentId;
        message.kind = kind;
        message.content = content == null ? "" : content;
        message.detail = detail == null ? Map.of() : detail;
        message.goalId = goalId;
        return message;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public int getPosition() {
        return position;
    }

    public String getAuthorKind() {
        return authorKind;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
