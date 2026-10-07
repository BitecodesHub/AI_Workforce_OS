package os.aiworkforce.analytics.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import os.aiworkforce.analytics.repository.AuditEvents;

/**
 * Walks the audit chains stored in the database and says whether they are intact.
 *
 * <p>The walking rules live in {@link AuditChain}; this reads the rows for them, a page at a time,
 * with no transaction held across pages so a long chain never pins a connection.
 */
@Service
public class AuditVerification {

    private final AuditEvents events;
    private final AuditChain chain;

    public AuditVerification(AuditEvents events, AuditChain chain) {
        this.events = events;
        this.chain = chain;
    }

    /** One workspace's chain, from its first entry to its newest. */
    public AuditChain.Verification verifyWorkspace(UUID orgId) {
        return verifyChain(AuditChain.chainKeyFor(orgId));
    }

    public AuditChain.Verification verifyChain(String chainKey) {
        return chain.verify(
                chainKey,
                (key, after, limit) -> events.findByChainKeyAndSequenceGreaterThanOrderBySequenceAsc(
                                key, after, PageRequest.of(0, limit))
                        .stream()
                        .map(AuditAppender::toEntry)
                        .toList());
    }

    /** The platform-wide chain written before there was one chain per workspace. */
    public AuditChain.Verification verifyLegacy() {
        return chain.verifyLegacy((after, limit) -> events
                .findByHashVersionAndSequenceGreaterThanOrderBySequenceAsc(
                        (short) AuditChain.LEGACY, after, PageRequest.of(0, limit))
                .stream()
                .map(AuditAppender::toEntry)
                .toList());
    }

    /**
     * Every chain on the platform: the original one first, then each workspace's and the platform's
     * own. For the nightly check, which is the only place the whole table is read.
     */
    public List<AuditChain.Verification> verifyEverything() {
        List<AuditChain.Verification> results = new ArrayList<>();
        results.add(verifyLegacy());
        for (String chainKey : events.findChainKeys()) {
            results.add(verifyChain(chainKey));
        }
        return results;
    }
}
