// @find: queued message, chat queue, message waiting, send while busy, edit queued message, cancel queued message, expire queued, chat_queued_messages, ChatQueuedMessage entity, Chat page queue
// @what: Entity for a chat message sent while the conversation was busy, waiting its turn until it can start.
// @flow: Stored by ChatQueuedMessages; drained by the chat queue worker; sender can edit or cancel.
// @find: queued message, chat queue, message waiting, send while busy, edit queued message, cancel queued message, expire queued, chat_queued_messages, ChatQueuedMessage entity, Chat page queue
// @what: Entity for a chat message sent while the conversation was busy, waiting its turn until it can start.
// @flow: Stored by ChatQueuedMessages; drained by the chat queue worker; sender can edit or cancel.
package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * A message sent while its conversation already had work in progress, waiting its turn.
 *
 * <p>It is not a chat message yet: it joins the thread, as an ordinary message, only when it
 * starts. Until then its sender (or the conversation's owner) can edit or cancel it.
 */
@Entity
@Table(name = "chat_queued_messages")
public class ChatQueuedMessage implements Persistable<UUID> {

    public static final String QUEUED = "queued";
    public static final String STARTING = "starting";
    public static final String EXPIRED = "expired";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Transient
    private boolean isNew = true;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(nullable = false, columnDefinition = "text")
    private String text = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_ids", nullable = false)
    private List<String> agentIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_ids", nullable = false)
    private List<String> attachmentIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> actor = Map.of();

    @Column(nullable = false)
    private String status = QUEUED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // @find: create queued message, queue chat message
    // @find: create queued message, queue chat message
    public static ChatQueuedMessage of(
            UUID orgId,
            UUID conversationId,
            UUID authorId,
            String text,
            List<UUID> agentIds,
            List<UUID> attachmentIds,
            Map<String, Object> actor) {
        ChatQueuedMessage queued = new ChatQueuedMessage();
        queued.orgId = orgId;
        queued.conversationId = conversationId;
        queued.authorId = authorId;
        queued.text = text == null ? "" : text;
        queued.agentIds = agentIds == null
                ? new ArrayList<>()
                : new ArrayList<>(agentIds.stream().map(UUID::toString).toList());
        queued.attachmentIds = attachmentIds == null
                ? new ArrayList<>()
                : new ArrayList<>(attachmentIds.stream().map(UUID::toString).toList());
        queued.actor = actor == null ? Map.of() : actor;
        return queued;
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

    public UUID getAuthorId() {
        return authorId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        this.updatedAt = Instant.now();
    }

    public List<UUID> getAgentIds() {
        return agentIds == null ? List.of() : agentIds.stream().map(UUID::fromString).toList();
    }

    public List<UUID> getAttachmentIds() {
        return attachmentIds == null
                ? List.of()
                : attachmentIds.stream().map(UUID::fromString).toList();
    }

    public Map<String, Object> getActor() {
        return actor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
