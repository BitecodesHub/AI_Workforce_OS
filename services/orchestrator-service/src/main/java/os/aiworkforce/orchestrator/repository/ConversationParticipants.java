// @find: conversation participants repository, who is in private chat, is participant, share conversation, ConversationParticipants
// @what: Spring Data repository for ConversationParticipant rows.
// @flow: Used by chat access checks and the share dialog.
// @find: conversation participants repository, who is in private chat, is participant, share conversation, ConversationParticipants
// @what: Spring Data repository for ConversationParticipant rows.
// @flow: Used by chat access checks and the share dialog.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.ConversationParticipant;

public interface ConversationParticipants extends JpaRepository<ConversationParticipant, ConversationParticipant.Key> {

    // @find: list participants of conversation
    // @find: list participants of conversation
    List<ConversationParticipant> findByConversationId(UUID conversationId);

    // @find: is user a participant, can read private chat
    // @find: is user a participant, can read private chat
    boolean existsByConversationIdAndUserId(UUID conversationId, String userId);
}
