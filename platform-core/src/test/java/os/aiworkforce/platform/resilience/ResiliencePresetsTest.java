// @find: tests for resilience presets, circuit breaker config, per-workspace provider names, timeouts
// @what: Checks how breakers and waits are configured for named dependencies.
package os.aiworkforce.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * How a breaker or a wait is configured for a named dependency, including the per-workspace
 * provider names the router uses.
 */
class ResiliencePresetsTest {

    private static ResiliencePresets presets(Map<String, PlatformProperties.DependencyOverride> overrides) {
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.resilience())
                .thenReturn(new PlatformProperties.Resilience(
                        50f,
                        10,
                        5,
                        Duration.ofSeconds(30),
                        3,
                        3,
                        Duration.ofMillis(100),
                        Duration.ofMillis(400),
                        2.0,
                        false,
                        25,
                        Duration.ofSeconds(60),
                        overrides));
        return new ResiliencePresets(properties);
    }

    @Test
    @DisplayName("a provider's breaker for one workspace takes the override written for that provider")
    void perWorkspaceBreakerUsesTheProviderOverride() {
        ResiliencePresets presets = presets(Map.of(
                "provider.groq", new PlatformProperties.DependencyOverride(80f, 20, null, null, null, null, null)));

        var forWorkspace = presets.circuitBreaker("provider.0b0c-org.groq").getCircuitBreakerConfig();
        var forAnother = presets.circuitBreaker("provider.9f9f-org.groq").getCircuitBreakerConfig();
        var unrelated = presets.circuitBreaker("provider.0b0c-org.openrouter").getCircuitBreakerConfig();

        assertThat(forWorkspace.getFailureRateThreshold()).isEqualTo(80f);
        assertThat(forWorkspace.getSlidingWindowSize()).isEqualTo(20);
        assertThat(forAnother.getFailureRateThreshold()).isEqualTo(80f);
        // No override for this provider, so the platform default applies.
        assertThat(unrelated.getFailureRateThreshold()).isEqualTo(50f);
    }

    @Test
    @DisplayName("an exact override for one workspace's provider beats the one written for the provider")
    void exactOverrideWins() {
        ResiliencePresets presets = presets(Map.of(
                "provider.groq", new PlatformProperties.DependencyOverride(80f, null, null, null, null, null, null),
                "provider.0b0c-org.groq",
                        new PlatformProperties.DependencyOverride(30f, null, null, null, null, null, null)));

        assertThat(presets.circuitBreaker("provider.0b0c-org.groq")
                        .getCircuitBreakerConfig()
                        .getFailureRateThreshold())
                .isEqualTo(30f);
        assertThat(presets.circuitBreaker("provider.1a1a-org.groq")
                        .getCircuitBreakerConfig()
                        .getFailureRateThreshold())
                .isEqualTo(80f);
    }

    @Test
    @DisplayName("the organisation service's breaker is built from the defaults, and kept per name")
    void serviceBreaker() {
        ResiliencePresets presets = presets(Map.of());

        var breaker = presets.circuitBreaker("service.organisation");

        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(presets.circuitBreaker("service.organisation")).isSameAs(breaker);
    }

    @Test
    @DisplayName("a wait grows with each attempt and never passes the cap")
    void backoffGrowsAndIsCapped() {
        ResiliencePresets presets = presets(Map.of());

        assertThat(presets.backoff("service.organisation", 1)).isEqualTo(Duration.ofMillis(100));
        assertThat(presets.backoff("service.organisation", 2)).isEqualTo(Duration.ofMillis(200));
        assertThat(presets.backoff("service.organisation", 3)).isEqualTo(Duration.ofMillis(400));
        assertThat(presets.backoff("service.organisation", 9)).isEqualTo(Duration.ofMillis(400));
    }
}
