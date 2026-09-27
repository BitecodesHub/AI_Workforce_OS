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

public interface Usage extends JpaRepository<LlmUsageRecord, UUID> {

    @Query("""
            select coalesce(sum(u.cost), 0) from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :since
            """)
    java.math.BigDecimal spendSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /**
     * What one run has cost so far, across every attempt the router made for it.
     *
     * <p>Failed attempts are included for the same reason they are recorded at all: a provider
     * bills for a call that timed out after generating most of an answer.
     */
    @Query("select coalesce(sum(u.cost), 0) from LlmUsageRecord u where u.runId = :runId")
    java.math.BigDecimal costForRun(@Param("runId") UUID runId);
}
