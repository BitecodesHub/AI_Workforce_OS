// @find: conversation participant, share private chat, add person to conversation, who can read private chat, conversation_participants, ConversationParticipant entity, Share chat dialog
// @what: Entity for a person added to a private conversation so they may read it.
// @flow: Stored by ConversationParticipants; checked by chat access rules.
// @find: conversation participant, share private chat, add person to conversation, who can read private chat, conversation_participants, ConversationParticipant entity, Share chat dialog
// @what: Entity for a person added to a private conversation so they may read it.
// @flow: Stored by ConversationParticipants; checked by chat access rules.
package os.aiworkforce.orchestrator.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A person, other than its creator, who may read a private conversation. */
@Entity
@Table(name = "conversation_participants")
@IdClass(ConversationParticipant.Key.class)
public class ConversationParticipant {

    /** The composite primary key: one row per person per conversation. */
    public static class Key implements Serializable {
        private UUID conversationId;
        private String userId;

        public Key() {}

        public Key(UUID conversationId, String userId) {
            this.conversationId = conversationId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(conversationId, key.conversationId)
                    && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, userId);
        }
    }

    @Id
    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "text")
    private String userId;

    @Column(name = "added_by", nullable = false, updatable = false, columnDefinition = "text")
    private String addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt = Instant.now();

    protected ConversationParticipant() {}

    public ConversationParticipant(UUID conversationId, String userId, String addedBy) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.addedBy = addedBy;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
