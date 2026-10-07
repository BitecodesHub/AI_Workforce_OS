package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface WorkspaceProviderSettings
        extends JpaRepository<WorkspaceProviderSetting, WorkspaceProviderSetting.Key> {

    List<WorkspaceProviderSetting> findByOrgId(UUID orgId);

    /**
     * Holds a transaction-scoped advisory lock on one workspace's provider settings.
     *
     * <p>Turning a provider off first checks that the routing policies keep another provider that
     * is on. Two requests that each turn off one of the last two would both pass that check if
     * they ran side by side; taking this lock first makes the second wait for the first to commit
     * and then see its change. Other workspaces never wait on it. Released when the transaction
     * ends. Wrapped in a select of a constant because {@code pg_advisory_xact_lock} returns
     * {@code void}, which has no Java type.
     */
    @Query(
            value =
                    """
            select 1 from (
                select pg_advisory_xact_lock(hashtext('workspace_provider_settings'), hashtext(cast(:orgId as text)))
            ) as held
            """,
            nativeQuery = true)
    Integer holdWorkspaceLock(@Param("orgId") UUID orgId);

    /**
     * Turns a provider on or off for one workspace. An atomic upsert, so a double click on the
     * toggle never breaks the primary key; the credential columns are left as they are.
     */
    @Modifying
    @Query(
            value =
                    """
            insert into workspace_provider_settings (org_id, provider_id, enabled, updated_at, updated_by)
            values (:orgId, :providerId, :enabled, :now, :updatedBy)
            on conflict (org_id, provider_id) do update set
                enabled = excluded.enabled,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """,
            nativeQuery = true)
    int upsertEnabled(
            @Param("orgId") UUID orgId,
            @Param("providerId") String providerId,
            @Param("enabled") boolean enabled,
            @Param("now") Instant now,
            @Param("updatedBy") String updatedBy);

    /**
     * Records that the provider refused this workspace's key. Only this workspace's row changes:
     * another workspace's key is a different key, and it may be perfectly good.
     */
    @Modifying
    @Query(
            value =
                    """
            insert into workspace_provider_settings
                (org_id, provider_id, credential_status, credential_checked_at, updated_at)
            values (:orgId, :providerId, 'rejected', :now, :now)
            on conflict (org_id, provider_id) do update set
                credential_status = 'rejected',
                credential_checked_at = excluded.credential_checked_at,
                updated_at = excluded.updated_at
            """,
            nativeQuery = true)
    int markCredentialRejected(
            @Param("orgId") UUID orgId, @Param("providerId") String providerId, @Param("now") Instant now);

    /**
     * Clears this workspace's recorded rejection once the provider has answered one of its calls.
     *
     * <p>A rejection is written the moment a provider refuses a key, and without this a key
     * replaced afterwards kept reading as refused for good. Scoped to the workspace that made the
     * call: a success with one workspace's key says nothing about another's. Only a rejected row
     * changes, so the usual case matches nothing and writes nothing.
     *
     * @return 1 when a rejection was cleared, otherwise 0
     */
    @Modifying
    @Query(
            """
            update WorkspaceProviderSetting s
            set s.credentialStatus = 'valid', s.credentialCheckedAt = :now, s.updatedAt = :now
            where s.orgId = :orgId and s.providerId = :providerId and s.credentialStatus = 'rejected'
            """)
    int clearRejectedCredential(
            @Param("orgId") UUID orgId, @Param("providerId") String providerId, @Param("now") Instant now);
}
