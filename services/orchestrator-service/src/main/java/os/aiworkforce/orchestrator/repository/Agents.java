// @find: agents repository, list agents, find agent by key, find agent by id, fallback agent, count agents by status, Agents
// @what: Spring Data repository for Agent rows, scoped to a workspace.
// @flow: Used by the agent service and chat routing.
// @find: agents repository, list agents, find agent by key, find agent by id, fallback agent, count agents by status, Agents
// @what: Spring Data repository for Agent rows, scoped to a workspace.
// @flow: Used by the agent service and chat routing.
package os.aiworkforce.orchestrator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.orchestrator.domain.Agent;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Agents extends JpaRepository<Agent, UUID> {

    // @find: list agents in workspace by name, Agents page
    // @find: list agents in workspace by name, Agents page
    List<Agent> findByOrgIdOrderByName(UUID orgId);

    // @find: find agent by key
    // @find: find agent by key
    Optional<Agent> findByOrgIdAndKey(UUID orgId, String key);

    // @find: get agent by id in workspace
    // @find: get agent by id in workspace
    Optional<Agent> findByIdAndOrgId(UUID id, UUID orgId);

    // @find: find fallback agent, default agent
    // @find: find fallback agent, default agent
    /** The workspace's General Employee, found by its flag rather than by a key people can choose. */
    Optional<Agent> findByOrgIdAndFallbackTrue(UUID orgId);

    // @find: count agents by status
    // @find: count agents by status
    long countByOrgIdAndStatus(UUID orgId, String status);
}
