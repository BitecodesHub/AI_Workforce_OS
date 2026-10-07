package os.aiworkforce.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.analytics.service.AuditChain.ChainEntry;

/**
 * The hash, and the walk that checks it, with no database: the rules are what has to hold, and they
 * are the same whether the rows come from a list or from Postgres.
 */
class AuditChainTest {

    private static final String ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1").toString();
    private static final Instant T0 = Instant.parse("2026-10-06T01:02:03.123456Z");

    private final AuditChain chain = new AuditChain();

    /** A version 2 entry linked to the one before it, hashed as the appender hashes it. */
    private ChainEntry v2(long sequence, String previousHash, Map<String, Object> detail) {
        ChainEntry draft = new ChainEntry(
                sequence,
                AuditChain.CURRENT,
                ORG,
                previousHash,
                "pending",
                "user-1",
                "USER",
                "boss-1",
                "member.role_change",
                "member",
                "user-2",
                "succeeded",
                "req-" + sequence,
                T0.plusSeconds(sequence),
                detail);
        return withHash(draft);
    }

    private ChainEntry withHash(ChainEntry e) {
        return new ChainEntry(
                e.sequence(),
                e.hashVersion(),
                e.orgId(),
                e.previousHash(),
                chain.recompute(e),
                e.actorId(),
                e.actorKind(),
                e.onBehalfOf(),
                e.action(),
                e.resourceType(),
                e.resourceId(),
                e.outcome(),
                e.requestId(),
                e.occurredAt(),
                e.detail());
    }

    /** The same entry with one field replaced and every hash left as it was: an edit in the table. */
    private static ChainEntry edited(ChainEntry e, UnaryOperator<EntryFields> change) {
        EntryFields f = change.apply(new EntryFields(e));
        return new ChainEntry(
                e.sequence(),
                e.hashVersion(),
                f.org,
                e.previousHash(),
                e.entryHash(),
                f.actorId,
                f.actorKind,
                f.onBehalfOf,
                f.action,
                e.resourceType(),
                e.resourceId(),
                f.outcome,
                f.requestId,
                f.occurredAt,
                f.detail);
    }

    private static final class EntryFields {
        String org;
        String actorId;
        String actorKind;
        String onBehalfOf;
        String action;
        String outcome;
        String requestId;
        Instant occurredAt;
        Map<String, Object> detail;

        EntryFields(ChainEntry e) {
            org = e.orgId();
            actorId = e.actorId();
            actorKind = e.actorKind();
            onBehalfOf = e.onBehalfOf();
            action = e.action();
            outcome = e.outcome();
            requestId = e.requestId();
            occurredAt = e.occurredAt();
            detail = e.detail();
        }
    }

    private List<ChainEntry> chainOf(int length) {
        List<ChainEntry> entries = new ArrayList<>();
        String previous = null;
        for (int i = 1; i <= length; i++) {
            ChainEntry entry = v2(i, previous, Map.of("n", i));
            entries.add(entry);
            previous = entry.entryHash();
        }
        return entries;
    }

    /** The version 1 formula, written out as the original code did, to build rows it would have made. */
    private ChainEntry legacy(long sequence, String previousHash, Map<String, Object> detail) throws Exception {
        Instant at = T0.plusSeconds(sequence);
        String detailText = new ObjectMapper().writeValueAsString(new TreeMap<>(detail));
        String hash = chain.hash(
                previousHash, "agent-1", "run.complete", "run", "run-" + sequence, "succeeded", at.toString(), detailText);
        return new ChainEntry(
                sequence,
                AuditChain.LEGACY,
                ORG,
                previousHash,
                hash,
                "agent-1",
                "AGENT",
                "boss-1",
                "run.complete",
                "run",
                "run-" + sequence,
                "succeeded",
                null,
                at,
                detail);
    }

    @Test
    @DisplayName("an intact chain verifies, and so does the empty one")
    void intact() {
        assertThat(chain.findFirstBreak(chainOf(5))).isEmpty();
        assertThat(chain.findFirstBreak(List.of())).isEmpty();
    }

    @Test
    @DisplayName("the hash covers the workspace, the kind of actor, who it acted for and the request")
    void everyFieldIsCovered() {
        List<ChainEntry> entries = chainOf(3);

        // Each of these was left out of the original hash, so editing it went unnoticed.
        assertThat(chain.findFirstBreak(replace(entries, 1, e -> edited(e, f -> {
                    f.org = "00000000-0000-7000-8000-0000000000ff";
                    return f;
                }))))
                .contains(2L);
        assertThat(chain.findFirstBreak(replace(entries, 1, e -> edited(e, f -> {
                    f.actorKind = "SYSTEM";
                    return f;
                }))))
                .contains(2L);
        assertThat(chain.findFirstBreak(replace(entries, 1, e -> edited(e, f -> {
                    f.onBehalfOf = "someone-else";
                    return f;
                }))))
                .contains(2L);
        assertThat(chain.findFirstBreak(replace(entries, 1, e -> edited(e, f -> {
                    f.requestId = "forged";
                    return f;
                }))))
                .contains(2L);
    }

    @Test
    @DisplayName("an edited outcome, actor, action, time or detail is found at the entry that was edited")
    void editedFields() {
        List<ChainEntry> entries = chainOf(4);

        assertThat(chain.findFirstBreak(replace(entries, 2, e -> edited(e, f -> {
                    f.outcome = "failed";
                    return f;
                }))))
                .contains(3L);
        assertThat(chain.findFirstBreak(replace(entries, 2, e -> edited(e, f -> {
                    f.actorId = "user-9";
                    return f;
                }))))
                .contains(3L);
        assertThat(chain.findFirstBreak(replace(entries, 2, e -> edited(e, f -> {
                    f.action = "member.remove";
                    return f;
                }))))
                .contains(3L);
        assertThat(chain.findFirstBreak(replace(entries, 2, e -> edited(e, f -> {
                    f.occurredAt = f.occurredAt.plusNanos(1_000);
                    return f;
                }))))
                .contains(3L);
        assertThat(chain.findFirstBreak(replace(entries, 2, e -> edited(e, f -> {
                    f.detail = Map.of("n", 99);
                    return f;
                }))))
                .contains(3L);
    }

    @Test
    @DisplayName("a deleted entry breaks the link at the entry after it")
    void deletedEntry() {
        List<ChainEntry> entries = new ArrayList<>(chainOf(5));
        entries.remove(2);

        assertThat(chain.findFirstBreak(entries)).contains(4L);
    }

    @Test
    @DisplayName("an entry inserted out of order, or a second start of the chain, is found")
    void insertedEntry() {
        List<ChainEntry> entries = new ArrayList<>(chainOf(3));
        // A second entry that claims to start the chain.
        entries.add(2, v2(99, null, Map.of()));

        assertThat(chain.findFirstBreak(entries)).contains(99L);
    }

    @Test
    @DisplayName("the hash of a time does not depend on trailing zeros or on precision beyond the microsecond")
    void microseconds() {
        Instant whole = Instant.parse("2026-10-06T01:02:03Z");
        Instant sub = whole.plusNanos(123_456_789);

        assertThat(AuditChain.microsText(whole)).isEqualTo("2026-10-06T01:02:03.000000Z");
        assertThat(AuditChain.microsText(sub)).isEqualTo("2026-10-06T01:02:03.123456Z");
        // What the database keeps, and what the appender hashes, is the truncated instant.
        assertThat(sub.truncatedTo(ChronoUnit.MICROS)).isEqualTo(Instant.parse("2026-10-06T01:02:03.123456Z"));
    }

    @Test
    @DisplayName("nested detail hashes the same however its keys were ordered or its numbers written")
    void nestedDetailIsCanonical() {
        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("b", 1);
        inner1.put("a", List.of(Map.of("y", 2.0, "x", 1)));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("zeta", "last");
        first.put("alpha", inner1);

        // As the database hands it back: other key order, 1.0 for 1, a long where a double was.
        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("a", List.of(new TreeMap<>(Map.of("x", 1.0, "y", 2))));
        inner2.put("b", 1L);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("alpha", inner2);
        second.put("zeta", "last");

        ChainEntry a = v2(1, null, first);
        ChainEntry b = v2(1, null, second);

        assertThat(b.entryHash()).isEqualTo(a.entryHash());
        assertThat(CanonicalJson.write(first)).isEqualTo(CanonicalJson.write(second));
    }

    @Test
    @DisplayName("canonical JSON writes numbers as plain decimals and escapes what JSON requires")
    void canonicalJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("one", 1.0);
        value.put("big", 1.0E10);
        value.put("small", 0.50);
        value.put("zero", -0.0);
        value.put("text", "a\"b\\c\nd\u001e");
        value.put("none", null);
        value.put("flag", true);

        assertThat(CanonicalJson.write(value))
                .isEqualTo("{\"big\":10000000000,\"flag\":true,\"none\":null,\"one\":1,\"small\":0.5,"
                        + "\"text\":\"a\\\"b\\\\c\\nd\\u001E\",\"zero\":0}");
    }

    @Test
    @DisplayName("version 1 rows still verify under the formula they were written with")
    void legacyRowsVerify() throws Exception {
        ChainEntry first = legacy(1, null, Map.of("status", "completed"));
        ChainEntry second = legacy(2, first.entryHash(), Map.of("runs", 2, "reason", "stopped"));

        assertThat(chain.recompute(first)).isEqualTo(first.entryHash());
        assertThat(chain.findFirstBreak(List.of(first, second))).isEmpty();

        ChainEntry altered = edited(second, f -> {
            f.outcome = "failed";
            return f;
        });
        assertThat(chain.findFirstBreak(List.of(first, altered))).contains(2L);
    }

    @Test
    @DisplayName("a version 1 row whose nested keys were hashed sorted still verifies when they come back unsorted")
    void legacyNestedOrder() throws Exception {
        Map<String, Object> asWritten = new TreeMap<>(Map.of("b", 1, "a", 2));
        Map<String, Object> asRead = new LinkedHashMap<>();
        asRead.put("b", 1);
        asRead.put("a", 2);
        ChainEntry written = legacy(1, null, Map.of("n", asWritten));
        ChainEntry readBack = new ChainEntry(
                written.sequence(),
                written.hashVersion(),
                written.orgId(),
                written.previousHash(),
                written.entryHash(),
                written.actorId(),
                written.actorKind(),
                written.onBehalfOf(),
                written.action(),
                written.resourceType(),
                written.resourceId(),
                written.outcome(),
                written.requestId(),
                written.occurredAt(),
                Map.of("n", asRead));

        assertThat(chain.matches(readBack)).isTrue();
        assertThat(chain.findFirstBreak(List.of(readBack))).isEmpty();

        ChainEntry changed = edited(readBack, f -> {
            f.detail = Map.of("n", Map.of("a", 3, "b", 1));
            return f;
        });
        assertThat(chain.matches(changed)).isFalse();
    }

    @Test
    @DisplayName("version 2 entries chain onto version 1 entries, and an old entry after a new one is refused")
    void mixedVersions() throws Exception {
        ChainEntry old1 = legacy(1, null, Map.of("k", "v"));
        ChainEntry old2 = legacy(2, "an-entry-of-another-workspace", Map.of("k", "w"));
        ChainEntry fresh = v2(3, old2.entryHash(), Map.of("k", "x"));

        assertThat(chain.findFirstBreak(List.of(old1, old2, fresh))).isEmpty();

        // The old entries are not linked to each other (they belonged to a platform-wide chain), so
        // the walk checks their own hashes only; but a new entry must link to the one before it.
        ChainEntry mislinked = v2(3, old1.entryHash(), Map.of("k", "x"));
        assertThat(chain.findFirstBreak(List.of(old1, old2, mislinked))).contains(3L);

        ChainEntry late = legacy(4, fresh.entryHash(), Map.of("k", "y"));
        assertThat(chain.findFirstBreak(List.of(old1, old2, fresh, late))).contains(4L);
    }

    @Test
    @DisplayName("a chain longer than a page is walked page by page and a break on a later page is found")
    void pagedWalk() {
        List<ChainEntry> entries = chainOf(AuditChain.PAGE_SIZE * 2 + 37);
        List<Integer> pageSizes = new ArrayList<>();

        AuditChain.Verification clean = chain.verify(ORG, pagesOf(entries, pageSizes));

        assertThat(clean.verified()).isTrue();
        assertThat(clean.checked()).isEqualTo(entries.size());
        assertThat(clean.lastSequence()).isEqualTo(entries.size());
        assertThat(pageSizes).containsExactly(AuditChain.PAGE_SIZE, AuditChain.PAGE_SIZE, 37);

        long target = AuditChain.PAGE_SIZE + 10;
        List<ChainEntry> broken = replace(entries, (int) target - 1, e -> edited(e, f -> {
            f.outcome = "denied";
            return f;
        }));
        AuditChain.Verification result = chain.verify(ORG, pagesOf(broken, new ArrayList<>()));

        assertThat(result.verified()).isFalse();
        assertThat(result.firstBrokenSequence()).isEqualTo(target);
        assertThat(result.checked()).isEqualTo(target);
        assertThat(result.reason()).contains("no longer matches");
    }

    @Test
    @DisplayName("the original platform-wide chain verifies across a fork it was known to contain")
    void legacyForkIsNotABreak() throws Exception {
        ChainEntry one = legacy(1, null, Map.of("n", 1));
        ChainEntry two = legacy(2, one.entryHash(), Map.of("n", 2));
        // Written within milliseconds of the previous entry, before appends were serialised: it names
        // the same predecessor as the next one does.
        ChainEntry threeA = legacy(3, two.entryHash(), Map.of("n", 3));
        ChainEntry threeB = legacy(4, two.entryHash(), Map.of("n", 4));
        List<ChainEntry> all = List.of(one, two, threeA, threeB);

        AuditChain.Verification result = chain.verifyLegacy((after, limit) -> all.stream()
                .filter(e -> e.sequence() > after)
                .limit(limit)
                .toList());

        assertThat(result.verified()).isTrue();
        assertThat(result.checked()).isEqualTo(4);
    }

    @Test
    @DisplayName("a deleted or edited entry in the original chain is still found")
    void legacyBreaks() throws Exception {
        ChainEntry one = legacy(1, null, Map.of("n", 1));
        ChainEntry two = legacy(2, one.entryHash(), Map.of("n", 2));
        ChainEntry three = legacy(3, two.entryHash(), Map.of("n", 3));

        List<ChainEntry> deleted = List.of(one, three);
        assertThat(chain.verifyLegacy((after, limit) -> deleted.stream()
                                .filter(e -> e.sequence() > after)
                                .limit(limit)
                                .toList())
                        .firstBrokenSequence())
                .isEqualTo(3L);

        ChainEntry altered = edited(two, f -> {
            f.actorId = "somebody-else";
            return f;
        });
        List<ChainEntry> edited = List.of(one, altered, three);
        assertThat(chain.verifyLegacy((after, limit) -> edited.stream()
                                .filter(e -> e.sequence() > after)
                                .limit(limit)
                                .toList())
                        .firstBrokenSequence())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("each workspace and the platform have their own chain key")
    void chainKeys() {
        UUID org = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

        assertThat(AuditChain.chainKeyFor(org)).isEqualTo(org.toString());
        assertThat(AuditChain.chainKeyFor(null)).isEqualTo("platform");
    }

    private static List<ChainEntry> replace(List<ChainEntry> entries, int index, UnaryOperator<ChainEntry> change) {
        List<ChainEntry> copy = new ArrayList<>(entries);
        copy.set(index, change.apply(copy.get(index)));
        return copy;
    }

    private static AuditChain.ChainPages pagesOf(List<ChainEntry> entries, List<Integer> pageSizes) {
        return (key, after, limit) -> {
            List<ChainEntry> page = entries.stream()
                    .filter(e -> e.sequence() > after)
                    .limit(limit)
                    .toList();
            if (!page.isEmpty()) {
                pageSizes.add(page.size());
            }
            return page;
        };
    }
}
