package os.aiworkforce.knowledge.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * Somewhere documents come from.
 *
 * <p>The watermark is what makes ingestion incremental. Without it, every crawl re-reads and
 * re-embeds a corpus that has not changed, which on a metered embedding provider is a bill for
 * doing nothing.
 *
 * <p>The embedding model and its dimension are recorded because changing either invalidates the
 * index: vectors of different widths cannot be compared, and the symptom is poor recall rather
 * than an error. Storing the dimension turns that into a refusal.
 */
@Entity
@Table(name = "sources")
public class Source extends OrgScopedEntity {

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "credential_ref")
    private String credentialRef;

    @Column(nullable = false)
    private String status = "idle";

    @Column
    private String watermark;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 1200;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 150;

    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider = "sandbox";

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel = "sandbox-embed-1";

    @Column(name = "embedding_dimension", nullable = false)
    private int embeddingDimension = 1536;

    @Column(nullable = false)
    private String collection;

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "last_ingested_at")
    private Instant lastIngestedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    public boolean isReady() {
        return "ready".equals(status);
    }

    /** Set when a stored credential stops working, so the console can prompt for re-consent. */
    public void requireReconnect(String reason) {
        this.status = "reconnect_required";
        this.lastError = reason;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWatermark() {
        return watermark;
    }

    public void setWatermark(String watermark) {
        this.watermark = watermark;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(int documentCount) {
        this.documentCount = documentCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Instant getLastIngestedAt() {
        return lastIngestedAt;
    }

    public void setLastIngestedAt(Instant lastIngestedAt) {
        this.lastIngestedAt = lastIngestedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
