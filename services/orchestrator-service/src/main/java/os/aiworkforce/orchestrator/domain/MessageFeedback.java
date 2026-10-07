package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One person's rating of one agent answer: a thumbs up or a thumbs down, and optionally why.
 *
 * <p>At most one per person per answer (a unique index on workspace, message and person), and a
 * second vote replaces the first. In practice every write goes through {@code MessageFeedbacks}'s
 * native upsert, so two clicks at once can never break that index; the row is loaded by ordinary
 * finders.
 *
 * <p>{@code agentId} and {@code runId} are copied from the answer message by the server when the
 * rating is written. A message never changes, so the copy cannot drift, and the per-agent
 * satisfaction figure and the ratings on a run's trace read them directly.
 *
 * <p>Implements {@link Persistable} for the same reason {@link ChatMessage} does: the id is
 * assigned in Java, so Spring Data cannot use nullness to tell an insert from an update.
 */
@Entity
@Table(name = "chat_message_feedback")
public class MessageFeedback implements Persistable<UUID> {

    /** The rating for a thumbs up. */
    public static final short POSITIVE = 1;

    /** The rating for a thumbs down. */
    public static final short NEGATIVE = -1;

    /** The longest reason kept, matching the column's CHECK. */
    public static final int REASON_MAX = 500;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Transient
    private boolean isNew = true;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(nullable = false)
    private short rating;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public short getRating() {
        return rating;
    }

    public void setRating(short rating) {
        this.rating = rating;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
