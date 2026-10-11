// @find: knowledge base, knowledge, documents, sources, documents repository, find document by name, list documents in source, replace document, delete document, Documents
// @what: Spring Data repository for documents, including lookup by source and file name used to detect re-uploads.
// @flow: Called by IngestionService.ingest/deleteDocument and KnowledgeController list endpoints.
package os.aiworkforce.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.knowledge.domain.Document;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Documents of the knowledge base. Used by {@code IngestionService}, {@code KnowledgeController}
 * and {@code AgentKnowledgeController}. Every method works on one source or one workspace, and
 * callers pass the workspace explicitly where a document is looked up by its own identifier.
 */
public interface Documents extends JpaRepository<Document, UUID> {

    /**
     * The document a source holds under a name (its external id, which is the file name), or empty.
     * Ingestion uses it to find the document that a re-upload replaces.
     */
    Optional<Document> findBySourceIdAndExternalId(UUID sourceId, String externalId);

    /** Every document of a source, including skipped and tombstoned ones, ordered by title. */
    List<Document> findBySourceIdOrderByTitle(UUID sourceId);

    /** A document by identifier within a workspace, or empty; a document of another workspace is never returned. */
    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);

    /** Whether a source already holds a document under that name, used to find a free name when keeping both versions. */
    boolean existsBySourceIdAndExternalId(UUID sourceId, String externalId);

    /** Erases every document of a source. Their passages are deleted first, by the caller. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from Document d where d.sourceId = :sourceId")
    int deleteBySourceId(@Param("sourceId") UUID sourceId);

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
