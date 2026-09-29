package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.AgentVersion;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface AgentVersions extends JpaRepository<AgentVersion, UUID> {

    List<AgentVersion> findByAgentIdOrderByRevisionDesc(UUID agentId);

    @Query("select coalesce(max(v.revision), 0) from AgentVersion v where v.agentId = :agentId")
    int highestRevision(@Param("agentId") UUID agentId);
}
