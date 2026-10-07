package os.aiworkforce.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.SigningKey;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface SigningKeys extends JpaRepository<SigningKey, String> {

    /** The key that signs now. A unique partial index guarantees there is at most one. */
    @Query("select k from SigningKey k where k.status = 'active'")
    Optional<SigningKey> findActive();

    List<SigningKey> findByStatusIn(Collection<String> statuses);

    /**
     * Holds a transaction-scoped advisory lock while the first key is created.
     *
     * <p>Two replicas starting together against an empty table would each generate a key; the
     * unique index would refuse the second insert, and that replica would fail to start. Taking
     * this lock first makes the second wait, then find and adopt the key the first one wrote.
     * Released automatically when the transaction ends. Wrapped in a select of a constant
     * because {@code pg_advisory_xact_lock} returns {@code void}, which has no Java type.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:lockKey)) as held", nativeQuery = true)
    Integer holdKeyCreationLock(@Param("lockKey") long lockKey);
}
