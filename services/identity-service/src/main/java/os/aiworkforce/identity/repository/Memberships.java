package os.aiworkforce.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.Membership;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Memberships extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserIdAndOrgId(UUID userId, UUID orgId);

    /** The person's membership in the workspace, only while it is active. */
    default Optional<Membership> findActive(UUID userId, UUID orgId) {
        return findByUserIdAndOrgId(userId, orgId).filter(Membership::isActive);
    }

    List<Membership> findByUserIdAndStatus(UUID userId, String status);

    List<Membership> findByOrgIdAndStatus(UUID orgId, String status);

    /**
     * How many people hold a given role in a workspace.
     *
     * <p>Used to refuse the removal of the last owner. A workspace with no owner cannot grant
     * anybody the authority to fix it, so the state is unrecoverable without a support
     * intervention - which is why it is prevented rather than repaired.
     */
    @Query(
            """
            select count(m) from Membership m
            where m.orgId = :orgId and m.roleId = :roleId and m.status = 'active'
            """)
    long countActiveWithRole(@Param("orgId") UUID orgId, @Param("roleId") UUID roleId);
}
