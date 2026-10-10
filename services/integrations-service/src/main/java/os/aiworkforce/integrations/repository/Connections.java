// @find: connections repository, find connection by server, list connections, connector connection lookup, database access
// @what: Spring Data repository for connections, scoped to the workspace.
// @flow: Used by ConnectorService and OAuthService
package os.aiworkforce.integrations.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.integrations.domain.Connection;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Connections extends JpaRepository<Connection, UUID> {

    List<Connection> findByOrgId(UUID orgId);

    Optional<Connection> findByOrgIdAndServer(UUID orgId, String server);

    /**
     * Connections whose token is close to expiring.
     *
     * <p>Refreshed ahead of time by a background sweep, so a person's action never fails
     * because a token lapsed a minute ago.
     */
    @Query(
            """
            select c from Connection c
            where c.status = 'connected'
              and c.reconnectRequired = false
              and c.tokenExpiresAt is not null
              and c.tokenExpiresAt < :before
            """)
    List<Connection> findNeedingRefresh(@Param("before") Instant before, Pageable pageable);
}
