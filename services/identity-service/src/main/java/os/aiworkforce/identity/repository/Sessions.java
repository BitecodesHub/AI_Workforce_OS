package os.aiworkforce.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.PermissionRecord;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.Session;
import os.aiworkforce.identity.domain.User;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Sessions extends JpaRepository<Session, UUID> {

    Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * Revokes an entire token family at once.
     *
     * <p>A bulk update rather than a load-and-save loop: this runs on the reuse-detection
     * path, where the priority is closing the window before the holder of the stolen token
     * can use it again.
     */
    @Modifying
    @Query("""
            update Session s set s.revokedAt = :now, s.revokedReason = :reason
            where s.familyId = :familyId and s.revokedAt is null
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("now") Instant now,
            @Param("reason") String reason);

    @Modifying
    @Query("delete from Session s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
