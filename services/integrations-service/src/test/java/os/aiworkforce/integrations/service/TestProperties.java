package os.aiworkforce.integrations.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import os.aiworkforce.platform.config.PlatformProperties;

/** Platform settings for unit tests: the test environment, with a development encryption key. */
final class TestProperties {

    private TestProperties() {}

    static PlatformProperties properties() {
        return new PlatformProperties(
                PlatformProperties.Environment.TEST,
                "test",
                "0.0.1",
                new PlatformProperties.Security(
                        "aiwos",
                        "aiwos-api",
                        "http://localhost/jwks",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        null,
                        null,
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        "test-secret",
                        "test-internal-secret",
                        new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                        new PlatformProperties.Encryption(null, "test", "AES/GCM/NoPadding", 12, 128),
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
                        List.of("http://localhost"),
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
                        1.0, List.of("password"), false),
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
}
