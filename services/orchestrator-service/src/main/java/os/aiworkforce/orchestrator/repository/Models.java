// @find: models repository, list enabled models, models by provider, discovered models, models in policies, Models
// @what: Spring Data repository for LlmModelEntity rows.
// @flow: Used by the provider registry and the Models page.
// @find: models repository, list enabled models, models by provider, discovered models, models in policies, Models
// @what: Spring Data repository for LlmModelEntity rows.
// @flow: Used by the provider registry and the Models page.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.LlmModelEntity;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Models extends JpaRepository<LlmModelEntity, LlmModelEntity.Key> {

    // @find: enabled seed models of a provider
    // @find: enabled seed models of a provider
    /**
     * The provider's enabled catalogue models: the seeded rows, not the hundreds a provider's own
     * model list may have added (those are read through {@link #findByProviderIdAndEnabledTrueAndSource}).
     */
    @Query("select m from LlmModelEntity m where m.providerId = :providerId and m.enabled = true and m.source = 'seed'")
    List<LlmModelEntity> findByProviderIdAndEnabledTrue(@Param("providerId") String providerId);

    // @find: enabled models of a provider by source
    // @find: enabled models of a provider by source
    /** Every enabled model of a provider from one source, {@code seed} or {@code discovered}. */
    List<LlmModelEntity> findByProviderIdAndEnabledTrueAndSource(String providerId, String source);

    // @find: all enabled seed models
    // @find: all enabled seed models
    /**
     * The enabled catalogue models, seeded rows only. Discovered models are offered through the
     * per-provider model list instead, so that the workspace model list stays the short, curated
     * one; {@link #findDiscoveredInPolicies} adds back the discovered ones a workspace has chosen.
     */
    @Query("select m from LlmModelEntity m where m.enabled = true and m.source = 'seed' order by m.providerId, m.modelId")
    List<LlmModelEntity> findAllEnabled();

    // @find: discovered models used in policies
    // @find: discovered models used in policies
    /** Discovered models that one of the workspace's routing policies names. */
    @Query(
            value = "SELECT DISTINCT m.* FROM llm_models m"
                    + " JOIN model_policy_candidates c ON c.provider_id = m.provider_id AND c.model_id = m.model_id"
                    + " JOIN model_policies p ON p.id = c.policy_id"
                    + " WHERE p.org_id = :orgId AND m.enabled AND m.source = 'discovered'",
            nativeQuery = true)
    List<LlmModelEntity> findDiscoveredInPolicies(@Param("orgId") UUID orgId);
}
