// @find: credentials repository, find credential by ref, list credentials, record credential use, last used, API keys storage, Credentials JPA repository
// @what: Spring Data repository for stored encrypted credentials.
// @flow: Used by CredentialService.
package os.aiworkforce.organisation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.organisation.domain.Credential;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Credentials extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByOrgIdAndRef(UUID orgId, String ref);

    List<Credential> findByOrgIdOrderByRef(UUID orgId);

    boolean existsByOrgIdAndRef(UUID orgId, String ref);

    // @find: record credential use, update last used time without version conflict
    /**
     * Records a read on the row without loading it for a save. A save goes through the version
     * check, and two runs reading the same key at once (every concurrent run of a workspace does)
     * then failed one of them with a conflict, so the run saw "credential store unreachable" and
     * skipped a provider that was fine. This update leaves the version alone.
     */
    @Modifying
    @Query("update Credential c set c.lastUsedAt = :now where c.id = :id")
    int recordUse(@Param("id") UUID id, @Param("now") Instant now);
}
