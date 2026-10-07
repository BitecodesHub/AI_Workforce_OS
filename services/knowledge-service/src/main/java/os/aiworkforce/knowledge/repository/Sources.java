package os.aiworkforce.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.knowledge.domain.Source;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Sources extends JpaRepository<Source, UUID> {

    List<Source> findByOrgIdOrderByName(UUID orgId);

    /** The workspace's own sources: every one that does not belong to a single agent. */
    List<Source> findByOrgIdAndAgentIdIsNullOrderByName(UUID orgId);

    /** The workspace sources everyone may see: those that are not restricted and belong to no agent. */
    List<Source> findByOrgIdAndAgentIdIsNullAndRestrictedFalseOrderByName(UUID orgId);

    /** The sources one agent owns. */
    List<Source> findByOrgIdAndAgentIdOrderByName(UUID orgId, UUID agentId);

    Optional<Source> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The sources a caller may see. Restricted ones are included only for someone who manages
     * knowledge; everyone else gets the workspace-wide ones.
     */
    default List<Source> findVisible(UUID orgId, boolean includeRestricted) {
        return includeRestricted
                ? findByOrgIdAndAgentIdIsNullOrderByName(orgId)
                : findByOrgIdAndAgentIdIsNullAndRestrictedFalseOrderByName(orgId);
    }

    /**
     * What one agent's search may read: the workspace sources the person may see, and the agent's
     * own. Nobody else's agent-owned sources are ever included.
     */
    default List<Source> findVisibleTo(UUID orgId, boolean includeRestricted, UUID agentId) {
        List<Source> workspace = findVisible(orgId, includeRestricted);
        if (agentId == null) {
            return workspace;
        }
        List<Source> all = new java.util.ArrayList<>(workspace);
        all.addAll(findByOrgIdAndAgentIdOrderByName(orgId, agentId));
        return all;
    }
}
