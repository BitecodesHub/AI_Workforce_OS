package os.aiworkforce.analytics.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Makes the audit log tamper-evident.
 *
 * <p>Each entry's hash covers its own contents and the previous entry's hash, so the log is a
 * chain. Deleting a row, editing one, or inserting one out of order breaks every hash after it,
 * and the break is found by re-walking the chain.
 *
 * <p>This is deliberately tamper-<em>evident</em> rather than tamper-proof. Somebody with write
 * access to the database could recompute the whole chain. What it prevents is the realistic case:
 * a single row quietly changed, by a person or a script, without the change being visible. Making
 * it impossible would need the digests published somewhere the database cannot reach, which is a
 * reasonable later step and is not what this claims to be.
 */
@Service
public class AuditChain {

    private static final Logger log = LoggerFactory.getLogger(AuditChain.class);
    private static final String GENESIS = "genesis";

    /*
     * The record separator, which cannot appear in any of the fields being joined. A printable
     * separator would let two different entries produce identical input, and a chain where two
     * histories hash the same is not a chain.
     */
    private static final String SEPARATOR = String.valueOf((char) 0x1E);

    /** The digest for an entry, given the one before it. */
    public String hash(
            String previousHash,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String occurredAt,
            String detail) {
        String material = String.join(
                SEPARATOR,
                previousHash == null ? GENESIS : previousHash,
                nullToEmpty(actorId),
                nullToEmpty(action),
                nullToEmpty(resourceType),
                nullToEmpty(resourceId),
                nullToEmpty(outcome),
                nullToEmpty(occurredAt),
                nullToEmpty(detail));
        return digest(material);
    }

    /**
     * Re-walks a run of entries and reports the first that does not match.
     *
     * @return the sequence number of the first broken entry, or empty when the chain is intact
     */
    public Optional<Long> findFirstBreak(List<ChainEntry> entries) {
        String expectedPrevious = null;
        for (ChainEntry entry : entries) {
            if (expectedPrevious != null && !expectedPrevious.equals(entry.previousHash())) {
                log.error(
                        "Audit chain broken at sequence {}: expected previous {} but found {}",
                        entry.sequence(), expectedPrevious, entry.previousHash());
                return Optional.of(entry.sequence());
            }
            String recomputed = hash(
                    entry.previousHash(), entry.actorId(), entry.action(), entry.resourceType(),
                    entry.resourceId(), entry.outcome(), entry.occurredAt(), entry.detail());
            if (!recomputed.equals(entry.entryHash())) {
                log.error("Audit entry {} has been altered since it was written", entry.sequence());
                return Optional.of(entry.sequence());
            }
            expectedPrevious = entry.entryHash();
        }
        return Optional.empty();
    }

    /** The fields the chain covers, as read back from the table. */
    public record ChainEntry(
            long sequence,
            String previousHash,
            String entryHash,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String occurredAt,
            String detail) {}

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
