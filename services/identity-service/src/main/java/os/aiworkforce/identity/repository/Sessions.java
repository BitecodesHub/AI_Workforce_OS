// @find: Spring Data repository for refresh-token sessions and bulk revocation.
// @what: sessions, refresh token lookup, revoke family, revoke all sessions, sign out everywhere, live sessions, which sessions are still signed in, delete expired sessions, Sessions repository
package os.aiworkforce.identity.repository;

import java.time.Instant;
import java.util.Collection;
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

/**
 * Spring Data repository for {@link Session} rows, the links of refresh-token families.
 *
 * <p>Used by {@code AuthService} for sign-in, refresh, sign-out and the session list, and by
 * {@code SessionSweep} for housekeeping. The bulk updates here run in the caller's transaction and
 * return the number of rows changed.
 */
public interface Sessions extends JpaRepository<Session, UUID> {

    /**
     * Looks a session up by the SHA-256 hash of the refresh token presented. Empty when the token
     * is unknown, which the caller treats as an invalid token.
     */
    Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

    /** Every session of a person that has not been revoked, including used and expired links. */
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

    /**
     * Which of the given session links still belong to a signed-in device: their family has a link
     * that is neither used, revoked nor expired, and the account is active. A link of a family that
     * was signed out (there, from another device, everywhere, or by a password change or reset),
     * one of a disabled account, and one no longer known are all left out. Read by the
     * orchestrator before it sends a push notification to the device that link saved.
     */
    @Query(
            """
            select s.id from Session s
            where s.id in :ids
              and exists (select l.id from Session l
                          where l.familyId = s.familyId
                            and l.revokedAt is null and l.usedAt is null and l.expiresAt > :now)
              and exists (select u.id from User u where u.id = s.userId and u.status = 'active')
            """)
    List<UUID> findSignedInIds(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** When each family began, which is when that device signed in. Rows are {familyId, issuedAt}. */
    @Query(
            """
            select s.familyId, min(s.issuedAt) from Session s
            where s.userId = :userId and s.familyId in :familyIds
            group by s.familyId
            """)
    List<Object[]> findFamilyStarts(@Param("userId") UUID userId, @Param("familyIds") List<UUID> familyIds);

    /**
     * Whether the family belongs to the person. Used to stop one person revoking another's device.
     */
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

    /**
     * Deletes every session that expired before the given instant and returns the count. Called by
     * {@code SessionSweep}, which passes a cut-off a week in the past so recently expired rows remain.
     */
    @Modifying
    @Query("delete from Session s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
