// @find: model policies repository, find routing policy, workspace default policy, policy for agent, ModelPolicies
// @what: Spring Data repository for ModelPolicyEntity rows.
// @flow: Used by the model router and the model policy settings.
// @find: model policies repository, find routing policy, workspace default policy, policy for agent, ModelPolicies
// @what: Spring Data repository for ModelPolicyEntity rows.
// @flow: Used by the model router and the model policy settings.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
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

    // @find: find policy for an agent
    // @find: find policy for an agent
    Optional<ModelPolicyEntity> findByOrgIdAndAgentId(UUID orgId, UUID agentId);

    // @find: find workspace default routing policy
    // @find: find workspace default routing policy
    @Query("select p from ModelPolicyEntity p where p.orgId = :orgId and p.agentId is null")
    Optional<ModelPolicyEntity> findWorkspaceDefault(@Param("orgId") UUID orgId);

    // @find: list all policies with candidates
    // @find: list all policies with candidates
    /**
     * Every routing policy in a workspace - the default and each agent's own - with its candidates,
     * in one query. For a check that must look at all of them, such as whether switching a
     * provider off would leave any policy with nowhere to go, which otherwise reads one policy per
     * agent.
     */
    @Query("select distinct p from ModelPolicyEntity p left join fetch p.candidates where p.orgId = :orgId")
    List<ModelPolicyEntity> findByOrgId(@Param("orgId") UUID orgId);
}
