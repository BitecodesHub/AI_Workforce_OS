// @find: knowledge base, knowledge, documents, sources, source entity, create knowledge source, where the knowledge base is stored, sources table, restricted source, agent-owned source, embedding model per source, Qdrant collection, watermark, chunk size, Source
// @what: JPA entity for a knowledge source (a named folder of documents) with its embedding model, collection, restricted flag and optional owning agent.
// @flow: Created by IngestionService.createSource; read by KnowledgeController, RetrievalService and Sources repository.
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
 *
 * <p>Created and updated by {@code IngestionService}, listed by {@code KnowledgeController}, filtered
 * by {@code Sources} and read by {@code RetrievalService}. The identifier, workspace and timestamps
 * come from {@code OrgScopedEntity}. The getters and setters are plain accessors and are not
 * documented one by one.
 */
@Entity
@Table(name = "sources")
public class Source extends OrgScopedEntity {

    /** The kind of source; {@code upload} for a folder of uploaded files. */
    @Column(nullable = false)
    private String kind;

    /** The name shown in the console. Unique within the workspace, ignoring case. */
    @Column(nullable = false)
    private String name;

    /** A reference to the place the documents come from, for a source that is connected rather than uploaded. Not set by uploads. */
    @Column(name = "external_ref")
    private String externalRef;

    /** A reference to the stored credential used to read an external source. Not set by uploads. */
    @Column(name = "credential_ref")
    private String credentialRef;

    /** The state: {@code idle} until something is indexed, {@code ready}, or {@code reconnect_required}. */
    @Column(nullable = false)
    private String status = "idle";

    /** Where the last crawl of an external source stopped, so the next one reads only what is new. */
    @Column
    private String watermark;

    /** The target passage size in characters used when its documents are chunked. */
    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 1200;

    /** How many characters each passage repeats from the previous one. */
    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 150;

    /** The provider that embeds this source's passages; {@code sandbox} means keyword search only. */
    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider = "sandbox";

    /** The embedding model of that provider. */
    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel = "sandbox-embed-1";

    /** How wide the model's vectors are. */
    @Column(name = "embedding_dimension", nullable = false)
    private int embeddingDimension = 1536;

    /** The name of the vector collection that holds this source's vectors; it depends on the workspace and the model. */
    @Column(nullable = false)
    private String collection;

    /** How many documents are indexed, not counting skipped ones. */
    @Column(name = "document_count", nullable = false)
    private int documentCount;

    /** How many passages the indexed documents hold. */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** When a document was last indexed into the source. */
    @Column(name = "last_ingested_at")
    private Instant lastIngestedAt;

    /** The latest problem, such as the vector store being unavailable; null when there is none. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /**
     * Searchable, listed and counted only for people who manage knowledge. Everyone else is told
     * the source does not exist, rather than that it is hidden, so its name does not leak either.
     */
    @Column(nullable = false)
    private boolean restricted;

    /**
     * The one agent this source belongs to, or null for a workspace source. An agent's own source is
     * searched only by that agent and is not part of the workspace's list or search.
     */
    @Column(name = "agent_id")
    private java.util.UUID agentId;

    public java.util.UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(java.util.UUID agentId) {
        this.agentId = agentId;
    }

    /** The provider that produces offline vectors which carry no meaning (see SandboxProvider). */
    public static final String SANDBOX_PROVIDER = "sandbox";

    /** True when the source status is {@code ready}. */
    public boolean isReady() {
        return "ready".equals(status);
    }

    /**
     * Whether this source can be searched by meaning. Sandbox vectors are pseudo-random, so for a
     * sandbox source only the keyword half of retrieval is real, and the source says so.
     */
    public boolean isSearchableByMeaning() {
        return !SANDBOX_PROVIDER.equalsIgnoreCase(embeddingProvider);
    }

    /** How this source is searched, in the words the console shows: keyword, or keyword and meaning. */
    public String getSearchMode() {
        return isSearchableByMeaning() ? "keyword+meaning" : "keyword";
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

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }
}
