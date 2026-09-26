package os.aiworkforce.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.knowledge.domain.Chunk;
import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/** A passage with everything needed to cite it, assembled in one query. */
public interface CitableChunk {
    UUID getChunkId();

    UUID getDocumentId();

    UUID getSourceId();

    String getDocumentTitle();

    String getUri();

    Integer getPageNumber();

    String getHeading();

    String getContent();
}
