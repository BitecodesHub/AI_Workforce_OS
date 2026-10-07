package os.aiworkforce.analytics.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.repository.AuditEvents;

/**
 * Appends one entry to its chain.
 *
 * <p>Appending is read-then-insert: find the newest entry of the chain, hash on top of it, write the
 * new one. Two appends to one chain that overlap would both read the same newest entry and both
 * chain onto it, forking the chain, so the whole step runs under a transaction-scoped advisory
 * lock named for the chain, taken before anything is read. The lock is held until the commit, so
 * the next append sees this one. It is a lock in the database rather than a {@code synchronized}
 * block because the deployment runs several instances of this service, and because a
 * {@code synchronized} method returns - and so lets the next caller in - before Spring has
 * committed the transaction around it. Different chains take different locks, so one workspace's
 * audit traffic never waits on another's.
 *
 * <p>A unique index on {@code (chain_key, previous_hash)} is the backstop: if the lock were ever
 * bypassed, the second insert fails and is retried by its sender instead of forking the chain.
 */
@Service
public class AuditAppender {

    /** An entry that claims to have happened later than this is stamped with the time it arrived. */
    static final Duration FUTURE_ALLOWANCE = Duration.ofMinutes(5);

    private final AuditEvents events;
    private final AuditChain chain;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditAppender(AuditEvents events, AuditChain chain) {
        this.events = events;
        this.chain = chain;
    }

    /**
     * What to append.
     *
     * @param eventId the sender's own id for this event, or null; a second append of one id returns
     *     the entry the first produced
     * @param occurredAt when it happened, as the sender saw it, or null for now; a delivery that
     *     waited in the sender's outbox keeps the time of the action, not of the delivery
     */
    public record Command(
            UUID eventId,
            UUID orgId,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail,
            String requestId,
            Instant occurredAt) {}

    /** The entry that was written, or that an earlier delivery of the same event had written. */
    public record Appended(UUID id, long sequence, String entryHash, boolean duplicate) {}

    @Transactional
    public Appended append(Command command) {
        String chainKey = AuditChain.chainKeyFor(command.orgId());

        // The first statement of the transaction. Nothing may be read before it is held.
        lockChain(chainKey);

        if (command.eventId() != null) {
            Optional<AuditEvent> earlier = events.findByEventUuid(command.eventId());
            if (earlier.isPresent()) {
                AuditEvent found = earlier.get();
                return new Appended(found.getId(), found.getSequence(), found.getEntryHash(), true);
            }
        }

        AuditEvent previous = events.findFirstByChainKeyOrderBySequenceDesc(chainKey).orElse(null);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AuditEvent event = new AuditEvent();
        event.setHashVersion((short) AuditChain.CURRENT);
        event.setChainKey(chainKey);
        event.setEventUuid(command.eventId());
        event.setOrgId(command.orgId());
        event.setActorId(command.actorId());
        event.setActorKind(command.actorKind());
        event.setOnBehalfOf(blankToNull(command.onBehalfOf()));
        event.setAction(command.action());
        event.setResourceType(command.resourceType());
        event.setResourceId(blankToNull(command.resourceId()));
        event.setOutcome(command.outcome());
        event.setDetail(command.detail() == null ? Map.of() : command.detail());
        event.setRequestId(blankToNull(command.requestId()));
        event.setOccurredAt(occurredAt(command.occurredAt(), now));
        event.setPreviousHash(previous == null ? null : previous.getEntryHash());
        event.setEntryHash(chain.hashV2(toEntry(event)));

        // Flushed while the lock is held, and the instance returned is the one that was inserted:
        // its sequence is the one the database assigned.
        AuditEvent stored = events.saveAndFlush(event);
        return new Appended(stored.getId(), stored.getSequence(), stored.getEntryHash(), false);
    }

    /** The entry as the chain sees it: exactly the fields the hash covers, as they will be stored. */
    static AuditChain.ChainEntry toEntry(AuditEvent event) {
        return new AuditChain.ChainEntry(
                event.getSequence(),
                event.getHashVersion(),
                event.getOrgId() == null ? null : event.getOrgId().toString(),
                event.getPreviousHash(),
                event.getEntryHash(),
                event.getActorId(),
                event.getActorKind(),
                event.getOnBehalfOf(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getRequestId(),
                event.getOccurredAt(),
                event.getDetail());
    }

    private void lockChain(String chainKey) {
        // hashtext maps the name to the integer an advisory lock takes. Two chains that hash alike
        // merely share a lock for a while; the prefix keeps these away from other users of advisory
        // locks in the same database.
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtext(:name))")
                .setParameter("name", "audit-chain:" + chainKey)
                .getSingleResult();
    }

    private static Instant occurredAt(Instant claimed, Instant now) {
        if (claimed == null || claimed.isAfter(now.plus(FUTURE_ALLOWANCE))) {
            return now;
        }
        return claimed.truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
