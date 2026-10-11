// @find: Spring Data repository for roles and name resolution.
// @what: roles, find role, system role, role by name, role name clash, role holders count, Roles repository
package os.aiworkforce.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.identity.domain.Role;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Spring Data repository for {@link Role} rows, both system roles (no workspace) and custom ones.
 *
 * <p>Used by {@code RoleController}, {@code MemberController}, {@code InternalMembershipController},
 * {@code PasswordResetService}, {@code PermissionSeeder}, {@code DemoDataSeeder} and
 * {@code AuthService}.
 */
public interface Roles extends JpaRepository<Role, UUID> {

    /**
     * The system role with this exact name, or empty. System roles have no workspace, so the name is
     * unique among them.
     */
    @Query("select r from Role r where r.orgId is null and r.name = :name")
    Optional<Role> findSystemRole(@Param("name") String name);

    /** A workspace's own roles, plus the system roles it inherits. */
    @Query("select r from Role r where r.orgId = :orgId or r.orgId is null order by r.name")
    List<Role> findAvailableTo(@Param("orgId") UUID orgId);

    /**
     * The workspace's own role with this exact name, or empty. System roles are not considered; see
     * {@link #findAvailable} for the combined lookup.
     */
    @Query("select r from Role r where r.orgId = :orgId and r.name = :name")
    Optional<Role> findByOrgAndName(@Param("orgId") UUID orgId, @Param("name") String name);

    /**
     * Every role this workspace can see whose name matches {@code name} ignoring case: its own and
     * the system ones. A custom role called "Owner" would otherwise show in the console exactly
     * like the built-in owner role while carrying something else entirely.
     */
    @Query("select r from Role r where (r.orgId = :orgId or r.orgId is null) and lower(r.name) = lower(:name)")
    java.util.List<Role> findNameClashes(@Param("orgId") UUID orgId, @Param("name") String name);

    /**
     * The role a workspace means by a name: its own role first, then the system role.
     *
     * <p>The same order everywhere a role is named rather than chosen by id - an invitation, a
     * grant check - so a name cannot resolve to one role in one place and another elsewhere.
     */
    default Optional<Role> findAvailable(UUID orgId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String stripped = name.strip();
        return findByOrgAndName(orgId, stripped).or(() -> findSystemRole(stripped));
    }

    /**
     * How many active memberships hold the role, across every workspace. Used to show a role's
     * holder count and to refuse deleting a role that is still in use.
     */
    @Query("select count(m) from Membership m where m.roleId = :roleId and m.status = 'active'")
    long countActiveHolders(@Param("roleId") UUID roleId);
}
