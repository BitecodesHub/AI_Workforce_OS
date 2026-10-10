// @find: knowledge base, knowledge, documents, sources, citable chunk, citation, passage projection, search result row, grounding, CitableChunk
// @what: Read-only projection of a passage with the document and source names needed to cite it.
// @flow: Returned by Chunks queries; turned into RetrievalService.Passage.
package os.aiworkforce.knowledge.repository;

import java.util.UUID;

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
