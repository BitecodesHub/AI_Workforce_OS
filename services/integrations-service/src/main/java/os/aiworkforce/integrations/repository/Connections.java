// @find: connections repository, find connection by server, list connections, connector connection lookup, database access
// @what: Spring Data repository for connections, scoped to the workspace.
// @flow: Used by ConnectorService and OAuthService
package os.aiworkforce.integrations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.integrations.domain.Connection;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Spring Data repository for {@link Connection}, the workspace's connection to each connector.
 *
 * <p>Used by {@code ConnectorService} and {@code OAuthService} to read and save connections, and by
 * {@code IntegrationController} to list them. At most one row exists per workspace and connector.
 */
public interface Connections extends JpaRepository<Connection, UUID> {

    /**
     * Every connection a workspace has stored, for the Integrations page listing.
     *
     * @param orgId the workspace
     * @return the stored connections, empty when none exists
     */
    List<Connection> findByOrgId(UUID orgId);

    /**
     * The connection a workspace holds for one connector.
     *
     * @param orgId the workspace
     * @param server the connector key, for example {@code gmail}
     * @return the connection, or empty when the connector was never connected
     */
    Optional<Connection> findByOrgIdAndServer(UUID orgId, String server);
}
