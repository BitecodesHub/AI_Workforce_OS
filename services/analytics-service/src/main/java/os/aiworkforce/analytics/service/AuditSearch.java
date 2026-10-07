package os.aiworkforce.analytics.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.analytics.domain.AuditEvent;

/**
 * Reads one workspace's entries, newest first, narrowed by an {@link AuditFilter}.
 *
 * <p>Paged by a sequence cursor as well as by offset. An offset page shifts whenever a new entry
 * arrives while somebody is reading older ones, so a person paging through a busy log saw rows twice
 * and, for an export, would have written them twice; "entries older than this one" does not move.
 * No count is taken: a count over a filtered log of this size is the slowest part of the query and
 * nothing here needs it.
 */
@Component
public class AuditSearch {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param before only entries with a lower sequence than this, or null for the newest
     * @param offset entries to skip after that, for the offset paging the list endpoint still allows
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> find(UUID orgId, AuditFilter filter, Long before, int offset, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEvent> query = cb.createQuery(AuditEvent.class);
        Root<AuditEvent> root = query.from(AuditEvent.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get("orgId"), orgId));
        if (before != null) {
            where.add(cb.lessThan(root.get("sequence"), before));
        }
        if (filter.actorId() != null) {
            where.add(cb.equal(root.get("actorId"), filter.actorId()));
        }
        if (filter.onBehalfOf() != null) {
            where.add(cb.equal(root.get("onBehalfOf"), filter.onBehalfOf()));
        }
        if (!filter.actions().isEmpty()) {
            where.add(root.get("action").in(filter.actions()));
        }
        if (filter.resourceType() != null) {
            where.add(cb.equal(root.get("resourceType"), filter.resourceType()));
        }
        if (filter.resourceId() != null) {
            where.add(cb.equal(root.get("resourceId"), filter.resourceId()));
        }
        if (filter.outcome() != null) {
            where.add(cb.equal(root.get("outcome"), filter.outcome()));
        }
        if (filter.from() != null) {
            where.add(cb.greaterThanOrEqualTo(root.<java.time.Instant>get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            where.add(cb.lessThan(root.<java.time.Instant>get("occurredAt"), filter.to()));
        }
        query.select(root).where(where.toArray(Predicate[]::new)).orderBy(cb.desc(root.get("sequence")));

        TypedQuery<AuditEvent> typed = entityManager.createQuery(query).setMaxResults(limit);
        if (offset > 0) {
            typed.setFirstResult(offset);
        }
        return typed.getResultList();
    }
}
