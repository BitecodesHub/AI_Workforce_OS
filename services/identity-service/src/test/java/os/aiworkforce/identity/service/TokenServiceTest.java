package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.identity.domain.SigningKey;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;

/**
 * The signing key survives restarts and is shared by replicas, and every key is named by its
 * thumbprint.
 *
 * <p>A key generated in memory at startup signed everybody out on every restart, and two
 * replicas behind one key set failed about half of all requests. These tests build several
 * TokenService instances against one configuration or one table - which is what a restart or a
 * second replica is - and check that each verifies what the others sign.
 */
class TokenServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    @Test
    @DisplayName("two instances built from the same PEM verify each other's tokens, under the thumbprint")
    void samePemVerifiesAcrossInstances() throws Exception {
        PlatformProperties properties = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), null, null, "test");
        TestKeys.Table table = new TestKeys.Table();
        TokenService first = new TokenService(properties, store(table, properties));
        TokenService second = new TokenService(properties, store(table, properties));

        String fromFirst = accessToken(first);
        String fromSecond = accessToken(second);

        assertThat(first.activeKeyId()).isEqualTo(second.activeKeyId());
        assertThat(subject(fromFirst, second.jwks())).isEqualTo(USER.toString());
        assertThat(subject(fromSecond, first.jwks())).isEqualTo(USER.toString());

        ECKey published = (ECKey) JWKSet.parse(first.jwks()).getKeys().get(0);
        assertThat(first.activeKeyId()).isEqualTo(published.computeThumbprint().toString());
        assertThat(published.isPrivate()).as("only the public half is published").isFalse();
        // The private half of a configured key never reaches the table.
        assertThat(table.rows.get(first.activeKeyId()).getPrivateKeyEncrypted()).isEqualTo(SigningKey.HELD_EXTERNALLY);
    }

    @Test
    @DisplayName("a configured key id names a configured key")
    void configuredKeyId() {
        PlatformProperties properties = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), "prod-2026-10", null, "test");
        TokenService tokens = new TokenService(properties, store(new TestKeys.Table(), properties));
        assertThat(tokens.activeKeyId()).isEqualTo("prod-2026-10");
    }

    @Test
    @DisplayName("a PEM that is not P-256 stops startup instead of signing with something unverifiable")
    void refusesOtherCurves() {
        PlatformProperties properties = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp384r1"), null, null, "test");
        assertThatThrownBy(() -> new TokenService(properties, store(new TestKeys.Table(), properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P-256");
    }

    @Test
    @DisplayName("without a PEM, the key is generated once, stored encrypted, and reused by every instance")
    void persistedKeyIsReused() throws Exception {
        PlatformProperties properties = TestKeys.properties();
        TestKeys.Table table = new TestKeys.Table();

        TokenService first = new TokenService(properties, store(table, properties));
        TokenService replica = new TokenService(properties, store(table, properties));

        assertThat(table.rows).hasSize(1);
        SigningKey row = table.rows.values().iterator().next();
        assertThat(row.getAlgorithm()).isEqualTo("ES256");
        assertThat(row.getStatus()).isEqualTo(SigningKey.ACTIVE);
        assertThat(row.getPrivateKeyEncrypted()).startsWith("v1:").doesNotContain("\"d\"");

        assertThat(replica.activeKeyId()).isEqualTo(first.activeKeyId());
        assertThat(subject(accessToken(first), replica.jwks())).isEqualTo(USER.toString());
        assertThat(subject(accessToken(replica), first.jwks())).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("a restart signs with the same key under the same thumbprint, never local-dev")
    void restartKeepsTheKid() throws Exception {
        PlatformProperties properties = TestKeys.properties();
        TestKeys.Table table = new TestKeys.Table();

        TokenService before = new TokenService(properties, store(table, properties));
        String issuedBefore = accessToken(before);
        TokenService after = new TokenService(properties, store(table, properties));

        assertThat(after.activeKeyId()).isEqualTo(before.activeKeyId()).isNotEqualTo("local-dev");
        ECKey published = (ECKey) JWKSet.parse(after.jwks()).getKeys().get(0);
        assertThat(after.activeKeyId()).isEqualTo(published.computeThumbprint().toString());
        // A session open before the restart keeps working after it.
        assertThat(subject(issuedBefore, after.jwks())).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("moving to a configured key retires the stored one, which stays published for the overlap")
    void configuredKeyRetiresStoredKey() throws Exception {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties generated = TestKeys.properties();
        TokenService old = new TokenService(generated, store(table, generated));
        String issuedByOld = accessToken(old);

        PlatformProperties configured = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), null, null, "test");
        SigningKeyStore store = store(table, configured);
        TokenService fresh = new TokenService(configured, store);

        SigningKey retiring = table.rows.get(old.activeKeyId());
        assertThat(retiring.getStatus()).isEqualTo(SigningKey.RETIRING);
        assertThat(retiring.getExpiresAt())
                .isAfterOrEqualTo(retiring.getRetiredAt().plus(store.publishOverlap()));
        // Access-token lifetime, clock skew and the verifiers' cache, added together.
        assertThat(store.publishOverlap()).isEqualTo(Duration.ofMinutes(5).plusSeconds(30).plusMinutes(10));

        List<String> kids = JWKSet.parse(fresh.jwks()).getKeys().stream().map(JWK::getKeyID).toList();
        assertThat(kids).containsExactlyInAnyOrder(fresh.activeKeyId(), old.activeKeyId());
        assertThat(subject(issuedByOld, fresh.jwks())).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("a retiring key leaves the published set once its overlap has passed")
    void expiredRetiringKeyIsNotPublished() throws Exception {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties properties = TestKeys.properties();
        TokenService tokens = new TokenService(properties, store(table, properties));

        SigningKey stale = new SigningKey();
        stale.setKid("stale");
        stale.setAlgorithm("ES256");
        stale.setPublicJwk(SigningKeyStore.generate().toPublicJWK().toJSONString());
        stale.setPrivateKeyEncrypted(SigningKey.HELD_EXTERNALLY);
        stale.retire(Instant.now().minusSeconds(3_600), Instant.now().minusSeconds(60));
        table.rows.put("stale", stale);

        List<String> kids = JWKSet.parse(tokens.jwks()).getKeys().stream().map(JWK::getKeyID).toList();
        assertThat(kids).containsExactly(tokens.activeKeyId());
    }

    @Test
    @DisplayName("a stored key this process cannot decrypt is retired and replaced, not fatal")
    void undecryptableKeyIsReplaced() throws Exception {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties firstMaster = TestKeys.properties();
        TokenService before = new TokenService(firstMaster, store(table, firstMaster));

        PlatformProperties otherMaster = TestKeys.properties(
                PlatformProperties.Environment.TEST, null, null, null, "rotated-master");
        TokenService after = new TokenService(otherMaster, store(table, otherMaster));

        assertThat(after.activeKeyId()).isNotEqualTo(before.activeKeyId());
        assertThat(table.rows.get(before.activeKeyId()).getStatus()).isEqualTo(SigningKey.RETIRING);
        assertThat(subject(accessToken(before), after.jwks())).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("deployed, a stored key this process cannot read stops startup instead of being replaced")
    void undecryptableKeyIsFatalWhenDeployed() {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties healthy = TestKeys.properties(
                PlatformProperties.Environment.PRODUCTION, null, null, masterKey(1), "master-2026-09");
        TokenService running = new TokenService(healthy, store(table, healthy));

        // A pod part-way through a master-key rotation, holding the next key but not the current one.
        PlatformProperties rotated = TestKeys.properties(
                PlatformProperties.Environment.PRODUCTION, null, null, masterKey(2), "master-2026-10");
        assertThatThrownBy(() -> new TokenService(rotated, store(table, rotated)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(running.activeKeyId())
                .hasMessageContaining("master-2026-09")
                .hasMessageContaining("master-2026-10");

        // The same key id with the wrong key material is refused the same way.
        PlatformProperties wrongMaterial = TestKeys.properties(
                PlatformProperties.Environment.PRODUCTION, null, null, masterKey(3), "master-2026-09");
        assertThatThrownBy(() -> new TokenService(wrongMaterial, store(table, wrongMaterial)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-2026-09");

        // Nothing was retired or replaced: every healthy replica keeps signing with the shared key.
        assertThat(table.rows).hasSize(1);
        assertThat(table.rows.get(running.activeKeyId()).getStatus()).isEqualTo(SigningKey.ACTIVE);
        assertThat(new TokenService(healthy, store(table, healthy)).activeKeyId()).isEqualTo(running.activeKeyId());
    }

    @Test
    @DisplayName("the public key set reads the table at most once per interval, and keeps the last read on failure")
    void publishedKeysAreCached() throws Exception {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties properties = TestKeys.properties();
        MovableClock clock = new MovableClock(Instant.parse("2026-10-04T09:00:00Z"));
        TokenService tokens = new TokenService(properties, store(table, properties), clock);

        assertThat(kids(tokens)).containsExactly(tokens.activeKeyId());
        ECKey previous = SigningKeyStore.generate();
        SigningKey retiring = new SigningKey();
        retiring.setKid(previous.getKeyID());
        retiring.setAlgorithm("ES256");
        retiring.setPublicJwk(previous.toPublicJWK().toJSONString());
        retiring.setPrivateKeyEncrypted(SigningKey.HELD_EXTERNALLY);
        retiring.retire(Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        table.rows.put(previous.getKeyID(), retiring);

        clock.advance(TokenService.PUBLISHED_KEYS_TTL.minusSeconds(1));
        assertThat(kids(tokens)).as("still the set read a moment ago").containsExactly(tokens.activeKeyId());
        verify(table.repository, times(1)).findByStatusIn(anyCollection());

        clock.advance(Duration.ofSeconds(1));
        assertThat(kids(tokens)).containsExactlyInAnyOrder(tokens.activeKeyId(), previous.getKeyID());
        verify(table.repository, times(2)).findByStatusIn(anyCollection());

        doThrow(new IllegalStateException("database down")).when(table.repository).findByStatusIn(anyCollection());
        clock.advance(TokenService.PUBLISHED_KEYS_TTL);
        assertThat(kids(tokens)).containsExactlyInAnyOrder(tokens.activeKeyId(), previous.getKeyID());
        assertThat(kids(tokens)).containsExactlyInAnyOrder(tokens.activeKeyId(), previous.getKeyID());
        verify(table.repository, times(3)).findByStatusIn(anyCollection());
    }

    @Test
    @DisplayName("a configured key id that already names a different key is refused")
    void reusedKeyIdIsRefused() {
        TestKeys.Table table = new TestKeys.Table();
        PlatformProperties first = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), "prod-1", null, "test");
        new TokenService(first, store(table, first));

        PlatformProperties second = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), "prod-1", null, "test");
        assertThatThrownBy(() -> new TokenService(second, store(table, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod-1");
    }

    private static List<String> kids(TokenService tokens) throws Exception {
        return JWKSet.parse(tokens.jwks()).getKeys().stream().map(JWK::getKeyID).toList();
    }

    /** A 32-byte master key, distinct per seed. */
    private static String masterKey(int seed) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) seed);
        return Base64.getEncoder().encodeToString(key);
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static SigningKeyStore store(TestKeys.Table table, PlatformProperties properties) {
        return new SigningKeyStore(table.repository, new EnvelopeEncryptionService(properties), properties);
    }

    private static String accessToken(TokenService tokens) {
        return tokens.issueAccessToken(USER, ORG, UUID.randomUUID(), Set.of("agent:read"), 1, UUID.randomUUID())
                .token();
    }

    /** Verifies a token the way every other service does: ES256 against a fetched key set. */
    private static String subject(String token, Map<String, Object> jwks) throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(JWKSet.parse(jwks))));
        JWTClaimsSet claims = processor.process(SignedJWT.parse(token), null);
        return claims.getSubject();
    }
}
