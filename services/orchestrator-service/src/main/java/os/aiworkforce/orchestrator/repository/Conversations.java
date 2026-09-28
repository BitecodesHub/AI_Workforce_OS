package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.Conversation;

/**
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention (see the other
 * repositories in this package).
 */
public interface Conversations extends JpaRepository<Conversation, UUID> {

    /** Every conversation in the workspace, most recently active first - visible to anyone in it. */
    List<Conversation> findByOrgIdOrderByUpdatedAtDesc(UUID orgId, Pageable pageable);

    Optional<Conversation> findByIdAndOrgId(UUID id, UUID orgId);
}
