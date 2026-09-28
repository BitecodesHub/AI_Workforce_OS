package os.aiworkforce.orchestrator.voice;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, and one interface per file is the convention this codebase follows
 * for exactly that reason.
 */

public interface VoiceClips extends JpaRepository<VoiceClip, UUID> {

    Optional<VoiceClip> findByIdAndOrgId(UUID id, UUID orgId);
}
