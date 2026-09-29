package os.aiworkforce.orchestrator.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import os.aiworkforce.orchestrator.domain.LlmModelEntity;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Models extends JpaRepository<LlmModelEntity, LlmModelEntity.Key> {

    List<LlmModelEntity> findByProviderIdAndEnabledTrue(String providerId);

    @Query("select m from LlmModelEntity m where m.enabled = true order by m.providerId, m.modelId")
    List<LlmModelEntity> findAllEnabled();
}
