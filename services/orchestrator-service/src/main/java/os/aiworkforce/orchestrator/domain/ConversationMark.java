// @find: conversation mark, pin conversation, archive conversation, unread, read position, mark as read, personal chat flags, conversation_marks, ConversationMark entity, Chat list pin archive
// @what: Entity for one person's own pin, archive and read position on a shared conversation.
// @flow: Written by native upserts in ConversationMarks; read by the chat list.
// @find: conversation mark, pin conversation, archive conversation, unread, read position, mark as read, personal chat flags, conversation_marks, ConversationMark entity, Chat list pin archive
// @what: Entity for one person's own pin, archive and read position on a shared conversation.
// @flow: Written by native upserts in ConversationMarks; read by the chat list.
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
 * One person's personal mark on a conversation: pinned to the top of their own list, archived out
 * of it, or read up to a position.
 *
 * <p>The conversation list itself is shared by the whole workspace, so these marks live in their
 * own table rather than on {@link Conversation} - pinning or archiving a shared thread must change
 * nobody else's list, and reading it must not mark it read for anyone else either.
 *
 * <p>Implements {@link Persistable} for the same reason {@link ChatMessage} does: the id is
 * assigned in Java, so Spring Data cannot use nullness to tell an insert from an update. In
 * practice every write goes through {@code ConversationMarks}'s native upserts rather than
 * {@code save}, but the row is still loaded and returned by ordinary finders.
 */
@Entity
@Table(name = "conversation_marks")
public class ConversationMark implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Transient
    private boolean isNew = true;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean archived;

    /** The highest message position this person has seen. Null means never opened here. */
    @Column(name = "last_read_position")
    private Integer lastReadPosition;

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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Integer getLastReadPosition() {
        return lastReadPosition;
    }

    public void setLastReadPosition(Integer lastReadPosition) {
        this.lastReadPosition = lastReadPosition;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
