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

/**
 * Spring Data repository for {@link OAuthState}, the pending OAuth sign-ins.
 *
 * <p>Used by {@code OAuthService}: {@code start} saves a row and clears old ones, and {@code callback}
 * reads the row and claims it exactly once.
 */
public interface OAuthStates extends JpaRepository<OAuthState, UUID> {

    /**
     * Claims a state for use: succeeds (returns 1) for the first caller only, and only while it
     * has not expired. A second request with the same state, or a late one, gets 0.
     *
     * <p>It is one conditional {@code UPDATE} run in its own transaction, so the database decides the
     * winner when two callbacks arrive together. Called by {@code OAuthService.callback} before any
     * token exchange.
     *
     * @param id the state row to claim
     * @param now the current time, compared with the expiry
     * @return 1 when this call claimed the row, otherwise 0
     */
    @Modifying
    @Transactional
    @Query(
            """
            update OAuthState s set s.usedAt = :now
            where s.id = :id and s.usedAt is null and s.expiresAt > :now
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Removes states that expired before the given moment, whether used or not.
     *
     * <p>Called in passing by {@code OAuthService.start}, which passes a cutoff one day in the past, so
     * that no separate cleanup job is needed. Failures there are logged and ignored.
     *
     * @param before rows whose expiry is earlier than this are deleted
     * @return the number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("delete from OAuthState s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
