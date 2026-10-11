// @find: append audit event, add audit entry, hash chain append, advisory lock, duplicate event, idempotent audit write
// @what: Appends one audit entry to its workspace chain under a database lock so the chain never forks.
// @flow: Called by InternalAuditController.append; uses AuditChain for hashing
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

    /**
     * Repository used to find the chain head and any earlier delivery of the same event, and to
     * insert the new entry.
     */
    private final AuditEvents events;
    /** Hash rules; supplies the entry hash for a new entry. */
    private final AuditChain chain;

    /** Injected by the container; used only to take the advisory lock. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Creates the service over the events repository and the chain hasher. */
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

    /**
     * Appends one entry to the chain of the workspace the command names, or to the platform chain when it
     * names none. Called by InternalAuditController.
     *
     * Flow, all in one transaction: take the chain's advisory lock; when an event id was supplied and an
     * entry for it exists, return that entry with the duplicate flag set and write nothing; otherwise read
     * the chain's newest entry, build the new entry (blank optional fields become null, a missing detail
     * becomes empty, the time is the sender's unless absent or more than five minutes ahead), link it to the
     * previous hash, compute its hash and insert and flush it. Side effect: one insert, and a lock held until
     * commit. Failure behaviour: any exception rolls back and releases the lock; a unique-index violation
     * (the backstop against a bypassed lock) surfaces to the sender, which retries.
     *
     * @param command the entry to append
     * @return the id, sequence and hash of the stored entry, and whether it was a repeat delivery
     */
    @Transactional
    // @find: append audit event, write audit entry to chain
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

    /**
     * Takes the transaction-scoped Postgres advisory lock for one chain, blocking until it is free. It is
     * released automatically at commit or rollback.
     */
    private void lockChain(String chainKey) {
        // hashtext maps the name to the integer an advisory lock takes. Two chains that hash alike
        // merely share a lock for a while; the prefix keeps these away from other users of advisory
        // locks in the same database.
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtext(:name))")
                .setParameter("name", "audit-chain:" + chainKey)
                .getSingleResult();
    }

    /**
     * Chooses the stored time: now when the sender gave none or claimed a time more than
     * {@link #FUTURE_ALLOWANCE} ahead, otherwise the sender's time, truncated to microseconds so the stored
     * and hashed values agree.
     */
    private static Instant occurredAt(Instant claimed, Instant now) {
        if (claimed == null || claimed.isAfter(now.plus(FUTURE_ALLOWANCE))) {
            return now;
        }
        return claimed.truncatedTo(ChronoUnit.MICROS);
    }

    /** Returns null for a null or blank value, otherwise the value unchanged. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
