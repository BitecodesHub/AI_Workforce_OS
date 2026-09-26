package os.aiworkforce.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.LlmUsageRecord;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Approvals extends JpaRepository<Approval, UUID> {

    @Query("""
            select a from Approval a
            where a.orgId = :orgId and a.status = 'pending'
            order by a.requestedAt
            """)
    List<Approval> findPending(@Param("orgId") UUID orgId);

    Optional<Approval> findByIdAndOrgId(UUID id, UUID orgId);

    List<Approval> findByRunIdAndStatus(UUID runId, String status);

    /** Pending approvals past their deadline, for the expiry sweep. */
    @Query("select a from Approval a where a.status = 'pending' and a.expiresAt < :now")
    List<Approval> findExpired(@Param("now") Instant now, Pageable pageable);
}
