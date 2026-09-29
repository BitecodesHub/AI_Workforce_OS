package os.aiworkforce.identity.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Roles and the permissions they carry.
 *
 * <p>This is where the platform's claim that authorisation is data rather than code is made good.
 * A workspace can compose its own roles from the registered permission codes, and the change
 * takes effect on the next token - no deployment, no restart.
 */
@RestController
@RequestMapping("/api/roles")
@Tag(name = "Roles")
public class RoleController {

    private final Roles roles;
    private final Memberships memberships;

    public RoleController(Roles roles, Memberships memberships) {
        this.roles = roles;
        this.memberships = memberships;
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

    @GetMapping
    @RequiresPermission(Permission.Codes.ROLE_READ)
    @Operation(summary = "List the roles available in this workspace")
    public List<RoleView> list() {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        return roles.findAvailableTo(orgId).stream().map(this::toView).toList();
    }

    /*
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
        // code actually checks, which is the only list that means anything.
        return Permission.ALL.stream()
                .map(p -> new PermissionView(p.code(), p.resource(), p.action(), p.description(), p.administrative()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.ROLE_CREATE)
    @Transactional
    @Operation(summary = "Create a role for this workspace")
    public RoleView create(@Valid @RequestBody SaveRoleRequest request) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        validate(request.permissions());

        // Looked up as it will be stored, so " Manager" cannot slip past the check for "Manager".
        String name = request.name().strip();
        if (roles.findByOrgAndName(orgId, name).isPresent()
                || roles.findSystemRole(name).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "A role with that name already exists.");
        }

        Role role = new Role();
        role.setOrgId(orgId);
        role.setName(name);
        role.setDescription(
                request.description() == null ? "" : request.description().strip());
        role.setSystem(false);
        role.setPermissions(new LinkedHashSet<>(request.permissions()));
        return toView(roles.save(role));
    }

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

        // Renaming onto a name another role already uses is refused the same way creating one
        // is, rather than left to the unique index to reject as a bare conflict.
        String name = request.name().strip();
        boolean taken = roles.findByOrgAndName(orgId, name)
                        .filter(other -> !other.getId().equals(role.getId()))
                        .isPresent()
                || roles.findSystemRole(name).isPresent();
        if (taken) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "A role with that name already exists.");
        }

        role.setName(name);
        role.setDescription(
                request.description() == null ? "" : request.description().strip());
        // replacePermissions bumps the permission version, which invalidates every token already
        // issued under this role. That is what makes narrowing a role take effect at once.
        role.replacePermissions(new LinkedHashSet<>(request.permissions()));
        return toView(roles.save(role));
    }

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
        roles.delete(role);
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
