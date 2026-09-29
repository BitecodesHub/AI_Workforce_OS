package os.aiworkforce.platform.web.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * What every persisted row carries.
 *
 * <p>Identifiers are UUIDv7, assigned by the application rather than the database. Two reasons:
 * a service can reference a row it has not yet flushed, and a sequential UUID keeps index
 * locality, which a random v4 destroys on a large table.
 *
 * <p>The version column is not optional. Two people editing the same agent is ordinary, and
 * without optimistic locking the second save silently discards the first.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 64)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    /**
     * Whether this entity has been written yet.
     *
     * <p>Required because the identifier is assigned in Java rather than by the database. Spring
     * Data decides between insert and update by asking whether the id is null, and here it never
     * is - so every new entity was treated as existing and merged rather than persisted. Merge
     * returns a <em>different</em> managed copy; code that kept using the original then saved a
     * stale version over a newer one and failed with an optimistic-locking conflict. That is what
     * broke creating a goal, and it would have broken anything else that saved an entity twice.
     */
    @Transient
    @JsonIgnore
    private boolean isNew = true;

    @Override
    @JsonIgnore
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Adopts the version of the copy a save returned.
     *
     * <p>Saving an entity that is not attached to the current persistence context (one kept
     * across several short transactions, such as a run driven step by step) merges it: the
     * repository returns a different managed copy carrying the incremented version, and the
     * original keeps the old one. Saving the original again would then fail an optimistic-lock
     * check against its own earlier write. Calling this with the returned copy keeps them in step.
     */
    public void adoptVersion(BaseEntity saved) {
        if (saved != null && saved != this) {
            this.version = saved.version;
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    /**
     * Identity by primary key, not by field values.
     *
     * <p>Comparing a managed entity with a detached one by their fields makes two rows look equal
     * while one holds stale data. The identifier exists before the insert, so it is stable across
     * the whole lifecycle and safe to use in a collection.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity entity)) {
            return false;
        }
        return id != null && Objects.equals(id, entity.id);
    }

    @Override
    public final int hashCode() {
        return id == null ? getClass().hashCode() : id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + id + ", version=" + version + "]";
    }
}
