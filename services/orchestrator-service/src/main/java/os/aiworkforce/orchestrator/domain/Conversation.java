// @find: conversation, chat thread, new chat, chat list, conversation title, visibility, private conversation, workspace conversation, message count, last message preview, deciding routing, conversations table, Conversation entity, Chat page
// @what: Entity for a chat thread between people and the workforce, with visibility, title and a message counter.
// @flow: Stored by Conversations; participants in ConversationParticipant; per-user marks in ConversationMark.
// @find: conversation, chat thread, new chat, chat list, conversation title, visibility, private conversation, workspace conversation, message count, last message preview, deciding routing, conversations table, Conversation entity, Chat page
// @what: Entity for a chat thread between people and the workforce, with visibility, title and a message counter.
// @flow: Stored by Conversations; participants in ConversationParticipant; per-user marks in ConversationMark.
package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A thread of conversation between a person and the workforce.
 *
 * <p>{@code messageCount} and {@code lastMessagePreview} are kept here, updated in the same write
 * that appends a message, rather than computed by joining to the messages table on every list.
 * That write is also what advances {@code updatedAt} through the ordinary auditing listener: a
 * conversation is otherwise never itself edited, so without a real field changing on it there
 * would be nothing to make that write dirty, and "newest first" would silently mean "most
 * recently created" instead of "most recently active".
 */
@Entity
@Table(name = "conversations")
public class Conversation extends OrgScopedEntity {

    @Column(nullable = false, columnDefinition = "text")
    private String title = "";

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "last_message_preview", nullable = false, columnDefinition = "text")
    private String lastMessagePreview = "";

    /** {@code workspace}: everyone with Chat access reads it. {@code private}: its creator and the people added. */
    @Column(nullable = false, columnDefinition = "text")
    private String visibility = "workspace";

    /** When a message's routing started being decided, before any goal exists; null otherwise. */
    @Column(name = "deciding_since")
    private java.time.Instant decidingSince;

    /** The message being decided, so only its own decision clears the mark. */
    @Column(name = "deciding_message_id")
    private java.util.UUID decidingMessageId;

    public java.time.Instant getDecidingSince() {
        return decidingSince;
    }

    public java.util.UUID getDecidingMessageId() {
        return decidingMessageId;
    }

    // @find: mark message routing in progress, deciding which agent
    // @find: mark message routing in progress, deciding which agent
    /** Marks a message as being decided. */
    public void startDeciding(java.util.UUID messageId) {
        this.decidingSince = java.time.Instant.now();
        this.decidingMessageId = messageId;
    }

    // @find: clear routing in progress mark
    // @find: clear routing in progress mark
    /** Clears the mark when it is this message's; true when it was. */
    public boolean finishDeciding(java.util.UUID messageId) {
        if (decidingMessageId == null || !decidingMessageId.equals(messageId)) {
            return false;
        }
        this.decidingSince = null;
        this.decidingMessageId = null;
        return true;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = "private".equals(visibility) ? "private" : "workspace";
    }

    public boolean isPrivate() {
        return "private".equals(visibility);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    // @find: next message position in conversation
    // @find: next message position in conversation
    /** The position the next message appended to this conversation will get. */
    public int nextPosition() {
        return messageCount;
    }

    // @find: record appended message, update preview and count, bump last activity
    // @find: record appended message, update preview and count, bump last activity
    /** Records that one more message has just been appended, and what it previews as. */
    public void recordMessage(String preview) {
        messageCount++;
        lastMessagePreview = preview == null ? "" : preview;
    }
}
