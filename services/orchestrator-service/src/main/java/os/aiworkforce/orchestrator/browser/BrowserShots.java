// @find: browser screenshots repository, find screenshot by id, BrowserShots
// @what: Spring Data repository for BrowserShot rows.
// @flow: Used by BrowserShotService and BrowserShotController
package os.aiworkforce.orchestrator.browser;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrowserShots extends JpaRepository<BrowserShot, UUID> {

    Optional<BrowserShot> findByIdAndOrgId(UUID id, UUID orgId);
}
