package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.LlmProviderEntity;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Providers extends JpaRepository<LlmProviderEntity, String> {

    /** Platform-wide providers plus any the workspace added for itself. */
    @Query("select p from LlmProviderEntity p where p.orgId is null or p.orgId = :orgId order by p.priority")
    List<LlmProviderEntity> findVisibleTo(@Param("orgId") UUID orgId);

    /**
     * Clears a recorded rejection once the provider has answered a call.
     *
     * <p>A rejection is written the moment a provider refuses a key, but nothing else ever wrote
     * the opposite, so a key replaced after a rejection kept reading as refused for good. Only a
     * rejected row changes: the usual case matches nothing and writes nothing, so running this
     * after every successful call costs one indexed lookup.
     *
     * @return 1 when a rejection was cleared, otherwise 0
     */
    @Modifying
    @Query(
            """
            update LlmProviderEntity p
            set p.credentialStatus = 'valid', p.credentialCheckedAt = :now
            where p.id = :providerId and p.credentialStatus = 'rejected'
            """)
    int clearRejectedCredential(@Param("providerId") String providerId, @Param("now") Instant now);
}
