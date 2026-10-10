// @find: org scoped entity, tenant isolation, workspace id on every row, hibernate filter, row level security, multi tenant
// @what: Base class for rows that belong to exactly one workspace, with tenant filtering.
// @flow: Extended by workspace-owned entities in the services
package os.aiworkforce.platform.web.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * A row that belongs to exactly one workspace.
 *
 * <p>Tenant isolation is enforced three times over, deliberately. The repository filters by
 * organisation because that is the query the code means. The Hibernate filter below catches the
 * query somebody forgot to filter. Postgres row-level security catches the statement that never
 * went through Hibernate at all - a migration, a report, a console session.
 *
 * <p>Three layers is not paranoia here: a single missed {@code where} clause in a multi-tenant
 * system shows one customer another customer's data, and no amount of review reliably prevents
 * one across a codebase this size.
 */
@MappedSuperclass
@FilterDef(
        name = OrgScopedEntity.ORG_FILTER,
        parameters = @ParamDef(name = OrgScopedEntity.ORG_PARAM, type = UUID.class),
        defaultCondition = "org_id = :orgId")
@Filter(name = OrgScopedEntity.ORG_FILTER)
public abstract class OrgScopedEntity extends BaseEntity {

    public static final String ORG_FILTER = "orgFilter";
    public static final String ORG_PARAM = "orgId";

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }
}
