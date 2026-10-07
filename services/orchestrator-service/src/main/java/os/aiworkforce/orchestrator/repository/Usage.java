package os.aiworkforce.orchestrator.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.LlmUsageRecord;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Usage extends JpaRepository<LlmUsageRecord, UUID> {

    @Query(
            """
            select coalesce(sum(u.cost), 0) from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :since
            """)
    BigDecimal spendSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /**
     * What one agent has cost its workspace since {@code since}, across every run it made, for the
     * per-agent daily cap. Served by the index on workspace, agent and time.
     */
    @Query(
            """
            select coalesce(sum(u.cost), 0) from LlmUsageRecord u
            where u.orgId = :orgId and u.agentId = :agentId and u.occurredAt >= :since
            """)
    BigDecimal spendSinceByAgent(
            @Param("orgId") UUID orgId, @Param("agentId") UUID agentId, @Param("since") Instant since);

    /**
     * What one run has cost so far, across every attempt the router made for it, counting only the
     * rows of {@code orgId}.
     *
     * <p>Failed attempts are included for the same reason they are recorded at all: a provider
     * bills for a call that timed out after generating most of an answer.
     *
     * <p>The workspace is part of the question, not an afterthought: a run id is only a name, and
     * a row written under another workspace with the same id - by a caller that was trusted to say
     * which run it spent for - must not move this workspace's per-run total or its cap.
     */
    @Query("select coalesce(sum(u.cost), 0) from LlmUsageRecord u where u.orgId = :orgId and u.runId = :runId")
    BigDecimal costForRun(@Param("orgId") UUID orgId, @Param("runId") UUID runId);


    /*
     * The spend report. Each query returns one row per group over the window [from, to), as
     * [key, promptTokens, cachedTokens, completionTokens, cost, failedCost, attempts, failed,
     * skipped]. "attempts" counts the calls actually made, so skipped candidates are not in it;
     * "failedCost" is what failed attempts cost, which a provider bills for and a report that
     * ignored it would not reconcile with the invoice. One query per grouping, because the group
     * is part of the query and a single query that chose its group by a parameter could use no
     * index. The window is half open so consecutive reports never count a boundary row twice.
     */

    /**
     * The day is the UTC day, spelled {@code YYYY-MM-DD}. Native, because the day is cut in the
     * database in an explicit time zone: a cast in a query language would cut it in whatever zone
     * the connection happened to use, and a day's spend would move between reports.
     */
    @Query(
            value =
                    """
            select to_char(date_trunc('day', occurred_at at time zone 'UTC'), 'YYYY-MM-DD') as day,
                   sum(prompt_tokens), sum(cached_tokens), sum(completion_tokens), sum(cost),
                   sum(case when outcome = 'FAILED' then cost else 0 end),
                   sum(case when outcome <> 'SKIPPED' then 1 else 0 end),
                   sum(case when outcome = 'FAILED' then 1 else 0 end),
                   sum(case when outcome = 'SKIPPED' then 1 else 0 end)
            from llm_usage
            where org_id = :orgId and occurred_at >= :from and occurred_at < :to
            group by 1
            order by 1
            """,
            nativeQuery = true)
    List<Object[]> reportByDay(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select u.providerId,
                   sum(u.promptTokens), sum(u.cachedTokens), sum(u.completionTokens), sum(u.cost),
                   sum(case when u.outcome = 'FAILED' then u.cost else 0 end),
                   sum(case when u.outcome <> 'SKIPPED' then 1 else 0 end),
                   sum(case when u.outcome = 'FAILED' then 1 else 0 end),
                   sum(case when u.outcome = 'SKIPPED' then 1 else 0 end)
            from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :from and u.occurredAt < :to
            group by u.providerId
            order by sum(u.cost) desc, u.providerId
            """)
    List<Object[]> reportByProvider(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select concat(u.providerId, '/', u.modelId),
                   sum(u.promptTokens), sum(u.cachedTokens), sum(u.completionTokens), sum(u.cost),
                   sum(case when u.outcome = 'FAILED' then u.cost else 0 end),
                   sum(case when u.outcome <> 'SKIPPED' then 1 else 0 end),
                   sum(case when u.outcome = 'FAILED' then 1 else 0 end),
                   sum(case when u.outcome = 'SKIPPED' then 1 else 0 end)
            from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :from and u.occurredAt < :to
            group by u.providerId, u.modelId
            order by sum(u.cost) desc, u.providerId, u.modelId
            """)
    List<Object[]> reportByModel(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    /** The key is the agent's id, or null for spend that belongs to no agent (chat, knowledge). */
    @Query(
            """
            select u.agentId,
                   sum(u.promptTokens), sum(u.cachedTokens), sum(u.completionTokens), sum(u.cost),
                   sum(case when u.outcome = 'FAILED' then u.cost else 0 end),
                   sum(case when u.outcome <> 'SKIPPED' then 1 else 0 end),
                   sum(case when u.outcome = 'FAILED' then 1 else 0 end),
                   sum(case when u.outcome = 'SKIPPED' then 1 else 0 end)
            from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :from and u.occurredAt < :to
            group by u.agentId
            order by sum(u.cost) desc
            """)
    List<Object[]> reportByAgent(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select u.outcome,
                   sum(u.promptTokens), sum(u.cachedTokens), sum(u.completionTokens), sum(u.cost),
                   sum(case when u.outcome = 'FAILED' then u.cost else 0 end),
                   sum(case when u.outcome <> 'SKIPPED' then 1 else 0 end),
                   sum(case when u.outcome = 'FAILED' then 1 else 0 end),
                   sum(case when u.outcome = 'SKIPPED' then 1 else 0 end)
            from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :from and u.occurredAt < :to
            group by u.outcome
            order by sum(u.cost) desc, u.outcome
            """)
    List<Object[]> reportByOutcome(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * One page of the attempts in a window, oldest first, for the CSV export, as [occurredAt,
     * agentId, runId, providerId, modelId, outcome, failure, skipReason, promptTokens,
     * cachedTokens, completionTokens, cost, durationMs, id]. Keyed on time and id together so a
     * page boundary never skips or repeats a row, and so the export can walk a large window in
     * fixed-size pages instead of holding all of it.
     */
    @Query(
            """
            select u.occurredAt, u.agentId, u.runId, u.providerId, u.modelId, u.outcome, u.failure,
                   u.skipReason, u.promptTokens, u.cachedTokens, u.completionTokens, u.cost, u.durationMs,
                   u.id
            from LlmUsageRecord u
            where u.orgId = :orgId and u.occurredAt >= :from and u.occurredAt < :to
              and (u.occurredAt > :afterTime or (u.occurredAt = :afterTime and u.id > :afterId))
            order by u.occurredAt, u.id
            """)
    List<Object[]> exportPage(
            @Param("orgId") UUID orgId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("afterTime") Instant afterTime,
            @Param("afterId") UUID afterId,
            Pageable page);
}
