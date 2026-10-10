// @find: audit chain, tamper evident log, hash, sha-256, verify chain, find first break, platform chain, workspace chain, chain key, legacy chain
// @what: Computes audit entry hashes and verifies that a chain of entries has not been changed.
// @flow: Used by AuditAppender and AuditVerification
package os.aiworkforce.analytics.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Makes the audit log tamper-evident.
 *
 * <p>Each entry's hash covers its own contents and the previous entry's hash, so a workspace's log
 * is a chain. Deleting a row, editing one, or inserting one out of order breaks every hash after
 * it, and the break is found by re-walking the chain.
 *
 * <p>There is one chain per workspace, plus one {@value #PLATFORM_CHAIN} chain for events that
 * belong to no workspace. A workspace can therefore verify and export its own history without
 * being shown anyone else's hashes.
 *
 * <p>Two hash formulas exist, named by the {@code hash_version} stored beside each row. Version 1
 * was written when the whole platform shared one chain and covered only some of each row; those
 * rows stay as they are and are checked with the formula they were written with. Version 2 covers
 * every field - including the workspace, the kind of actor, who it acted for and the request - so
 * none can be edited without breaking the hash.
 *
 * <p>This is deliberately tamper-<em>evident</em> rather than tamper-proof. Somebody who owns the
 * database could drop the trigger that makes the table append-only and recompute every hash from
 * the edit onward. What it prevents is the realistic case: a single row quietly changed, by a
 * person or a script, without the change being visible. Making it impossible would need the
 * digests published somewhere the database cannot reach, which is a reasonable later step and is
 * not what this claims to be.
 */
@Service
public class AuditChain {

    private static final Logger log = LoggerFactory.getLogger(AuditChain.class);

    /** The chain for events that belong to no workspace: a sign-in as an address nobody holds. */
    public static final String PLATFORM_CHAIN = "platform";

    public static final int LEGACY = 1;
    public static final int CURRENT = 2;

    /** Rows read per page while walking a chain: enough to be quick, few enough to hold in memory. */
    public static final int PAGE_SIZE = 500;

    private static final String GENESIS = "genesis";

    /*
     * The record separator, used only by version 1. A printable separator would let two different
     * entries produce identical input; version 2 does not rely on one at all, because its input
     * is JSON, which escapes every control character.
     */
    private static final String SEPARATOR = String.valueOf((char) 0x1E);

    /** Always six fractional digits, so the text for an instant never depends on its trailing zeros. */
    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** Plain and unconfigured: version 1 hashed whatever Jackson's defaults printed. */
    private static final ObjectMapper LEGACY_JSON = new ObjectMapper();

    /** The chain an entry for this workspace belongs to. */
    public static String chainKeyFor(UUID orgId) {
        return orgId == null ? PLATFORM_CHAIN : orgId.toString();
    }

    /** The fields the chain covers, as read back from the table. */
    public record ChainEntry(
            long sequence,
            int hashVersion,
            String orgId,
            String previousHash,
            String entryHash,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String requestId,
            Instant occurredAt,
            Map<String, Object> detail) {}

    /**
     * The result of walking one chain.
     *
     * @param checked entries examined, up to and including the break when there is one
     * @param lastSequence the last entry examined, or 0 for an empty chain
     * @param firstBrokenSequence the first entry that failed, or null when the chain is intact
     * @param reason what failed there, in words an operator can act on, or null
     */
    public record Verification(
            String chainKey, long checked, long lastSequence, Long firstBrokenSequence, String reason) {

        public boolean verified() {
            return firstBrokenSequence == null;
        }
    }

    /** Where a walk gets its rows from: the next page of one chain, oldest first. */
    @FunctionalInterface
    public interface ChainPages {
        List<ChainEntry> after(String chainKey, long afterSequence, int limit);
    }

    /** Where a walk of the original platform-wide chain gets its rows from. */
    @FunctionalInterface
    public interface LegacyPages {
        List<ChainEntry> after(long afterSequence, int limit);
    }

    /* ---- The two formulas ---------------------------------------------------------------------- */

    /** The version 1 digest, given the entry before it. Kept exactly as it was written. */
    // @find: compute audit entry hash
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
     * The version 2 digest: SHA-256 over the canonical JSON of every field.
     *
     * <p>The time is cut to the microsecond the column keeps and printed with fixed width, and the
     * detail is written with keys sorted at every depth, so re-reading a row from the database
     * yields the text that was hashed when it was written.
     */
    public String hashV2(ChainEntry entry) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("v", CURRENT);
        material.put("previous", entry.previousHash() == null ? GENESIS : entry.previousHash());
        material.put("org", entry.orgId());
        material.put("actorId", entry.actorId());
        material.put("actorKind", entry.actorKind());
        material.put("onBehalfOf", entry.onBehalfOf());
        material.put("action", entry.action());
        material.put("resourceType", entry.resourceType());
        material.put("resourceId", entry.resourceId());
        material.put("outcome", entry.outcome());
        material.put("requestId", entry.requestId());
        material.put("occurredAt", microsText(entry.occurredAt()));
        material.put("detail", entry.detail() == null ? Map.of() : entry.detail());
        return digest(CanonicalJson.write(material));
    }

    /** The digest an entry should carry, by the formula its own version names. */
    public String recompute(ChainEntry entry) {
        if (entry.hashVersion() >= CURRENT) {
            return hashV2(entry);
        }
        return legacyHash(entry, legacyDetail(entry.detail()));
    }

    private String legacyHash(ChainEntry entry, String detailText) {
        return hash(
                entry.previousHash(),
                entry.actorId(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                entry.occurredAt().toString(),
                detailText);
    }

    /**
     * Whether an entry still carries the hash its fields produce.
     *
     * <p>A version 2 entry has one acceptable digest. A version 1 entry has two, because its detail
     * was hashed with the top level sorted and the nested objects in whatever order the writer's map
     * happened to have - an order {@code jsonb} does not keep. The order the database now returns
     * is tried first, and the fully sorted form second, which is what a writer that sorted would
     * have produced. Reordering the keys of an object changes nothing it says, so accepting either
     * costs the check nothing that matters.
     */
    public boolean matches(ChainEntry entry) {
        if (entry.hashVersion() >= CURRENT) {
            return hashV2(entry).equals(entry.entryHash());
        }
        if (recompute(entry).equals(entry.entryHash())) {
            return true;
        }
        return legacyHash(entry, legacyDetail(sortedDeep(entry.detail()))).equals(entry.entryHash());
    }

    /** The instant as hashed by version 2. */
    public static String microsText(Instant instant) {
        return MICROS.format(instant);
    }

    /* ---- Walking a chain ----------------------------------------------------------------------- */

    /**
     * Re-walks one workspace's chain, page by page, and reports the first entry that does not match.
     *
     * <p>A version 2 entry must name the entry before it (or nothing, if it starts the chain) and
     * must hash to what it carries. A version 1 entry is checked for its own hash only: it was
     * linked into the old platform-wide chain, whose neighbours belong to other workspaces and are
     * checked by {@link #verifyLegacy}.
     */
    // @find: verify audit chain, tamper check
    public Verification verify(String chainKey, ChainPages pages) {
        Walk walk = new Walk(chainKey);
        long after = 0;
        while (true) {
            List<ChainEntry> page = pages.after(chainKey, after, PAGE_SIZE);
            if (page.isEmpty()) {
                return walk.result();
            }
            for (ChainEntry entry : page) {
                if (walk.accept(entry) != null) {
                    return walk.result();
                }
                after = entry.sequence();
            }
            if (page.size() < PAGE_SIZE) {
                return walk.result();
            }
        }
    }

    /** The first broken sequence in an in-memory run of one chain, or empty when it is intact. */
    // @find: find first broken audit entry
    public Optional<Long> findFirstBreak(List<ChainEntry> entries) {
        Walk walk = new Walk("in-memory");
        for (ChainEntry entry : entries) {
            if (walk.accept(entry) != null) {
                break;
            }
        }
        return Optional.ofNullable(walk.result().firstBrokenSequence());
    }

    /**
     * Re-walks the original platform-wide chain, the rows written before there was one chain per
     * workspace.
     *
     * <p>Each entry must hash to what it carries and must name an entry written before it. It does
     * not have to name the one immediately before: the original append was not serialised across
     * processes, so two entries written within milliseconds of each other can name the same
     * predecessor. That is a fork from a known defect, not an alteration, and reporting it as a
     * break would leave this check failing for ever. A deleted entry is still found, because the
     * entry after it names a predecessor that no longer exists.
     */
    public Verification verifyLegacy(LegacyPages pages) {
        Set<String> seen = new HashSet<>();
        long checked = 0;
        long last = 0;
        long after = 0;
        while (true) {
            List<ChainEntry> page = pages.after(after, PAGE_SIZE);
            for (ChainEntry entry : page) {
                checked++;
                last = entry.sequence();
                if (entry.previousHash() != null && !seen.contains(entry.previousHash())) {
                    log.error(
                            "Legacy audit chain broken at sequence {}: its predecessor {} is not present",
                            entry.sequence(),
                            entry.previousHash());
                    return new Verification(
                            "legacy",
                            checked,
                            last,
                            entry.sequence(),
                            "an earlier entry it was chained to is missing");
                }
                if (!matches(entry)) {
                    log.error("Legacy audit entry {} has been altered since it was written", entry.sequence());
                    return new Verification(
                            "legacy", checked, last, entry.sequence(), "the entry no longer matches its hash");
                }
                seen.add(entry.entryHash());
                after = entry.sequence();
            }
            if (page.size() < PAGE_SIZE) {
                return new Verification("legacy", checked, last, null, null);
            }
        }
    }

    /** The state of one walk, so an in-memory list and a paged read follow exactly the same rules. */
    private final class Walk {
        private final String chainKey;
        private String lastHash;
        private long checked;
        private long lastSequence;
        private boolean sawCurrent;
        private Long broken;
        private String reason;

        Walk(String chainKey) {
            this.chainKey = chainKey;
        }

        /** Examines the next entry; returns its sequence if it is the first that fails. */
        Long accept(ChainEntry entry) {
            checked++;
            lastSequence = entry.sequence();
            if (entry.hashVersion() >= CURRENT) {
                sawCurrent = true;
                if (!java.util.Objects.equals(lastHash, entry.previousHash())) {
                    return fail(
                            entry,
                            "the entry before it is not the one it was chained to",
                            "expected previous " + lastHash + " but found " + entry.previousHash());
                }
            } else if (sawCurrent) {
                return fail(entry, "an entry in the old format appears after newer ones", "format went backwards");
            }
            if (!matches(entry)) {
                return fail(entry, "the entry no longer matches its hash", "altered since it was written");
            }
            lastHash = entry.entryHash();
            return null;
        }

        private Long fail(ChainEntry entry, String why, String logDetail) {
            broken = entry.sequence();
            reason = why;
            log.error("Audit chain {} broken at sequence {}: {}", chainKey, entry.sequence(), logDetail);
            return broken;
        }

        Verification result() {
            return new Verification(chainKey, checked, lastSequence, broken, reason);
        }
    }

    /** A copy of a JSON value with the keys of every object sorted. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sortedDeep(Map<String, Object> detail) {
        return (Map<String, Object>) sortedDeepValue(detail == null ? Map.of() : detail);
    }

    private static Object sortedDeepValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, inner) -> sorted.put(String.valueOf(key), sortedDeepValue(inner)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditChain::sortedDeepValue).toList();
        }
        return value;
    }

    /** What version 1 hashed for the detail: the top-level keys sorted, nested maps as they came. */
    private static String legacyDetail(Map<String, Object> detail) {
        try {
            return LEGACY_JSON.writeValueAsString(new TreeMap<>(detail == null ? Map.of() : detail));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The detail of an audit entry could not be written as JSON", e);
        }
    }

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
