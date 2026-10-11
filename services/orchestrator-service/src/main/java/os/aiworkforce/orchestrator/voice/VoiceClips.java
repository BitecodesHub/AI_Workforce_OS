// @find: voice clips repository, find clip by id, VoiceClips, audio clip lookup
// @what: Spring Data repository for VoiceClip rows.
// @flow: Used by VoiceController.clip
package os.aiworkforce.orchestrator.voice;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, and one interface per file is the convention this codebase follows
 * for exactly that reason.
 */

/** Spring Data repository for {@link VoiceClip} rows, used by {@code VoiceClipService} and {@code VoiceController}. */
public interface VoiceClips extends JpaRepository<VoiceClip, UUID> {

    /** One clip by id, only when it belongs to the given workspace. */
    Optional<VoiceClip> findByIdAndOrgId(UUID id, UUID orgId);
}
