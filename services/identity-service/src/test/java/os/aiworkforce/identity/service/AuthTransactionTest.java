package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.identity.domain.PasswordResetToken;
import os.aiworkforce.identity.domain.Session;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.PasswordResetTokens;
import os.aiworkforce.identity.repository.Sessions;
import os.aiworkforce.identity.repository.SigningKeys;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;

/**
 * What sign-in, sign-out and reset links leave behind in a real transaction.
 *
 * <p>The unit tests check that signIn is marked not to roll back on its own refusals; this checks
 * that the count is actually committed, as is the closing of a reset link refused at redemption, through Spring's transaction proxy and a real database,
 * and that the key TokenService loads at startup is the one stored. Each test runs outside the
 * test-managed transaction so the service's own transaction commits or rolls back as it would in
 * production. Opt-in like IdentityPersistenceTest: run with {@code AIWOS_DATABASE_TESTS=true}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingConfig.class,
    AuthTransactionTest.Wiring.class,
    AuthService.class,
    PasswordResetService.class,
    GrantGuard.class,
    PasswordService.class,
    TokenService.class,
    SigningKeyStore.class,
    EnvelopeEncryptionService.class,
})
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthTransactionTest {

    private static final String PASSWORD = "correct horse battery";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    @EnableConfigurationProperties(PlatformProperties.class)
    static class Wiring {}

    @Autowired
    private AuthService auth;

    @Autowired
    private PasswordService passwords;

    @Autowired
    private TokenService tokens;

    @Autowired
    private Users users;

    @Autowired
    private Sessions sessions;

    @Autowired
    private SigningKeys signingKeys;

    @Autowired
    private PasswordResetService resets;

    @Autowired
    private PasswordResetTokens resetTokens;

    @Test
    @DisplayName("a failed sign-in's count is committed, not rolled back with the refusal")
    void failedSignInIsCommitted() {
        User user = user("counted@example.com");

        assertThatThrownBy(() -> auth.signIn("counted@example.com", "not the password", null, "ua", null))
                .isInstanceOf(ApiException.class);

        assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sign-in stores the address; signing out everywhere revokes every device")
    void signOutEverywhere() {
        User user = user("everywhere@example.com");
        AuthService.AuthResult laptop = auth.signIn("everywhere@example.com", PASSWORD, null, "laptop", "203.0.113.9");
        auth.signIn("everywhere@example.com", PASSWORD, null, "phone", "2001:db8::7");

        List<AuthService.SessionSummary> listed = auth.listSessions(user.getId(), null);
        assertThat(listed).extracting(AuthService.SessionSummary::ipAddress)
                .containsExactlyInAnyOrder("203.0.113.9", "2001:db8::7");

        auth.signOut(laptop.refreshToken(), true);

        assertThat(sessions.findLiveByUserId(user.getId(), Instant.now())).isEmpty();
        assertThat(sessions.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("changing the password keeps the device that changed it")
    void changePasswordKeepsThisDevice() {
        User user = user("changer@example.com");
        auth.signIn("changer@example.com", PASSWORD, null, "laptop", null);
        auth.signIn("changer@example.com", PASSWORD, null, "phone", null);
        Session laptop = sessions.findLiveByUserId(user.getId(), Instant.now()).stream()
                .filter(session -> "laptop".equals(session.getUserAgent()))
                .findFirst()
                .orElseThrow();

        auth.changePassword(user.getId(), laptop.getId().toString(), PASSWORD, "an entirely new passphrase");

        assertThat(sessions.findLiveByUserId(user.getId(), Instant.now()))
                .extracting(Session::getUserAgent)
                .containsExactly("laptop");
        User changed = users.findById(user.getId()).orElseThrow();
        assertThat(changed.getPasswordChangedAt()).isNotNull();
        assertThat(passwords.matches("an entirely new passphrase", changed.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("a reset link refused at redemption stays closed, and the password is unchanged")
    void refusedResetLinkIsClosed() {
        User target = user("reset-target@example.com");
        User issuer = user("reset-issuer@example.com");
        String raw = "a-reset-token-for-the-transaction-test";
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(target.getId());
        token.setOrgId(UUID.randomUUID());
        token.setIssuedBy(issuer.getId());
        token.setTokenHash(PasswordResetService.hashToken(raw));
        token.setExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        resetTokens.saveAndFlush(token);

        // Neither person is an active member of the link's workspace any more.
        assertThatThrownBy(() -> resets.redeem(raw, "an entirely new passphrase"))
                .isInstanceOf(ApiException.class)
                .hasMessage(PasswordResetService.LINK_UNUSABLE);

        assertThat(resetTokens.findById(token.getId()).orElseThrow().getUsedAt()).isNotNull();
        assertThat(passwords.matches(PASSWORD, users.findById(target.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName("the key TokenService signs with is the stored active key")
    void signsWithTheStoredKey() {
        assertThat(signingKeys.findActive()).hasValueSatisfying(row -> {
            assertThat(row.getKid()).isEqualTo(tokens.activeKeyId());
            assertThat(row.getAlgorithm()).isEqualTo("ES256");
        });
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(email);
        user.setStatus("active");
        user.setEmailVerifiedAt(Instant.now());
        user.setPasswordHash(passwords.hash(PASSWORD));
        return users.saveAndFlush(user);
    }
}
