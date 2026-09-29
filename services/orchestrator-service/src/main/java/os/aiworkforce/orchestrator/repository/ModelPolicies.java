package os.aiworkforce.orchestrator.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface ModelPolicies extends JpaRepository<ModelPolicyEntity, UUID> {

    Optional<ModelPolicyEntity> findByOrgIdAndAgentId(UUID orgId, UUID agentId);

    @Query("select p from ModelPolicyEntity p where p.orgId = :orgId and p.agentId is null")
    Optional<ModelPolicyEntity> findWorkspaceDefault(@Param("orgId") UUID orgId);
}
