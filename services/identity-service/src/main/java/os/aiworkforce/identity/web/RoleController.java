// @find: roles, create role, edit role, delete role, custom role, permissions list, role permissions, /api/roles, Roles page, RoleController
// @what: REST endpoints to list, create, edit and delete roles and list permission codes.
// @flow: Uses GrantGuard, Roles, Permissions; backs the Roles page.
package os.aiworkforce.identity.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.service.GrantGuard;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.audit.AuditClient;

/**
 * Roles and the permissions they carry.
 *
 * <p>This is where the platform's claim that authorisation is data rather than code is made good.
 * A workspace can compose its own roles from the registered permission codes, and the change
 * takes effect on the next token - no deployment, no restart.
 *
 * <p>Composing a role is granting in advance, so it follows the same rule as granting one
 * ({@link GrantGuard}): a role may only carry permissions its author holds. Otherwise anyone with
 * role:create could build a role with everything in it and then hand it out.
 *
 * <p>Creating, changing and deleting a role are written to the audit log with the permissions
 * involved - for a change, the permissions added and removed - because a role's contents decide what
 * everyone who holds it can do.
 */
@RestController
@RequestMapping("/api/roles")
@Tag(name = "Roles")
public class RoleController {

    private final Roles roles;
    private final Memberships memberships;
    private final GrantGuard guard;
    private final AuditClient audit;

    public RoleController(Roles roles, Memberships memberships, GrantGuard guard, AuditClient audit) {
        this.roles = roles;
        this.memberships = memberships;
        this.guard = guard;
        this.audit = audit;
    }

    public record RoleView(
            UUID id,
            String name,
            String description,
            boolean system,
            long permissionVersion,
            Set<String> permissions,
            long holders) {}

    public record PermissionView(
            String code, String resource, String action, String description, boolean administrative) {}

    public record SaveRoleRequest(
            @NotBlank @Size(max = 60) String name, @Size(max = 300) String description, Set<String> permissions) {}

    // @find: list roles, GET /api/roles
    @GetMapping
    @RequiresPermission(Permission.Codes.ROLE_READ)
    @Operation(summary = "List the roles available in this workspace")
    public List<RoleView> list() {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        return roles.findAvailableTo(orgId).stream().map(this::toView).toList();
    }

    /*
    // @find: list permissions, permission catalogue, GET /api/roles/permissions
     * WORKSPACE_READ, not ROLE_READ: this is the compile-time permission registry - the same
     * catalogue every role's own description is drawn from - not workspace data, and not
     * sensitive. Gating it behind ROLE_READ meant the profile page's "what your role allows"
     * (which every signed-in person can otherwise open) rendered silently empty for any role
     * without role management access, which is most of them.
     */
    @GetMapping("/permissions")
    @RequiresPermission(Permission.Codes.WORKSPACE_READ)
    @Operation(summary = "Every permission a role may be given")
    public List<PermissionView> permissions() {
        // Served from the build's registry rather than the table: these are the codes the running
        // code actually checks, which is the only list that means anything. Planned codes are
        // left out for the same reason - nothing checks them yet, so a checkbox for one would
        // grant nothing.
        return Permission.available().stream()
                .map(p -> new PermissionView(p.code(), p.resource(), p.action(), p.description(), p.administrative()))
                .toList();
    }

    // @find: create role, new custom role, POST /api/roles
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.ROLE_CREATE)
    @Transactional
    @Operation(summary = "Create a role for this workspace")
    public RoleView create(@Valid @RequestBody SaveRoleRequest request) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        validate(request.permissions());
        guard.assertCanCompose(guard.caller(RequestContext.requireActor()), request.permissions());

        // Looked up as it will be stored, so " Manager" cannot slip past the check for "Manager".
        String name = request.name().strip();
        // Case is ignored: "Owner" or "ADMIN" would read exactly like the built-in role.
        if (!roles.findNameClashes(orgId, name).isEmpty()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "A role with that name already exists.");
        }

        Role role = new Role();
        role.setOrgId(orgId);
        role.setName(name);
        role.setDescription(
                request.description() == null ? "" : request.description().strip());
        role.setSystem(false);
        role.setPermissions(new LinkedHashSet<>(request.permissions()));
        Role saved = roles.save(role);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", saved.getName());
        detail.put("permissions", new ArrayList<>(new TreeSet<>(saved.getPermissions())));
        audit.record("role.create", "role", saved.getId().toString(), "succeeded", detail);
        return toView(saved);
    }

    // @find: update role, edit role permissions, PUT /api/roles/{roleId}
    @PutMapping("/{roleId}")
    @RequiresPermission(Permission.Codes.ROLE_UPDATE)
    @Transactional
    @Operation(summary = "Change the permissions a role carries")
    public RoleView update(@PathVariable UUID roleId, @Valid @RequestBody SaveRoleRequest request) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Role role = roles.findById(roleId).orElseThrow(() -> ApiException.notFound("role", roleId));

        // A system role is shared by every workspace on the platform. Editing one here would
        // change what "manager" means for everybody, so a workspace copies it instead.
        if (role.isSystem()) {
            throw new ApiException(
                    ErrorCode.IMMUTABLE_RESOURCE, "Built-in roles cannot be changed. Create a workspace role instead.");
        }
        if (!orgId.equals(role.getOrgId())) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH);
        }
        validate(request.permissions());
        guardEdit(guard.caller(RequestContext.requireActor()), role, request.permissions());

        // Renaming onto a name another role already uses is refused the same way creating one
        // is, rather than left to the unique index to reject as a bare conflict.
        String name = request.name().strip();
        boolean taken = roles.findNameClashes(orgId, name).stream()
                .anyMatch(other -> !other.getId().equals(role.getId()));
        if (taken) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "A role with that name already exists.");
        }

        String previousName = role.getName();
        Set<String> before = new TreeSet<>(role.getPermissions());
        role.setName(name);
        role.setDescription(
                request.description() == null ? "" : request.description().strip());
        // replacePermissions bumps the permission version. Nothing rejects an older token on that
        // version yet, so the change - narrowing included - reaches each holder at their next
        // refresh, within the access-token lifetime, when the role's permissions are read afresh.
        role.replacePermissions(new LinkedHashSet<>(request.permissions()));
        Role saved = roles.save(role);

        Set<String> after = new TreeSet<>(saved.getPermissions());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", saved.getName());
        if (!previousName.equals(saved.getName())) {
            detail.put("previousName", previousName);
        }
        detail.put("added", difference(after, before));
        detail.put("removed", difference(before, after));
        detail.put("holders", roles.countActiveHolders(saved.getId()));
        audit.record("role.update", "role", saved.getId().toString(), "succeeded", detail);
        return toView(saved);
    }

    // @find: delete role, DELETE /api/roles/{roleId}
    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.ROLE_DELETE)
    @Transactional
    @Operation(summary = "Delete a role that nobody holds")
    public void delete(@PathVariable UUID roleId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Role role = roles.findById(roleId).orElseThrow(() -> ApiException.notFound("role", roleId));

        if (role.isSystem()) {
            throw new ApiException(ErrorCode.IMMUTABLE_RESOURCE, "Built-in roles cannot be deleted.");
        }
        if (!orgId.equals(role.getOrgId())) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH);
        }
        long holders = roles.countActiveHolders(roleId);
        if (holders > 0) {
            // Deleting it would leave those people with a dangling role and no permissions at
            // all, which reads as a platform fault rather than as an administrator's decision.
            throw new ApiException(ErrorCode.RESOURCE_IN_USE).with("holders", holders);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", role.getName());
        detail.put("permissions", new ArrayList<>(new TreeSet<>(role.getPermissions())));
        roles.delete(role);
        audit.record("role.delete", "role", roleId.toString(), "succeeded", detail);
    }

    /** What is in {@code from} and not in {@code minus}, in order, as a list the audit log can store. */
    private static List<String> difference(Set<String> from, Set<String> minus) {
        List<String> only = new ArrayList<>();
        for (String code : from) {
            if (!minus.contains(code)) {
                only.add(code);
            }
        }
        return only;
    }

    /**
     * Refuses an edit the caller is not entitled to make.
     *
     * <p>Three ways an edit could widen somebody's authority, each refused: a role that already
     * carries permissions the caller lacks (narrowing it would still decide about authority they
     * do not hold), a new set with permissions the caller lacks, and a role the caller holds
     * themselves gaining anything at all.
     */
    private void guardEdit(GrantGuard.Grantor caller, Role role, Set<String> codes) {
        if (!role.isWithin(caller.permissions())) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "This role includes permissions you do not have, so you cannot change it.")
                    .with("reason", GrantGuard.EXCEEDS_GRANTOR);
        }
        if (role.getId().equals(caller.roleId()) && !role.getPermissions().containsAll(codes)) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED, "You cannot add permissions to a role you hold yourself.")
                    .with("reason", GrantGuard.EXCEEDS_GRANTOR);
        }
        guard.assertCanCompose(caller, codes);
    }

    /**
     * Refuses a permission code this build does not implement.
     *
     * <p>Without the check, a typo produces a role that looks correct in the console and grants
     * nothing at all - a failure that surfaces as "the button does not work for Priya" weeks
     * later.
     */
    private void validate(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw ApiException.validation("permissions", "a role must carry at least one permission");
        }
        List<String> unknown = codes.stream()
                .filter(code -> !Permission.isKnown(code))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw ApiException.validation("permissions", "unknown permission code(s): " + String.join(", ", unknown));
        }
    }

    private RoleView toView(Role role) {
        return new RoleView(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                role.getPermissionVersion(),
                Set.copyOf(role.getPermissions()),
                roles.countActiveHolders(role.getId()));
    }
}
