package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One turn in a conversation.
 *
 * <p>Never updated once written, like a run step, and for the same reason: a record of what was
 * said is worth nothing if it can be rewritten afterwards. {@code detail} carries whatever shape
 * this kind of message needs - a routing receipt, cited passages, a schedule preview - because
 * that shape differs completely by kind.
 *
 * <p>Implements {@link Persistable} for the same reason {@code BaseEntity} does: the id is
 * assigned in Java, not by the database, so Spring Data cannot tell a new row from an existing one
 * by asking whether the id is null - it is never null. Without this, every save issues a SELECT
 * first to decide between insert and update; a busy conversation appends many of these in a row.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Transient
    private boolean isNew = true;

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
            UUID orgId,
            UUID conversationId,
            int position,
            String authorKind,
            UUID authorId,
            UUID agentId,
            String kind,
            String content,
            Map<String, Object> detail,
            UUID goalId) {
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

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
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
