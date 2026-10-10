// @find: test fixture for grant guard, roles and members setup, owner admin manager fixtures
// @what: Shared test fixture building roles and members for grant tests.
package os.aiworkforce.identity.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.rbac.Permission;

/**
 * A workspace in memory: the five system roles as the seeder builds them, its members, and
 * repositories answering from those maps - enough to put the grant rules through every path that
 * hands out a role without a database.
 */
public final class GrantFixture {

    public static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    public final Roles roles = mock(Roles.class);
    public final Memberships memberships = mock(Memberships.class);
    public final Users users = mock(Users.class);
    public final GrantGuard guard;

    public final Role owner;
    public final Role admin;
    public final Role manager;
    public final Role employee;

    private final Map<UUID, Role> roleById = new LinkedHashMap<>();
    private final Map<String, Membership> membershipByKey = new LinkedHashMap<>();
    private final Map<UUID, User> userById = new LinkedHashMap<>();

    public GrantFixture() {
        Set<String> all = codes(Permission.ALL);
        owner = role(null, "owner", true, all);
        Set<String> adminCodes = new LinkedHashSet<>(all);
        adminCodes.remove(Permission.Codes.WORKSPACE_DELETE);
        admin = role(null, "admin", true, adminCodes);
        manager = role(
                null,
                "manager",
                true,
                Set.of(
                        Permission.Codes.WORKSPACE_READ,
                        Permission.Codes.MEMBER_READ,
                        Permission.Codes.ROLE_READ,
                        Permission.Codes.AGENT_READ,
                        Permission.Codes.AGENT_RUN,
                        Permission.Codes.TASK_READ,
                        Permission.Codes.TASK_CREATE,
                        Permission.Codes.APPROVAL_DECIDE));
        employee = role(
                null,
                "employee",
                true,
                Set.of(Permission.Codes.WORKSPACE_READ, Permission.Codes.MEMBER_READ, Permission.Codes.CHAT_USE));

        stubRoles();
        stubMemberships();
        stubUsers();
        guard = new GrantGuard(roles, memberships);
    }

    /** A role this workspace made for itself. */
    public Role customRole(String name, String... codes) {
        return role(ORG, name, false, new LinkedHashSet<>(List.of(codes)));
    }

    /** A person with an account, a membership at {@code role} and the given status. */
    public UUID member(String name, Role role, String status) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName(name);
        user.setEmail(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '.') + "@example.test");
        userById.put(user.getId(), user);

        Membership membership = new Membership();
        membership.setUserId(user.getId());
        membership.setOrgId(ORG);
        membership.setRoleId(role.getId());
        membership.setStatus(status);
        membership.setJoinedAt(Instant.parse("2026-01-05T09:00:00Z"));
        membershipByKey.put(key(user.getId(), ORG), membership);
        return user.getId();
    }

    /** A person with an account but no membership here. */
    public UUID stranger(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName(name);
        user.setEmail(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '.') + "@example.test");
        userById.put(user.getId(), user);
        return user.getId();
    }

    public Membership membershipOf(UUID userId) {
        return membershipByKey.get(key(userId, ORG));
    }

    public User user(UUID userId) {
        return userById.get(userId);
    }

    /** The token a member holds right now: their role's permissions, in this workspace. */
    public Actor tokenOf(UUID userId) {
        Membership membership = membershipOf(userId);
        Role role = roleById.get(membership.getRoleId());
        return Actor.user(
                userId.toString(),
                ORG.toString(),
                role.getId().toString(),
                role.getPermissions(),
                role.getPermissionVersion());
    }

    /** A sibling service calling an internal endpoint. */
    public static Actor service() {
        return new Actor("organisation", Actor.Kind.SYSTEM, null, null, Set.of(), 0L, null, null, null, Map.of());
    }

    private Role role(UUID orgId, String name, boolean system, Set<String> codes) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setOrgId(orgId);
        role.setName(name);
        role.setDescription(name);
        role.setSystem(system);
        role.setPermissions(new LinkedHashSet<>(codes));
        roleById.put(role.getId(), role);
        return role;
    }

    private void stubRoles() {
        when(roles.findById(any())).thenAnswer(call -> Optional.ofNullable(roleById.get(call.<UUID>getArgument(0))));
        when(roles.findSystemRole(anyString()))
                .thenAnswer(call -> roleById.values().stream()
                        .filter(role -> role.getOrgId() == null && role.getName().equals(call.getArgument(0)))
                        .findFirst());
        when(roles.findByOrgAndName(any(), anyString()))
                .thenAnswer(call -> roleById.values().stream()
                        .filter(role -> Objects.equals(role.getOrgId(), call.getArgument(0))
                                && role.getName().equals(call.getArgument(1)))
                        .findFirst());
        when(roles.findNameClashes(any(), anyString()))
                .thenAnswer(call -> roleById.values().stream()
                        .filter(role -> (role.getOrgId() == null || Objects.equals(role.getOrgId(), call.getArgument(0)))
                                && role.getName().equalsIgnoreCase(call.getArgument(1)))
                        .toList());
        when(roles.findAvailable(any(), any())).thenCallRealMethod();
        when(roles.findAvailableTo(any()))
                .thenAnswer(call -> roleById.values().stream()
                        .filter(role -> role.getOrgId() == null || role.getOrgId().equals(call.getArgument(0)))
                        .toList());
        when(roles.findAllById(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().map(roleById::get).filter(Objects::nonNull).toList();
        });
        when(roles.countActiveHolders(any()))
                .thenAnswer(call -> membershipByKey.values().stream()
                        .filter(m -> m.isActive() && m.getRoleId().equals(call.getArgument(0)))
                        .count());
        when(roles.save(any())).thenAnswer(call -> {
            Role role = call.getArgument(0);
            roleById.put(role.getId(), role);
            return role;
        });
    }

    private void stubMemberships() {
        when(memberships.findByUserIdAndOrgId(any(), any()))
                .thenAnswer(call -> Optional.ofNullable(membershipByKey.get(key(call.getArgument(0), call.getArgument(1)))));
        when(memberships.findActive(any(), any())).thenCallRealMethod();
        when(memberships.findByUserIdAndStatus(any(), anyString()))
                .thenAnswer(call -> membershipByKey.values().stream()
                        .filter(m -> m.getUserId().equals(call.getArgument(0))
                                && m.getStatus().equals(call.getArgument(1)))
                        .toList());
        when(memberships.findByOrgIdAndStatus(any(), anyString()))
                .thenAnswer(call -> membershipByKey.values().stream()
                        .filter(m -> m.getOrgId().equals(call.getArgument(0))
                                && m.getStatus().equals(call.getArgument(1)))
                        .toList());
        when(memberships.countActiveWithRole(any(), any()))
                .thenAnswer(call -> membershipByKey.values().stream()
                        .filter(m -> m.isActive()
                                && m.getOrgId().equals(call.getArgument(0))
                                && m.getRoleId().equals(call.getArgument(1)))
                        .count());
        when(memberships.save(any())).thenAnswer(call -> {
            Membership membership = call.getArgument(0);
            membershipByKey.put(key(membership.getUserId(), membership.getOrgId()), membership);
            return membership;
        });
    }

    private void stubUsers() {
        when(users.findById(any())).thenAnswer(call -> Optional.ofNullable(userById.get(call.<UUID>getArgument(0))));
        when(users.findByIdIn(any())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(0);
            return ids.stream().map(userById::get).filter(Objects::nonNull).toList();
        });
        // Case-insensitive, as the real query is.
        when(users.findByEmail(anyString())).thenAnswer(call -> userById.values().stream()
                .filter(user -> user.getEmail().equalsIgnoreCase(call.<String>getArgument(0)))
                .findFirst());
    }

    private static String key(Object userId, Object orgId) {
        return userId + "/" + orgId;
    }

    private static Set<String> codes(Collection<Permission> permissions) {
        return permissions.stream().map(Permission::code).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
