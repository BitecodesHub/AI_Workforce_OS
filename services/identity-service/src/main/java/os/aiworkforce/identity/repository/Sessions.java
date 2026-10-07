package os.aiworkforce.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.Session;

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
     * The one usable link of each of a person's signed-in families, newest first.
     *
     * <p>Every refresh retires the link it used, so a family that is still signed in has exactly
     * one row that is neither used, revoked nor expired. Listing those rows lists the devices.
     */
    @Query(
            """
            select s from Session s
            where s.userId = :userId and s.revokedAt is null and s.usedAt is null and s.expiresAt > :now
            order by s.issuedAt desc
            """)
    List<Session> findLiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** When each family began, which is when that device signed in. Rows are {familyId, issuedAt}. */
    @Query(
            """
            select s.familyId, min(s.issuedAt) from Session s
            where s.userId = :userId and s.familyId in :familyIds
            group by s.familyId
            """)
    List<Object[]> findFamilyStarts(@Param("userId") UUID userId, @Param("familyIds") List<UUID> familyIds);

    boolean existsByFamilyIdAndUserId(UUID familyId, UUID userId);

    /**
     * Revokes an entire token family at once.
     *
     * <p>A bulk update rather than a load-and-save loop: this runs on the reuse-detection
     * path, where the priority is closing the window before the holder of the stolen token
     * can use it again.
     */
    @Modifying
    @Query(
            """
            update Session s set s.revokedAt = :now, s.revokedReason = :reason
            where s.familyId = :familyId and s.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now, @Param("reason") String reason);

    /**
     * Revokes every session a person has, on every device.
     *
     * <p>Each sign-in starts its own family, so revoking the presented token's family reaches one
     * device only. "Sign out everywhere", a password reset and a password change all need this.
     */
    @Modifying
    @Query(
            """
            update Session s set s.revokedAt = :now, s.revokedReason = :reason
            where s.userId = :userId and s.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now, @Param("reason") String reason);

    /** As {@link #revokeAllForUser}, sparing one family: the device the person is using now. */
    @Modifying
    @Query(
            """
            update Session s set s.revokedAt = :now, s.revokedReason = :reason
            where s.userId = :userId and s.familyId <> :keepFamilyId and s.revokedAt is null
            """)
    int revokeAllForUserExcept(
            @Param("userId") UUID userId,
            @Param("keepFamilyId") UUID keepFamilyId,
            @Param("now") Instant now,
            @Param("reason") String reason);

    @Modifying
    @Query("delete from Session s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
