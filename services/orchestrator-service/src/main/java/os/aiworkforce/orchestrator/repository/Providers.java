// @find: providers repository, list providers visible to workspace, platform providers, priority order, Providers
// @what: Spring Data repository for LlmProviderEntity rows.
// @flow: Used by the provider registry and Providers page.
// @find: providers repository, list providers visible to workspace, platform providers, priority order, Providers
// @what: Spring Data repository for LlmProviderEntity rows.
// @flow: Used by the provider registry and Providers page.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.LlmProviderEntity;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * The provider catalogue.
 *
 * <p>Read-only for workspaces. A platform-wide row (no org) is shared by every workspace, so
 * nothing a workspace does may write it: a workspace's own choices and what its key last did live
 * in {@link WorkspaceProviderSettings}, keyed by the workspace.
 */
public interface Providers extends JpaRepository<LlmProviderEntity, String> {

    // @find: providers visible to a workspace, platform and own, by priority
    // @find: providers visible to a workspace, platform and own, by priority
    /** Platform-wide providers plus any the workspace added for itself. */
    @Query("select p from LlmProviderEntity p where p.orgId is null or p.orgId = :orgId order by p.priority")
    List<LlmProviderEntity> findVisibleTo(@Param("orgId") UUID orgId);
}
