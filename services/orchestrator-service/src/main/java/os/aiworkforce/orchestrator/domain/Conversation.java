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

    /** The position the next message appended to this conversation will get. */
    public int nextPosition() {
        return messageCount;
    }

    /** Records that one more message has just been appended, and what it previews as. */
    public void recordMessage(String preview) {
        messageCount++;
        lastMessagePreview = preview == null ? "" : preview;
    }
}
