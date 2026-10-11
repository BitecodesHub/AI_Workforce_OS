// @find: audit events repository, query audit log, newest entry of chain, chain pages, audit search queries, jpa repository
// @what: Spring Data repository for audit_events, including chain and search queries.
// @flow: Used by AuditAppender, AuditSearch, AuditVerification and AnalyticsController
package os.aiworkforce.analytics.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.analytics.domain.AuditEvent;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention across every service.
 */

/**
 * Spring Data repository for the append-only {@link AuditEvent} table. Used by AuditAppender (chain
 * head and duplicate lookup), AuditSearch (filtering is done by criteria there), AuditVerification
 * (chain paging) and AnalyticsController (counts). The workspace scope of a query is the caller's
 * responsibility, except for the chain queries, which are keyed by chain.
 */
public interface AuditEvents extends JpaRepository<AuditEvent, UUID> {

    /**
     * The most recently written entry of one chain: the row whose hash the next entry of that chain
     * must build on.
     *
     * <p>There is one chain per workspace, and one named {@code platform} for events that belong to
     * none, so this is read for a single chain and never across them. Rows written before chains
     * were split carry their workspace's key too, so a workspace's first newer entry links to its
     * last older one.
     */
    Optional<AuditEvent> findFirstByChainKeyOrderBySequenceDesc(String chainKey);

    /** The entry a sender's event id already produced, so a retried delivery is not appended twice. */
    Optional<AuditEvent> findByEventUuid(UUID eventUuid);

    /** The next run of one chain after a sequence, oldest first, for walking it. */
    List<AuditEvent> findByChainKeyAndSequenceGreaterThanOrderBySequenceAsc(
            String chainKey, long afterSequence, Pageable page);

    /** The next run of entries in the original platform-wide chain, oldest first. */
    List<AuditEvent> findByHashVersionAndSequenceGreaterThanOrderBySequenceAsc(
            short hashVersion, long afterSequence, Pageable page);

    /** Every chain that has an entry, for the nightly check. */
    @Query("select distinct e.chainKey from AuditEvent e where e.chainKey is not null order by e.chainKey")
    List<String> findChainKeys();

    /**
     * Number of one workspace's entries that occurred after the given instant; used for the
     * analytics total.
     */
    long countByOrgIdAndOccurredAtAfter(UUID orgId, Instant since);

    /** Projection of the by-action count query: an action code and its number of entries. */
    interface ActionCount {
        String getAction();

        long getCount();
    }

    /** Projection of the by-outcome count query: an outcome and its number of entries. */
    interface OutcomeCount {
        String getOutcome();

        long getCount();
    }

    /** Entries per action for one workspace since an instant, most frequent first. */
    @Query(
            """
            select e.action as action, count(e) as count
            from AuditEvent e
            where e.orgId = :orgId and e.occurredAt >= :since
            group by e.action
            order by count(e) desc
            """)
    List<ActionCount> countByActionSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /** Entries per outcome for one workspace since an instant, in no guaranteed order. */
    @Query(
            """
            select e.outcome as outcome, count(e) as count
            from AuditEvent e
            where e.orgId = :orgId and e.occurredAt >= :since
            group by e.outcome
            """)
    List<OutcomeCount> countByOutcomeSince(@Param("orgId") UUID orgId, @Param("since") Instant since);
}
