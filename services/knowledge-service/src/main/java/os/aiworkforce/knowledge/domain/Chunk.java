package os.aiworkforce.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One retrievable passage.
 *
 * <p>The text is stored here as well as in the vector store, because a citation has to be able to
 * show the passage it refers to and a vector database is not a document store. The page and
 * character offsets are what let a citation point at a place rather than at a whole file.
 *
 * <p>It reports whether it is new for the same reason {@code BaseEntity} does: the identifier is
 * assigned in Java, so Spring Data would otherwise take every fresh chunk for an existing one and
 * merge it, which costs a SELECT per passage before each insert - thousands of them for one large
 * document.
 */
@Entity
@Table(name = "chunks")
public class Chunk implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column
    private String heading;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getPosition() {
        return position;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setTokenEstimate(int tokenEstimate) {
        this.tokenEstimate = tokenEstimate;
    }

    public int getTokenEstimate() {
        return tokenEstimate;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setCharStart(Integer charStart) {
        this.charStart = charStart;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public void setCharEnd(Integer charEnd) {
        this.charEnd = charEnd;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getHeading() {
        return heading;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
