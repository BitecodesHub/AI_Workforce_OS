// @find: oauth states repository, find pending sign in state, expire old states, database access
// @what: Spring Data repository for pending OAuth sign-ins.
// @flow: Used by OAuthService
package os.aiworkforce.integrations.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.integrations.domain.OAuthState;

public interface OAuthStates extends JpaRepository<OAuthState, UUID> {

    /**
     * Claims a state for use: succeeds (returns 1) for the first caller only, and only while it
     * has not expired. A second request with the same state, or a late one, gets 0.
     */
    @Modifying
    @Transactional
    @Query(
            """
            update OAuthState s set s.usedAt = :now
            where s.id = :id and s.usedAt is null and s.expiresAt > :now
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from OAuthState s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
