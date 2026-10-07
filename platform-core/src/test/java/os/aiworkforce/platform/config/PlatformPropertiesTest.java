package os.aiworkforce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The startup check for the token signing key.
 *
 * <p>It used to pass when only a key id was set, so a deployment could "validate" while every
 * replica signed with its own throwaway key. A key id names a key; it is not one.
 */
class PlatformPropertiesTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SIGNING_PROBLEM = "aiwos.security.signing-private-key-pem";

    @Test
    @DisplayName("a key id alone does not satisfy the signing-key check")
    void keyIdAloneIsRejected() {
        List<String> problems = production(null, "prod-2026-10", null).validateForEnvironment();
        assertThat(problems).anyMatch(problem -> problem.startsWith(SIGNING_PROBLEM));
    }

    @Test
    @DisplayName("a master key lets identity keep a generated key, so no PEM is required")
    void masterKeyIsEnough() {
        List<String> problems = production(null, null, MASTER_KEY).validateForEnvironment();
        assertThat(problems).noneMatch(problem -> problem.startsWith(SIGNING_PROBLEM));
    }

    @Test
    @DisplayName("a P-256 PEM passes, including one flattened onto a line with literal \\n")
    void p256PemPasses() {
        String pem = pem("secp256r1");
        assertThat(production(pem, null, MASTER_KEY).validateForEnvironment()).isEmpty();
        assertThat(production(pem.replace("\n", "\\n"), null, MASTER_KEY).validateForEnvironment()).isEmpty();
        assertThat(production(pem, null, MASTER_KEY).security().signingPrivateKey()).isNotNull();
    }

    @Test
    @DisplayName("a PEM on another curve, or not a key at all, stops startup")
    void otherPemsFail() {
        assertThat(production(pem("secp384r1"), null, MASTER_KEY).validateForEnvironment())
                .anyMatch(problem -> problem.contains("P-256"));
        assertThat(production("-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----", null, MASTER_KEY)
                        .validateForEnvironment())
                .anyMatch(problem -> problem.startsWith(SIGNING_PROBLEM));
        assertThatThrownBy(() -> production("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----", null, null)
                        .security()
                        .signingPrivateKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");
    }

    @Test
    @DisplayName("production starts when one trace in ten is sampled, and refuses every trace")
    void productionSamplingRatio() {
        PlatformProperties oneInTen =
                properties(PlatformProperties.Environment.PRODUCTION, null, null, MASTER_KEY, 0.1);
        assertThat(oneInTen.validateForEnvironment()).isEmpty();

        PlatformProperties everything =
                properties(PlatformProperties.Environment.PRODUCTION, null, null, MASTER_KEY, 1.0);
        assertThat(everything.validateForEnvironment())
                .singleElement()
                .asString()
                .contains("unaffordable");

        // Staging traces everything on purpose; only production is held to the budget.
        assertThat(properties(PlatformProperties.Environment.STAGING, null, null, MASTER_KEY, 1.0)
                        .validateForEnvironment())
                .isEmpty();
    }

    @Test
    @DisplayName("local and test environments are not held to deployed rules")
    void localIsExempt() {
        PlatformProperties local = properties(PlatformProperties.Environment.LOCAL, null, "anything", null);
        assertThat(local.validateForEnvironment()).isEmpty();
        assertThat(local.security().signingPrivateKey()).isNull();
    }

    private static PlatformProperties production(String pem, String keyId, String masterKey) {
        return properties(PlatformProperties.Environment.PRODUCTION, pem, keyId, masterKey);
    }

    private static PlatformProperties properties(
            PlatformProperties.Environment environment, String pem, String keyId, String masterKey) {
        return properties(environment, pem, keyId, masterKey, 0.1);
    }

    private static PlatformProperties properties(
            PlatformProperties.Environment environment,
            String pem,
            String keyId,
            String masterKey,
            double traceSampleRatio) {
        return new PlatformProperties(
                environment,
                "identity",
                "0.0.1",
                new PlatformProperties.Security(
                        "aiwos",
                        "aiwos-api",
                        "http://localhost/jwks",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        keyId,
                        pem,
                        Duration.ofMinutes(5),
                        Duration.ofDays(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        "a-real-development-secret",
                        "a-real-internal-secret",
                        new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                        new PlatformProperties.Encryption(masterKey, "prod-1", "AES/GCM/NoPadding", 12, 128),
                        12,
                        8,
                        Duration.ofMinutes(15),
                        false),
                new PlatformProperties.Http(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        100,
                        20,
                        10_485_760,
                        List.of("https://console.example.com"),
                        true,
                        Duration.ofMinutes(30)),
                new PlatformProperties.RateLimit(true, 600, 60, 30, 10, true),
                new PlatformProperties.Events(
                        false,
                        "aiwos",
                        3,
                        (short) 1,
                        Duration.ofSeconds(30),
                        4,
                        List.of(Duration.ofSeconds(1)),
                        true,
                        Duration.ofDays(7)),
                new PlatformProperties.Resilience(
                        50,
                        10,
                        5,
                        Duration.ofSeconds(30),
                        3,
                        3,
                        Duration.ofMillis(1),
                        Duration.ofMillis(5),
                        2.0,
                        false,
                        25,
                        Duration.ofSeconds(60),
                        Map.of()),
                new PlatformProperties.Observability(traceSampleRatio, List.of("password"), false),
                new PlatformProperties.RuntimeConfig(false, Duration.ofSeconds(60), "channel", true),
                new PlatformProperties.Services(
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost"));
    }

    private static String pem(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
            String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
            return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a per-workspace provider breaker takes the override written once for its provider")
    void providerOverrideAppliesToEveryWorkspace() {
        PlatformProperties.DependencyOverride groq =
                new PlatformProperties.DependencyOverride(80f, null, null, null, null, null, null);
        PlatformProperties.Resilience resilience = new PlatformProperties.Resilience(
                50f, 10, 5, Duration.ofSeconds(30), 3, 3, Duration.ofMillis(500), Duration.ofSeconds(30), 2.0, true,
                25, Duration.ofSeconds(60), Map.of("provider.groq", groq));

        assertThat(resilience.forDependency("provider.groq")).isSameAs(groq);
        assertThat(resilience.forDependency("provider.0b0c-org.groq")).isSameAs(groq);
        assertThat(resilience.forDependency("provider.0b0c-org.openrouter").failureRatePercentThreshold())
                .isNull();
        assertThat(resilience.forDependency("identity").failureRatePercentThreshold()).isNull();
    }
}
