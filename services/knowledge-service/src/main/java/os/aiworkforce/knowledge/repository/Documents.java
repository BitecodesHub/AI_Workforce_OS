package os.aiworkforce.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.knowledge.domain.Document;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Documents extends JpaRepository<Document, UUID> {

    Optional<Document> findBySourceIdAndExternalId(UUID sourceId, String externalId);

    List<Document> findBySourceIdOrderByTitle(UUID sourceId);

    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Documents the last crawl did not see, so they can be tombstoned.
     *
     * <p>A document deleted at the source has to stop being citable. Without this sweep, an
     * answer keeps quoting a file that no longer exists, and the citation leads nowhere.
     */
    @Query(
            """
            select d from Document d
            where d.sourceId = :sourceId
              and d.tombstonedAt is null
              and d.externalId not in :seen
            """)
    List<Document> findMissingSince(@Param("sourceId") UUID sourceId, @Param("seen") List<String> seenExternalIds);
}
