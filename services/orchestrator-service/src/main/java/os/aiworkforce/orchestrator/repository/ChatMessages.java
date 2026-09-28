package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.ChatMessage;

/**
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention (see the other
 * repositories in this package).
 */
public interface ChatMessages extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByPosition(UUID conversationId);

    Optional<ChatMessage> findByIdAndConversationId(UUID id, UUID conversationId);

    List<ChatMessage> findByGoalId(UUID goalId);
}
