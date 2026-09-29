package os.aiworkforce.analytics.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One append-only entry in the audit projection.
 *
 * <p>Deliberately not a {@link os.aiworkforce.platform.web.persistence.BaseEntity}. That
 * superclass assigns its identifier in Java precisely so a row can be referenced before it is
 * flushed - but {@code sequence} here is the opposite kind of value: only the database can assign
 * it, because its entire job is to say which row came after which, and only the database sees
 * every concurrent insert. Hibernate reads it back after the insert via {@code @GeneratedValue},
 * which a plain {@code @Version}-carrying {@code BaseEntity} row is not set up to do for a second,
 * database-owned identifier. The row's own {@code id} stays application-assigned, as everywhere
 * else on this platform, so a caller can still hold a stable reference to it before it is saved.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id")
    private UUID orgId;

    /**
     * Assigned by the {@code BIGSERIAL} column itself, read back after insert.
     *
     * <p>{@code @GeneratedValue} only applies to the identifier property, and {@code id} already
     * holds that role - a second, database-owned generated value needs Hibernate's own
     * {@code @Generated}, which leaves the column out of the insert statement entirely and
     * re-selects the row afterward instead.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "sequence", nullable = false, updatable = false, insertable = false)
    private long sequence;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_kind", nullable = false)
    private String actorKind;

    @Column(name = "on_behalf_of")
    private String onBehalfOf;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(nullable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> detail = Map.of();

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "previous_hash")
    private String previousHash;

    @Column(name = "entry_hash", nullable = false)
    private String entryHash;

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorKind() {
        return actorKind;
    }

    public void setActorKind(String actorKind) {
        this.actorKind = actorKind;
    }

    public String getOnBehalfOf() {
        return onBehalfOf;
    }

    public void setOnBehalfOf(String onBehalfOf) {
        this.onBehalfOf = onBehalfOf;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail == null ? Map.of() : detail;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public void setEntryHash(String entryHash) {
        this.entryHash = entryHash;
    }
}
