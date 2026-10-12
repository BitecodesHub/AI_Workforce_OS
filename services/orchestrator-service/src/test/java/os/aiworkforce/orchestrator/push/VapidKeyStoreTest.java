// @find: tests for VAPID key store, generate once, persisted encrypted, environment keys, AIWOS_VAPID_PUBLIC_KEY, VapidKeyStoreTest
// @what: Unit tests for VapidKeyStore: keys from the environment, generated once and stored encrypted, read back by a second instance, and refused when unusable.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import os.aiworkforce.platform.crypto.EncryptedValue;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;

/** The server's push identity: made once, kept encrypted, the same for every instance, and never silently replaced. */
class VapidKeyStoreTest {

    /** The one stored row, as the table would hold it: public key and encrypted private key. */
    private final AtomicReference<String[]> row = new AtomicReference<>();
    private final List<String> inserts = new ArrayList<>();
    private JdbcTemplate jdbc;
    private EnvelopeEncryptionService encryption;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(this::readRow);
        when(jdbc.update(anyString(), anyString(), anyString())).thenAnswer(call -> {
            inserts.add(call.getArgument(2));
            // on conflict do nothing: only the first insert lands.
            return row.compareAndSet(null, new String[] {call.getArgument(1), call.getArgument(2)}) ? 1 : 0;
        });
        // A stand-in for the envelope: reversible, and never the plaintext.
        encryption = mock(EnvelopeEncryptionService.class);
        when(encryption.encrypt(isNull(), anyString())).thenAnswer(call -> EncryptedValue.of(
                "test", new byte[] {1}, new byte[] {2}, reversed(call.getArgument(1, String.class).getBytes(StandardCharsets.UTF_8))));
        when(encryption.decrypt(isNull(), anyString())).thenAnswer(call ->
                new String(reversed(EncryptedValue.parse(call.getArgument(1)).ciphertext()), StandardCharsets.UTF_8));
    }

    // The select: the stored row through the store's own row mapper, or nothing.
    @SuppressWarnings("unchecked")
    private List<String[]> readRow(org.mockito.invocation.InvocationOnMock call) throws Exception {
        String[] stored = row.get();
        if (stored == null) {
            return List.of();
        }
        ResultSet result = mock(ResultSet.class);
        when(result.getString(1)).thenReturn(stored[0]);
        when(result.getString(2)).thenReturn(stored[1]);
        RowMapper<String[]> mapper = call.getArgument(1);
        return java.util.Collections.singletonList(mapper.mapRow(result, 0));
    }

    private static byte[] reversed(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    private VapidKeyStore store(String envPublic, String envPrivate) {
        return new VapidKeyStore(jdbc, encryption, envPublic, envPrivate, Clock.systemUTC());
    }

    @Test
    @DisplayName("with no keys anywhere, a pair is generated once, stored with the private half encrypted, and kept in memory")
    void generatesOnceAndStoresEncrypted() {
        VapidKeyStore store = store("", "");

        WebPushCrypto.RawKeyPair first = store.keys().orElseThrow();
        WebPushCrypto.RawKeyPair again = store.keys().orElseThrow();

        assertThat(again).isEqualTo(first);
        assertThat(WebPushCrypto.pairMatches(first.publicKey(), first.privateKey())).isTrue();
        assertThat(inserts).hasSize(1);
        assertThat(row.get()[0]).isEqualTo(first.publicKey());
        assertThat(row.get()[1]).isNotEqualTo(first.privateKey()).doesNotContain(first.privateKey());
        assertThat(store.publicKey()).contains(first.publicKey());
        // Read twice on the first load (empty, then the winner), never again.
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
    }

    @Test
    @DisplayName("a second instance, or the same one after a restart, reads the stored pair rather than making another")
    void persistsAcrossInstances() {
        WebPushCrypto.RawKeyPair first = store("", "").keys().orElseThrow();

        WebPushCrypto.RawKeyPair later = store("", "").keys().orElseThrow();

        assertThat(later).isEqualTo(first);
        assertThat(inserts).hasSize(1);
    }

    @Test
    @DisplayName("two instances generating at once both end up with the pair that was stored first")
    void losingTheRaceReadsTheWinner() {
        WebPushCrypto.RawKeyPair winner = WebPushCrypto.generateKeyPair();
        // Another instance inserts between this one's empty read and its own insert.
        doAnswer(call -> {
                    row.compareAndSet(
                            null, new String[] {winner.publicKey(), encryption.encrypt(null, winner.privateKey()).serialise()});
                    return 0;
                })
                .when(jdbc)
                .update(anyString(), anyString(), anyString());

        WebPushCrypto.RawKeyPair got = store("", "").keys().orElseThrow();

        assertThat(got).isEqualTo(winner);
    }

    @Test
    @DisplayName("a pair from the environment is used as it is, and nothing is read or written")
    void environmentPairWins() {
        WebPushCrypto.RawKeyPair configured = WebPushCrypto.generateKeyPair();

        WebPushCrypto.RawKeyPair got = store(configured.publicKey(), configured.privateKey()).keys().orElseThrow();

        assertThat(got).isEqualTo(configured);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("one environment variable without the other, or halves of two pairs, turn push off instead of generating")
    void misconfiguredEnvironmentTurnsPushOff() {
        WebPushCrypto.RawKeyPair one = WebPushCrypto.generateKeyPair();
        WebPushCrypto.RawKeyPair two = WebPushCrypto.generateKeyPair();

        assertThat(store(one.publicKey(), "").keys()).isEmpty();
        assertThat(store(one.publicKey(), two.privateKey()).keys()).isEmpty();
        verify(jdbc, never()).update(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a stored key the master key cannot open turns push off rather than replacing the identity")
    void undecryptableStoredKeyIsNotReplaced() {
        row.set(new String[] {WebPushCrypto.generateKeyPair().publicKey(), "garbled"});
        doThrow(new IllegalStateException("tag mismatch")).when(encryption).decrypt(isNull(), eq("garbled"));

        VapidKeyStore store = store("", "");

        assertThat(store.keys()).isEmpty();
        assertThat(store.keys()).isEmpty();
        verify(jdbc, never()).update(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a database that cannot be reached is tried again only after a minute")
    void unreachableDatabaseIsRetriedLater() {
        Instant start = Instant.parse("2026-10-12T09:00:00Z");
        MutableClock clock = new MutableClock(start);
        doThrow(new DataAccessResourceFailureException("down"))
                .doAnswer(this::readRow)
                .when(jdbc)
                .query(anyString(), any(RowMapper.class));
        VapidKeyStore store = new VapidKeyStore(jdbc, encryption, "", "", clock);

        assertThat(store.keys()).isEmpty();
        clock.now = start.plusSeconds(30);
        assertThat(store.keys()).isEmpty();
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));

        clock.now = start.plus(Duration.ofSeconds(VapidKeyStore.RETRY_SECONDS + 1));
        assertThat(store.keys()).isPresent();
    }

    /** A clock a test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
