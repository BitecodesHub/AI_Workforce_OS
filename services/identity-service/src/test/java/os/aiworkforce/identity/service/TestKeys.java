package os.aiworkforce.identity.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import os.aiworkforce.identity.domain.SigningKey;
import os.aiworkforce.identity.repository.SigningKeys;
import os.aiworkforce.platform.config.PlatformProperties;

/** Platform settings, PEM keys and an in-memory signing-key table for identity's unit tests. */
public final class TestKeys {

    private TestKeys() {}

    /** The test environment, no PEM, a development encryption key. */
    public static PlatformProperties properties() {
        return properties(PlatformProperties.Environment.TEST, null, null, null, "test");
    }

    public static PlatformProperties properties(
            PlatformProperties.Environment environment,
            String pem,
            String activeKeyId,
            String masterKeyBase64,
            String masterKeyId) {
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
                        activeKeyId,
                        pem,
                        Duration.ofMinutes(5),
                        Duration.ofDays(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        "test-secret",
                        "test-internal-secret",
                        new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                        new PlatformProperties.Encryption(masterKeyBase64, masterKeyId, "AES/GCM/NoPadding", 12, 128),
                        12,
                        3,
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
                new PlatformProperties.Observability(
                        0.1, List.of("password"), false),
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

    /** A PKCS#8 PEM for a fresh key on {@code curve}, as openssl pkcs8 -topk8 -nocrypt prints it. */
    public static String pem(String curve) {
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

    /**
     * A signing_keys table in a map, behind the repository interface.
     *
     * <p>Enforces the one rule the real table enforces that the store relies on: at most one row
     * is active at a time.
     */
    static final class Table {

        final Map<String, SigningKey> rows = new LinkedHashMap<>();
        final SigningKeys repository = mock(SigningKeys.class);

        Table() {
            when(repository.findActive()).thenAnswer(call -> rows.values().stream()
                    .filter(SigningKey::isActive)
                    .findFirst());
            when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
            when(repository.findByStatusIn(anyCollection())).thenAnswer(call -> {
                Collection<?> statuses = call.getArgument(0);
                return rows.values().stream()
                        .filter(row -> statuses.contains(row.getStatus()))
                        .toList();
            });
            when(repository.saveAndFlush(any(SigningKey.class))).thenAnswer(call -> {
                SigningKey row = call.getArgument(0);
                boolean anotherActive = rows.values().stream()
                        .anyMatch(other -> other.isActive() && other != row && !other.getKid().equals(row.getKid()));
                if (row.isActive() && anotherActive) {
                    throw new IllegalStateException("signing_keys_single_active violated");
                }
                rows.put(row.getKid(), row);
                return row;
            });
            when(repository.holdKeyCreationLock(anyLong())).thenReturn(1);
        }
    }
}
