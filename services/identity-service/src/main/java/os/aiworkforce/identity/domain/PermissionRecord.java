// @find: permission, permission code, permissions table, PermissionRecord, permission catalogue, role permissions list, administrative permission
// @what: JPA entity mirroring each permission code from the build registry into the database.
// @flow: Written by PermissionSeeder; read by RoleController.permissions.
package os.aiworkforce.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A permission code, mirrored into the database from the build's registry.
 *
 * <p>The row exists so the console can list what a role may contain and so the composition table
 * has something to reference. It is not the source of truth: {@code PermissionSeeder} rewrites
 * these rows at startup from {@code Permission.ALL}, because a code that no endpoint checks
 * grants nothing regardless of what a row says.
 */
@Entity
@Table(name = "permissions")
public class PermissionRecord {

    @Id
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String resource;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean administrative;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PermissionRecord() {}

    public PermissionRecord(String code, String resource, String action, String description, boolean administrative) {
        this.code = code;
        this.resource = resource;
        this.action = action;
        this.description = description;
        this.administrative = administrative;
    }

    public String getCode() {
        return code;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAdministrative() {
        return administrative;
    }

    public void setAdministrative(boolean administrative) {
        this.administrative = administrative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
