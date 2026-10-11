// @find: browser screenshots repository, find screenshot by id, BrowserShots
// @what: Spring Data repository for BrowserShot rows.
// @flow: Used by BrowserShotService and BrowserShotController
package os.aiworkforce.orchestrator.browser;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link BrowserShot} rows, used by {@code BrowserShotService} to store and by {@code BrowserShotController} to read. */
public interface BrowserShots extends JpaRepository<BrowserShot, UUID> {

    /** One screenshot by id, only when it belongs to the given workspace. */
    Optional<BrowserShot> findByIdAndOrgId(UUID id, UUID orgId);
}
