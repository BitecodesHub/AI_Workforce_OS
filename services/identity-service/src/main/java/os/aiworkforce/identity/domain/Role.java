package os.aiworkforce.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * A named set of permissions.
 *
 * <p>Roles are data. A workspace can create {@code reviewer}, give it exactly
 * {@code approval:read} and {@code approval:decide}, and assign it - with no deployment, no code
 * change and no restart. That is the flexibility the permission registry in the build was
 * deliberately not trying to provide.
 */
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

    /** Null for a system role available to every workspace. */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description = "";

    @Column(name = "is_system", nullable = false)
    private boolean system;

    /**
     * Incremented whenever the permission set changes.
     *
     * <p>Access tokens carry the version they were minted under. A token holding an older version
     * is refused, so narrowing a role takes effect immediately rather than after every existing
     * token has expired - which is the difference between revoking access and scheduling it.
     */
    @Column(name = "permission_version", nullable = false)
    private long permissionVersion = 1;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", nullable = false)
    private Set<String> permissions = new LinkedHashSet<>();

    public void replacePermissions(Set<String> codes) {
        if (!permissions.equals(codes)) {
            permissions = new LinkedHashSet<>(codes);
            permissionVersion++;
        }
    }

    public boolean isPlatformWide() {
        return orgId == null;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public long getPermissionVersion() {
        return permissionVersion;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }
}
