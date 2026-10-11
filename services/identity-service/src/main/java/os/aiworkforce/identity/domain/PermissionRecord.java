// @find: permission, permission code, permissions table, PermissionRecord, permission catalogue, role permissions list, administrative permission
// @what: JPA entity mirroring each permission code from the build registry into the database.
// @flow: Written by PermissionSeeder; RoleController.permissions serves the build registry instead.
package os.aiworkforce.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A permission code, mirrored into the database from the build's registry.
 *
 * <p>The row exists so the composition table ({@code role_permissions}) has something to
 * reference. The console's permission list comes from the build's registry, not from these rows.
 * The rows are not the source of truth: {@code PermissionSeeder} rewrites them at startup from
 * {@code Permission.ALL}, because a code that no endpoint checks grants nothing regardless of
 * what a row says. Getters expose the columns; the two setters are used when the seeder updates
 * a description or the administrative flag.
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

    /** Required by JPA; not for application code. */
    protected PermissionRecord() {}

    /**
     * Creates a row for one permission code, as {@code PermissionSeeder} does for a code that is
     * not yet in the table. The creation time is set to the current instant.
     */
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
