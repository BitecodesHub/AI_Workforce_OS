package os.aiworkforce.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.PasswordResetToken;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface PasswordResetTokens extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Marks a link used, if nobody has used it yet. Returns 1 for the caller that won.
     *
     * <p>A conditional update rather than a read followed by a write: two requests presenting the
     * same link at once would both read it as unused, and both would set a password.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /** Closes every open link for a person, so only the newest one issued can be redeemed. */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int closeOpenFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
