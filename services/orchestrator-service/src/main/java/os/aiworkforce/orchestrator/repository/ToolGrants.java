// @find: tool grants repository, agent tool permissions, enabled grants of agent, grant for agent and server, ToolGrants
// @what: Spring Data repository for AgentToolGrant rows.
// @flow: Used by the agent service and run engine when building tools.
// @find: tool grants repository, agent tool permissions, enabled grants of agent, grant for agent and server, ToolGrants
// @what: Spring Data repository for AgentToolGrant rows.
// @flow: Used by the agent service and run engine when building tools.
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

    // @find: enabled tool grants of an agent
    // @find: enabled tool grants of an agent
    List<AgentToolGrant> findByAgentIdAndEnabledTrue(UUID agentId);

    // @find: tool grant for agent and server
    // @find: tool grant for agent and server
    Optional<AgentToolGrant> findByAgentIdAndServer(UUID agentId, String server);
}
