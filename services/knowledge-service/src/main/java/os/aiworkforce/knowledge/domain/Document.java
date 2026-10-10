// @find: knowledge base, knowledge, documents, sources, document entity, uploaded file, documents table, document status indexed skipped failed, content hash, unchanged file, replaced version, notice, skip reason, page count, Document
// @what: JPA entity for one uploaded file inside a source, with its status, content hash, notice and skip reason.
// @flow: Written by IngestionService.ingest (upload, re-upload, replace); read by KnowledgeController document lists.
package os.aiworkforce.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One document inside a source.
 *
 * <p>The content hash is what makes re-ingestion cheap: a document whose hash is unchanged is
 * skipped without being read, chunked or embedded.
 *
 * <p>{@code skipReason} exists so a document that cannot be indexed says why in words somebody can
 * act on. "Encrypted" and "no text layer" need completely different remedies, and a generic
 * failure sends a person looking in the wrong place.
 *
 * <p>{@code notice} is its non-blocking counterpart: something worth knowing about a document that
 * was indexed, such as only the first part of a very long file being kept. It is never written to
 * {@code skipReason}, because a skip reason means nothing of the document was indexed at all.
 */
@Entity
@Table(name = "documents")
public class Document extends OrgScopedEntity {

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String title;

    @Column
    private String uri;

    @Column(name = "media_type", nullable = false)
    private String mediaType = "text/plain";

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "skip_reason", columnDefinition = "text")
    private String skipReason;

    @Column(columnDefinition = "text")
    private String notice;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    /** Excludes the document from retrieval at once, before the vector purge has run. */
    @Column(name = "tombstoned_at")
    private Instant tombstonedAt;

    public void tombstone() {
        this.tombstonedAt = Instant.now();
        this.status = "tombstoned";
    }

    /**
     * Makes the document citable again when a file is uploaded under its name. Without this, a
     * re-upload would report "indexed" while retrieval, which skips tombstoned rows, never found it.
     */
    public void clearTombstone() {
        this.tombstonedAt = null;
    }

    public boolean isIndexed() {
        return "indexed".equals(status) && tombstonedAt == null;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }

    public Instant getTombstonedAt() {
        return tombstonedAt;
    }
}
