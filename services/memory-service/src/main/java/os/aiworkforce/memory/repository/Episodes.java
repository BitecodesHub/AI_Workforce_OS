// @find: episodes repository, search episodes, episodes for run, expired episodes, jpa repository, full text search
// @what: Spring Data repository for episodes, including the workspace-scoped search query.
// @flow: Used by EpisodicMemory
package os.aiworkforce.memory.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.memory.domain.Episode;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Spring Data repository for {@link Episode} rows: run history, recent and searched recall,
 * compaction candidates and expiry deletion. Used by {@code EpisodicMemory}, which supplies the
 * organisation scope and the transaction boundaries.
 */
public interface Episodes extends JpaRepository<Episode, UUID> {

    /**
     * The episodes recorded during one run, oldest first, for replaying what happened in that run.
     */
    List<Episode> findByOrgIdAndRunIdOrderByOccurredAt(UUID orgId, UUID runId);

    /**
     * The most useful recent memories for an agent.
     *
     * <p>Ordered by importance before recency: a decision from last month matters more than
     * an observation from this morning, and an agent given only the latter will keep
     * relitigating settled questions.
     */
    @Query(
            """
            select e from Episode e
            where e.orgId = :orgId
              and (:agentId is null or e.agentId = :agentId)
              and e.compacted = false
            order by e.importance desc, e.occurredAt desc
            """)
    List<Episode> findRecent(@Param("orgId") UUID orgId, @Param("agentId") UUID agentId, Pageable pageable);

    /**
     * Full-text search over the summary, using the index built in the migration.
     *
     * <p>Terms are combined with OR for the same reason as knowledge retrieval: requiring every
     * word means a question phrased naturally recalls nothing, and an agent told nothing is
     * remembered will ask a colleague the same thing twice a week.
     *
     * <p>Scoped to one agent when {@code agentId} is given, like {@link #findRecent}; without that,
     * a search for one agent returned every agent's memories in the workspace. The cast is needed
     * because Postgres cannot infer the type of a null parameter in a native query.
     */
    @Query(
            value =
                    """
            with terms as (
                select string_agg(quote_literal(lexeme), ' | ') as query
                from unnest(to_tsvector('english', :query))
            )
            select e.* from episodes e
            cross join terms
            where e.org_id = :orgId
              and (cast(:agentId as uuid) is null or e.agent_id = cast(:agentId as uuid))
              and e.compacted = false
              and terms.query is not null
              and to_tsvector('english', e.summary) @@ to_tsquery('english', terms.query)
            order by ts_rank(to_tsvector('english', e.summary), to_tsquery('english', terms.query)) desc,
                     e.occurred_at desc
            """,
            nativeQuery = true)
    List<Episode> search(
            @Param("orgId") UUID orgId,
            @Param("agentId") UUID agentId,
            @Param("query") String query,
            Pageable pageable);

    /**
     * Compaction candidates: uncompacted episodes older than {@code before} whose importance does not
     * exceed {@code ceiling}, oldest first. Decisions, handoffs and outcomes are never offered, because
     * they are the facts later runs rely on.
     */
    @Query(
            """
            select e from Episode e
            where e.orgId = :orgId
              and e.occurredAt < :before
              and e.importance <= :ceiling
              and e.compacted = false
              and e.kind not in ('decision', 'handoff', 'outcome')
            order by e.occurredAt
            """)
    List<Episode> findCompactable(
            @Param("orgId") UUID orgId,
            @Param("before") Instant before,
            @Param("ceiling") int ceiling,
            Pageable pageable);

    /**
     * Flags the given episodes as compacted so recall and further compaction skip them; returns the number of rows updated. Called by {@code EpisodicMemory} after a summary episode is written.
     */
    @Modifying
    @Query("update Episode e set e.compacted = true where e.id in :ids")
    int markCompacted(@Param("ids") List<UUID> ids);

    /**
     * Deletes up to {@code batch} episodes whose expiry is earlier than {@code now}, and returns the number
     * removed. Batching keeps the transaction short; called repeatedly by {@code EpisodePurgeJob}.
     */
    @Modifying
    @Query(
            value =
                    """
            delete from episodes
            where id in (
                select id from episodes where expires_at is not null and expires_at < :now limit :batch
            )
            """,
            nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("batch") int batch);
}
