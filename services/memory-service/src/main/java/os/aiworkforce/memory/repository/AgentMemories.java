package os.aiworkforce.memory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.memory.domain.AgentMemory;

/*
 * One interface per file: Spring Data does not find a repository nested in another class.
 */

public interface AgentMemories extends JpaRepository<AgentMemory, UUID> {

    Optional<AgentMemory> findByIdAndOrgIdAndAgentId(UUID id, UUID orgId, UUID agentId);

    /** Newest change first, for the agent's page. */
    List<AgentMemory> findByOrgIdAndAgentIdOrderByUpdatedAtDesc(UUID orgId, UUID agentId, Pageable page);

    long countByOrgIdAndAgentId(UUID orgId, UUID agentId);

    /** The same note written twice is one note: found by its words, ignoring case and spacing. */
    @Query(
            """
            select m from AgentMemory m
            where m.orgId = :orgId and m.agentId = :agentId and lower(m.content) = lower(:content)
            """)
    List<AgentMemory> findSame(
            @Param("orgId") UUID orgId, @Param("agentId") UUID agentId, @Param("content") String content);

    /**
     * Notes whose words match any word of the query, best match first. Words are combined with OR
     * for the reason knowledge retrieval does: requiring all of them means a naturally phrased
     * request recalls nothing.
     */
    @Query(
            value =
                    """
                    select * from agent_memories m
                    where m.org_id = :orgId and m.agent_id = :agentId
                      and to_tsvector('english', m.content) @@ to_tsquery('english', :tsQuery)
                    order by ts_rank(to_tsvector('english', m.content), to_tsquery('english', :tsQuery)) desc,
                             m.updated_at desc
                    limit :lim
                    """,
            nativeQuery = true)
    List<AgentMemory> search(
            @Param("orgId") UUID orgId,
            @Param("agentId") UUID agentId,
            @Param("tsQuery") String tsQuery,
            @Param("lim") int limit);
}
