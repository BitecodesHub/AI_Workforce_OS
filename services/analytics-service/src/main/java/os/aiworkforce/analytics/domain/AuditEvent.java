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

    /** Application-assigned time-ordered identifier, stable before the row is saved. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    /**
     * Workspace the entry belongs to; null for platform-level events, which join the platform
     * chain.
     */
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

    /** Who or what acted: a person, an agent or a service identifier. */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    /** The kind of actor, such as user, agent or service. */
    @Column(name = "actor_kind", nullable = false, updatable = false)
    private String actorKind;

    /** The person an agent or service acted for, when there was one. */
    @Column(name = "on_behalf_of", updatable = false)
    private String onBehalfOf;

    /** Action code such as {@code member.role_change}. */
    @Column(nullable = false, updatable = false)
    private String action;

    /** Kind of thing acted on. */
    @Column(name = "resource_type", nullable = false, updatable = false)
    private String resourceType;

    /** The specific thing acted on, when there is one. */
    @Column(name = "resource_id", updatable = false)
    private String resourceId;

    /** succeeded, failed, denied or locked. */
    @Column(nullable = false, updatable = false)
    private String outcome;

    /** Free-form structured context, covered by the entry hash in canonical form; never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> detail = Map.of();

    /** Identifier of the request that caused the action, for correlating with logs. */
    @Column(name = "request_id", updatable = false)
    private String requestId;

    /**
     * When the action happened, truncated to microseconds so the stored value matches the hashed
     * one.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    /** Hash of the previous entry of the same chain; null for the first entry. */
    @Column(name = "previous_hash", updatable = false)
    private String previousHash;

    /**
     * Digest of this entry, computed by AuditChain from the covered fields and the previous hash.
     */
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

    /**
     * Not persisted: true until the entity has been inserted or loaded, which is what {@link
     * #isNew} reports to Spring Data.
     */
    @Transient
    private boolean fresh = true;

    /** Returns the identifier; part of the {@link Persistable} contract. */
    @Override
    public UUID getId() {
        return id;
    }

    /**
     * Part of the {@link Persistable} contract: true until the entity has been persisted or
     * loaded, so Spring Data inserts it instead of merging it.
     */
    @Override
    public boolean isNew() {
        return fresh;
    }

    /** Lifecycle callback run after an insert or a load; flips the entity to not new. */
    @PostPersist
    @PostLoad
    void markStored() {
        fresh = false;
    }

    /**
     * Accessors and mutators below expose the fields documented above. The sequence has no setter
     * because only the database assigns it. Setters do not validate; AuditAppender sets every
     * field before the single insert.
     */
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
