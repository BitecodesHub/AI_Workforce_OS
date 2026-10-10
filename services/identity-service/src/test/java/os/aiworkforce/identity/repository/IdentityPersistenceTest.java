// @find: tests for identity persistence, flyway migration, signing keys, sessions per device, reset token claim, postgres testcontainers
// @what: Integration tests of identity repositories and migrations against a real Postgres.
package os.aiworkforce.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.identity.domain.PasswordResetToken;
import os.aiworkforce.identity.domain.Session;
import os.aiworkforce.identity.domain.SigningKey;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.service.SigningKeyStore;
import os.aiworkforce.identity.service.TestKeys;
import os.aiworkforce.identity.service.TokenService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;

/**
 * The new mappings against the real schema: Flyway through V3, then Hibernate's validation, the
 * INET column, the advisory lock and the bulk updates.
 *
 * <p>Unit tests stub the repositories, so they cannot see a mapping the schema rejects - and with
 * {@code ddl-auto: validate} that failure is identity refusing to start. Opt-in, because it needs
 * Docker: run with {@code AIWOS_DATABASE_TESTS=true} (and, where the Ryuk image is not available,
 * {@code TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class IdentityPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Users users;

    @Autowired
    private Sessions sessions;

    @Autowired
    private SigningKeys signingKeys;

    @Autowired
    private PasswordResetTokens resetTokens;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V3 is applied and signing keys default to ES256")
    void migration() {
        assertThat(jdbc.queryForObject(
                        "select column_default from information_schema.columns"
                                + " where table_schema = 'identity' and table_name = 'signing_keys' and column_name = 'algorithm'",
                        String.class))
                .contains("ES256");
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables"
                                + " where table_schema = 'identity' and table_name = 'password_reset_tokens'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a signing key round-trips, and the creation lock can be taken inside a transaction")
    void signingKeys() {
        assertThat(signingKeys.holdKeyCreationLock(42L)).isEqualTo(1);

        SigningKey key = new SigningKey();
        key.setKid("thumbprint-1");
        key.setAlgorithm("ES256");
        key.setPublicJwk("{\"kty\":\"EC\"}");
        key.setPrivateKeyEncrypted(SigningKey.HELD_EXTERNALLY);
        signingKeys.saveAndFlush(key);
        entities.clear();

        assertThat(signingKeys.findActive()).hasValueSatisfying(found -> {
            assertThat(found.getKid()).isEqualTo("thumbprint-1");
            assertThat(found.isHeldExternally()).isTrue();
        });
        SigningKey stored = signingKeys.findById("thumbprint-1").orElseThrow();
        stored.retire(Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
        signingKeys.saveAndFlush(stored);
        assertThat(signingKeys.findActive()).isEmpty();
        assertThat(signingKeys.findByStatusIn(List.of(SigningKey.ACTIVE, SigningKey.RETIRING))).hasSize(1);
    }

    @Test
    @DisplayName("the store creates one key, reuses it, and retires it for a configured key")
    void signingKeyStore() {
        PlatformProperties properties = TestKeys.properties();
        SigningKeyStore store = new SigningKeyStore(signingKeys, new EnvelopeEncryptionService(properties), properties);

        ECKey created = store.activeKey();
        entities.clear();
        ECKey again = store.activeKey();
        assertThat(again.getKeyID()).isEqualTo(created.getKeyID());
        assertThat(signingKeys.findById(created.getKeyID()))
                .hasValueSatisfying(row -> assertThat(row.getAlgorithm()).isEqualTo("ES256"));

        PlatformProperties configured = TestKeys.properties(
                PlatformProperties.Environment.TEST, TestKeys.pem("secp256r1"), null, null, "test");
        ECKey external = TokenService.fromPrivateKey(configured.security().signingPrivateKey(), null);
        store.adoptExternalKey(external);
        entities.clear();

        assertThat(signingKeys.findActive()).hasValueSatisfying(row -> assertThat(row.getKid()).isEqualTo(external.getKeyID()));
        assertThat(store.publishedKeys()).extracting(JWK::getKeyID)
                .containsExactlyInAnyOrder(created.getKeyID(), external.getKeyID());
    }

    @Test
    @DisplayName("sessions keep their address, list per device, and revoke per person")
    void sessions() {
        User user = user("maya@example.com");
        UUID laptop = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        sessions.saveAndFlush(session(user, laptop, "203.0.113.9"));
        sessions.saveAndFlush(session(user, phone, "2001:db8::1"));
        entities.clear();

        List<Session> live = sessions.findLiveByUserId(user.getId(), Instant.now());
        assertThat(live).hasSize(2);
        assertThat(live).extracting(Session::getIpAddress).containsExactlyInAnyOrder("203.0.113.9", "2001:db8::1");
        assertThat(sessions.findFamilyStarts(user.getId(), List.of(laptop, phone))).hasSize(2);
        assertThat(sessions.existsByFamilyIdAndUserId(laptop, user.getId())).isTrue();

        assertThat(sessions.revokeAllForUserExcept(user.getId(), laptop, Instant.now(), "password changed"))
                .isEqualTo(1);
        assertThat(sessions.revokeAllForUser(user.getId(), Instant.now(), "signed out everywhere")).isEqualTo(1);
        entities.clear();
        assertThat(sessions.findLiveByUserId(user.getId(), Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("a reset token is claimed once, and a newer link closes the older one")
    void resetTokens() {
        User user = user("eli@example.com");
        User issuer = user("admin@example.com");
        PasswordResetToken first = token(user, issuer, "hash-1");
        PasswordResetToken second = token(user, issuer, "hash-2");
        entities.clear();

        assertThat(resetTokens.findByTokenHash("hash-1")).hasValueSatisfying(found -> {
            assertThat(found.getIssuedBy()).isEqualTo(issuer.getId());
            assertThat(found.getOrgId()).isEqualTo(ORG);
        });
        assertThat(resetTokens.claim(first.getId(), Instant.now())).isEqualTo(1);
        assertThat(resetTokens.claim(first.getId(), Instant.now())).isZero();
        assertThat(resetTokens.closeOpenFor(user.getId(), Instant.now())).isEqualTo(1);
        assertThat(resetTokens.claim(second.getId(), Instant.now())).isZero();
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(email);
        user.setStatus("active");
        return users.saveAndFlush(user);
    }

    private static Session session(User user, UUID family, String address) {
        Session session = new Session();
        session.setUserId(user.getId());
        session.setFamilyId(family);
        session.setRefreshTokenHash(UUID.randomUUID().toString());
        session.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        session.setIpAddress(address);
        return session;
    }

    private PasswordResetToken token(User user, User issuer, String hash) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setOrgId(ORG);
        token.setIssuedBy(issuer.getId());
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        return resetTokens.saveAndFlush(token);
    }
}
