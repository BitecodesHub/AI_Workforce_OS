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
 *
 * <p>Created and updated by {@code IngestionService} and listed by {@code KnowledgeController} and
 * {@code AgentKnowledgeController}. The identifier, workspace and timestamps come from
 * {@code OrgScopedEntity}. The getters and setters are plain accessors and are not documented one by one.
 */
@Entity
@Table(name = "documents")
public class Document extends OrgScopedEntity {

    /** The source the document is in. */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** The document's name within its source, which for an upload is the file name. Unique within the source. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /** What a citation shows; the same as the stored name. */
    @Column(nullable = false)
    private String title;

    /** Where to open the original, when there is somewhere to open. Uploads leave it empty. */
    @Column
    private String uri;

    /** What the file is, detected from its content. */
    @Column(name = "media_type", nullable = false)
    private String mediaType = "text/plain";

    /** The SHA-256 of the file's bytes, used to skip an unchanged re-upload. */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    /** The file size in bytes. */
    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    /** The number of pages, for a format that has them. */
    @Column(name = "page_count")
    private Integer pageCount;

    /** The number of passages stored; zero for a skipped document. */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** The state: {@code pending} while indexing is unfinished, {@code indexed}, {@code skipped} or {@code tombstoned}. */
    @Column(nullable = false)
    private String status = "pending";

    /** Why nothing of the document was indexed, in words a person can act on; null otherwise. */
    @Column(name = "skip_reason", columnDefinition = "text")
    private String skipReason;

    /** Something worth knowing about an indexed document, such as being cut at the size limit; null otherwise. */
    @Column(columnDefinition = "text")
    private String notice;

    /** When the document was last marked indexed. */
    @Column(name = "indexed_at")
    private Instant indexedAt;

    /** Excludes the document from retrieval at once, before the vector purge has run. */
    @Column(name = "tombstoned_at")
    private Instant tombstonedAt;

    /**
     * Marks the document as removed at its source and sets its status to {@code tombstoned}, so that retrieval
     * stops returning it. The caller saves the entity.
     */
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

    /** True when the document is indexed and not tombstoned, which is the only state whose passages are searchable and counted. */
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
