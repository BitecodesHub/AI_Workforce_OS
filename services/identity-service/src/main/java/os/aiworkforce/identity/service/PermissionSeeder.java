package os.aiworkforce.identity.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.identity.domain.PermissionRecord;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.repository.Permissions;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Brings the database in line with the permissions this build understands.
 *
 * <p>Two different jobs, with deliberately different rules:
 *
 * <ul>
 *   <li><b>Permission codes are replaced.</b> They are owned by the build, so the seeder inserts
 *       what is new and updates descriptions that changed. A code that no longer exists in the
 *       build is left in place rather than deleted, because deleting it would cascade through
 *       {@code role_permissions} and silently strip a permission from every role that held it.
 *       It is logged instead, for a human to remove deliberately.
 *   <li><b>System roles are created once.</b> If {@code manager} already exists, its composition
 *       is left exactly as it is. An operator who narrowed a role during an incident would
 *       otherwise find it widened again by the next deployment - which is the sort of surprise
 *       that makes people stop trusting the console.
 * </ul>
 */
@Component
public class PermissionSeeder {

    private static final Logger log = LoggerFactory.getLogger(PermissionSeeder.class);

    private final Permissions permissions;
    private final Roles roles;

    public PermissionSeeder(Permissions permissions, Roles roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(10)
    @Transactional
    public void seed() {
        seedPermissions();
        seedSystemRoles();
    }

    private void seedPermissions() {
        Map<String, PermissionRecord> existing =
                permissions.findAll().stream().collect(java.util.stream.Collectors.toMap(
                        PermissionRecord::getCode, record -> record));

        int inserted = 0;
        int updated = 0;
        for (Permission permission : Permission.ALL) {
            PermissionRecord record = existing.get(permission.code());
            if (record == null) {
                permissions.save(new PermissionRecord(
                        permission.code(), permission.resource(), permission.action(),
                        permission.description(), permission.administrative()));
                inserted++;
            } else if (!record.getDescription().equals(permission.description())
                    || record.isAdministrative() != permission.administrative()) {
                record.setDescription(permission.description());
                record.setAdministrative(permission.administrative());
                updated++;
            }
        }

        // Never deleted automatically: the cascade would strip the code from every role holding
        // it, which is a permission change nobody asked for and nobody would see.
        List<String> orphans = existing.keySet().stream()
                .filter(code -> !Permission.isKnown(code))
                .sorted()
                .toList();
        if (!orphans.isEmpty()) {
            log.warn(
                    "{} permission code(s) exist in the database but not in this build and were left "
                            + "untouched: {}",
                    orphans.size(),
                    String.join(", ", orphans));
        }

        if (inserted > 0 || updated > 0) {
            log.info("Permission registry synchronised: {} added, {} updated", inserted, updated);
        }
    }

    /**
     * The five roles a workspace starts with.
     *
     * <p>Chosen so that the common cases need no configuration at all, and so that every
     * permission that can widen somebody's authority sits above {@code manager}. A manager can run
     * the workforce; only an administrator can change who is allowed to.
     */
    private void seedSystemRoles() {
        ensureRole("owner", "Full control of the workspace, including billing and closure.",
                Permission.ALL.stream().map(Permission::code).collect(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        ensureRole("admin", "Manages people, agents, integrations and settings.", codes(
                Permission.WORKSPACE_READ, Permission.WORKSPACE_UPDATE,
                Permission.MEMBER_READ, Permission.MEMBER_INVITE, Permission.MEMBER_UPDATE,
                Permission.MEMBER_REMOVE, Permission.ROLE_READ, Permission.ROLE_CREATE,
                Permission.ROLE_UPDATE, Permission.ROLE_DELETE,
                Permission.API_KEY_READ, Permission.API_KEY_MANAGE,
                Permission.AGENT_READ, Permission.AGENT_CREATE, Permission.AGENT_UPDATE,
                Permission.AGENT_DELETE, Permission.AGENT_RUN, Permission.AGENT_GRANT_TOOLS,
                Permission.AGENT_SET_MODEL_POLICY, Permission.AGENT_SET_APPROVAL_POLICY,
                Permission.TASK_READ, Permission.TASK_CREATE, Permission.TASK_CANCEL,
                Permission.RUN_READ, Permission.RUN_CANCEL, Permission.RUN_REPLAY, Permission.CHAT_USE,
                Permission.APPROVAL_READ, Permission.APPROVAL_DECIDE,
                Permission.KNOWLEDGE_READ, Permission.KNOWLEDGE_QUERY, Permission.KNOWLEDGE_SOURCE_MANAGE,
                Permission.INTEGRATION_READ, Permission.INTEGRATION_CONNECT, Permission.INTEGRATION_DISCONNECT,
                Permission.PROVIDER_READ, Permission.PROVIDER_MANAGE,
                Permission.BUDGET_READ, Permission.BUDGET_MANAGE,
                Permission.AUDIT_READ, Permission.ANALYTICS_READ,
                Permission.SETTINGS_READ, Permission.SETTINGS_UPDATE,
                Permission.MEMORY_READ, Permission.MEMORY_PURGE));

        // A manager runs the workforce and approves its actions, but cannot change who else may.
        ensureRole("manager", "Runs agents, assigns work and approves actions.", codes(
                Permission.WORKSPACE_READ, Permission.MEMBER_READ, Permission.ROLE_READ,
                Permission.AGENT_READ, Permission.AGENT_CREATE, Permission.AGENT_UPDATE,
                Permission.AGENT_RUN, Permission.AGENT_SET_MODEL_POLICY,
                Permission.TASK_READ, Permission.TASK_CREATE, Permission.TASK_CANCEL,
                Permission.RUN_READ, Permission.RUN_CANCEL, Permission.CHAT_USE,
                Permission.APPROVAL_READ, Permission.APPROVAL_DECIDE,
                Permission.KNOWLEDGE_READ, Permission.KNOWLEDGE_QUERY, Permission.KNOWLEDGE_SOURCE_MANAGE,
                Permission.INTEGRATION_READ, Permission.PROVIDER_READ, Permission.BUDGET_READ,
                Permission.ANALYTICS_READ, Permission.SETTINGS_READ, Permission.MEMORY_READ));

        ensureRole("employee", "Asks questions and hands routine work to agents.", codes(
                Permission.WORKSPACE_READ, Permission.MEMBER_READ,
                Permission.AGENT_READ, Permission.AGENT_RUN,
                Permission.TASK_READ, Permission.TASK_CREATE,
                Permission.RUN_READ, Permission.CHAT_USE,
                Permission.KNOWLEDGE_READ, Permission.KNOWLEDGE_QUERY,
                Permission.INTEGRATION_READ, Permission.APPROVAL_READ));

        ensureRole("viewer", "Reads dashboards and traces without changing anything.", codes(
                Permission.WORKSPACE_READ, Permission.MEMBER_READ, Permission.AGENT_READ,
                Permission.TASK_READ, Permission.RUN_READ, Permission.KNOWLEDGE_READ,
                Permission.INTEGRATION_READ, Permission.ANALYTICS_READ));
    }

    private static Set<String> codes(Permission... permissions) {
        Set<String> set = new LinkedHashSet<>();
        for (Permission permission : permissions) {
            set.add(permission.code());
        }
        return set;
    }

    private void ensureRole(String name, String description, Set<String> permissionCodes) {
        if (roles.findSystemRole(name).isPresent()) {
            return;
        }
        Role role = new Role();
        role.setOrgId(null);
        role.setName(name);
        role.setDescription(description);
        role.setSystem(true);
        role.setPermissions(new LinkedHashSet<>(permissionCodes));
        roles.save(role);
        log.info("Created system role '{}' with {} permission(s)", name, permissionCodes.size());
    }
}
