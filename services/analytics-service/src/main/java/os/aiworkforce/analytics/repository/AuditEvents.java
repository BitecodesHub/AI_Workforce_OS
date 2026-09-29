package os.aiworkforce.analytics.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.analytics.domain.AuditEvent;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention across every service.
 */

public interface AuditEvents extends JpaRepository<AuditEvent, UUID> {

    /**
     * The most recently written entry, chain-wide.
     *
     * <p>The chain is one sequence for the whole platform, not one per organisation: {@code
     * sequence} is a single {@code BIGSERIAL} and {@code audit_sequence_unique} enforces it is
     * unique across every organisation, which only makes sense if one chain covers all of them.
     * A per-organisation chain would need its own sequence column per organisation; this schema
     * has one sequence, so this is the row whose hash the next entry must build on.
     */
    Optional<AuditEvent> findFirstByOrderBySequenceDesc();

    /** A page of this organisation's entries, newest first. */
    Page<AuditEvent> findByOrgIdOrderBySequenceDesc(UUID orgId, Pageable pageable);

    long countByOrgIdAndOccurredAtAfter(UUID orgId, Instant since);

    interface ActionCount {
        String getAction();

        long getCount();
    }

    interface OutcomeCount {
        String getOutcome();

        long getCount();
    }

    @Query(
            """
            select e.action as action, count(e) as count
            from AuditEvent e
            where e.orgId = :orgId and e.occurredAt >= :since
            group by e.action
            order by count(e) desc
            """)
    List<ActionCount> countByActionSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query(
            """
            select e.outcome as outcome, count(e) as count
            from AuditEvent e
            where e.orgId = :orgId and e.occurredAt >= :since
            group by e.outcome
            """)
    List<OutcomeCount> countByOutcomeSince(@Param("orgId") UUID orgId, @Param("since") Instant since);
}
