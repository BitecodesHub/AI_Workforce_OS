package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.ConversationParticipant;

public interface ConversationParticipants extends JpaRepository<ConversationParticipant, ConversationParticipant.Key> {

    List<ConversationParticipant> findByConversationId(UUID conversationId);

    boolean existsByConversationIdAndUserId(UUID conversationId, String userId);
}
