package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.WorkspaceModelAvailability;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface WorkspaceModelAvailabilities
        extends JpaRepository<WorkspaceModelAvailability, WorkspaceModelAvailability.Key> {

    List<WorkspaceModelAvailability> findByOrgId(UUID orgId);

    /**
     * Sets a model aside for one workspace until {@code until}. An atomic upsert, because two
     * runs in the same workspace can hit the same empty account at the same moment.
     *
     * <p>Never shortens a note still in force: a later note wins, with its cause and reason, and
     * an earlier one leaves the row as it is (and returns 0).
     */
    @Modifying
    @Query(
            value =
                    """
            insert into workspace_model_availability as w
                (org_id, provider_id, model_id, unavailable_until, cause, reason)
            values (:orgId, :providerId, :modelId, :until, :cause, :reason)
            on conflict (org_id, provider_id, model_id) do update set
                unavailable_until = excluded.unavailable_until,
                cause = excluded.cause,
                reason = excluded.reason
            where w.unavailable_until <= excluded.unavailable_until
            """,
            nativeQuery = true)
    int upsert(
            @Param("orgId") UUID orgId,
            @Param("providerId") String providerId,
            @Param("modelId") String modelId,
            @Param("until") Instant until,
            @Param("cause") String cause,
            @Param("reason") String reason);

    /**
     * The other workspaces with a note for this model, for this cause, still in force at
     * {@code now}. How one workspace's "this model does not exist" is told apart from two
     * workspaces saying the same.
     */
    @Query(
            """
            select distinct w.orgId from WorkspaceModelAvailability w
            where w.providerId = :providerId and w.modelId = :modelId and w.cause = :cause
              and w.orgId <> :orgId and w.unavailableUntil > :now
            """)
    List<UUID> findOtherWorkspacesReporting(
            @Param("providerId") String providerId,
            @Param("modelId") String modelId,
            @Param("cause") String cause,
            @Param("orgId") UUID orgId,
            @Param("now") Instant now);
}
