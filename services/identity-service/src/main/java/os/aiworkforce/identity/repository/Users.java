// @find: Spring Data repository for user accounts.
// @what: users, find user by email, email exists, find users by id, Users repository
package os.aiworkforce.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.User;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Spring Data repository for {@link User} accounts.
 *
 * <p>Used by {@code AuthService} (registration and sign-in), {@code PasswordResetService},
 * {@code MemberController}, {@code InternalMembershipController} and {@code DemoDataSeeder}.
 */
public interface Users extends JpaRepository<User, UUID> {

    /** Case-insensitive, because people do not type their address the same way twice. */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    /**
     * Whether an account exists for the address, compared without regard to case. Used to reject a
     * duplicate registration.
     */
    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    /**
     * The accounts with the given identifiers, in no particular order. Used to resolve the people
     * behind a list of memberships in one query rather than one per member.
     */
    List<User> findByIdIn(List<UUID> ids);
}
