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

public interface Roles extends JpaRepository<Role, UUID> {

    @Query("select r from Role r where r.orgId is null and r.name = :name")
    Optional<Role> findSystemRole(@Param("name") String name);

    /** A workspace's own roles, plus the system roles it inherits. */
    @Query("select r from Role r where r.orgId = :orgId or r.orgId is null order by r.name")
    List<Role> findAvailableTo(@Param("orgId") UUID orgId);

    @Query("select r from Role r where r.orgId = :orgId and r.name = :name")
    Optional<Role> findByOrgAndName(@Param("orgId") UUID orgId, @Param("name") String name);

    @Query("select count(m) from Membership m where m.roleId = :roleId and m.status = 'active'")
    long countActiveHolders(@Param("roleId") UUID roleId);
}
