// @find: audit event, audit_events table, audit log row, entry hash, previous hash, chain key, sequence, actor, on behalf of, action, resource, outcome, detail json, tamper evident
// @what: Database entity for one append-only audit log entry with its hash chain fields.
// @flow: Written by AuditAppender; read by AuditSearch and AuditVerification
package os.aiworkforce.analytics.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

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
 *
 * <p>Implements {@link Persistable} so Spring Data inserts it with {@code persist} rather than
 * {@code merge}. An entity with an assigned identifier and no version looks "not new" to Spring
 * Data, so {@code save} merged it and handed back a different, managed copy: the sequence the
 * database assigned was set on the copy and never on the object the caller held, which is why
 * every response reported sequence 0.
 *
 * <p>Every column is {@code updatable = false}, because the table is append-only and a trigger
 * refuses any UPDATE. Without it, the re-select that reads {@code sequence} back loads
 * {@code detail} as the database stores it - a {@code Long} 1 comes back as an {@code Integer} 1 -
 * so dirty checking saw a change and flushed an UPDATE, which the trigger refused and which rolled
 * the whole append back. ({@code @Immutable} would say the same, but refuses the pessimistic lock
 * the chain reads take.)
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", updatable = false)
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

    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    @Column(name = "actor_kind", nullable = false, updatable = false)
    private String actorKind;

    @Column(name = "on_behalf_of", updatable = false)
    private String onBehalfOf;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private String resourceId;

    @Column(nullable = false, updatable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> detail = Map.of();

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(name = "previous_hash", updatable = false)
    private String previousHash;

    @Column(name = "entry_hash", nullable = false, updatable = false)
    private String entryHash;

    /** Which formula {@link #entryHash} was computed with: 1 for the original, 2 since this chain. */
    @Column(name = "hash_version", nullable = false, updatable = false)
    private short hashVersion = 2;

    /** The workspace id, or {@code platform}: the chain this entry belongs to. */
    @Column(name = "chain_key", updatable = false)
    private String chainKey;

    /** Set by the sender before its first attempt, so a retried delivery is recognised. */
    @Column(name = "event_uuid", updatable = false)
    private UUID eventUuid;

    @Transient
    private boolean fresh = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markStored() {
        fresh = false;
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

    /** Kept to the microsecond the column holds, so what was hashed is what is stored. */
    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
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

    public short getHashVersion() {
        return hashVersion;
    }

    public void setHashVersion(short hashVersion) {
        this.hashVersion = hashVersion;
    }

    public String getChainKey() {
        return chainKey;
    }

    public void setChainKey(String chainKey) {
        this.chainKey = chainKey;
    }

    public UUID getEventUuid() {
        return eventUuid;
    }

    public void setEventUuid(UUID eventUuid) {
        this.eventUuid = eventUuid;
    }
}
