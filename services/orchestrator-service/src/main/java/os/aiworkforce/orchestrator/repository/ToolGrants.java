package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.AgentToolGrant;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface ToolGrants extends JpaRepository<AgentToolGrant, UUID> {

    List<AgentToolGrant> findByAgentIdAndEnabledTrue(UUID agentId);

    Optional<AgentToolGrant> findByAgentIdAndServer(UUID agentId, String server);
}
