// @find: tests for platform defaults, default settings, launcher environment variable names, permission cache, platform-defaults.yml
// @what: Checks the shared default settings and the environment variable names the launcher writes.
package os.aiworkforce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The defaults every service imports, read the way a service reads them.
 *
 * <p>Two of these settings decide whether the platform takes traffic at all, and one bounds how
 * long a revoked permission lingers, so each is pinned here rather than left to be noticed in an
 * outage. The last check covers the environment variable names the one-click launcher writes to
 * its private .env file: a name that binds to nothing would leave a service on the published
 * development secret without any error.
 */
class PlatformDefaultsTest {

    private static final String HEALTH = "management.endpoint.health.group.";

    @Test
    @DisplayName("readiness holds only the service's own state and its database, not Redis")
    void readinessExcludesRedis() throws IOException {
        Binder binder = binder(Map.of());

        assertThat(set(binder, HEALTH + "readiness.include")).containsExactlyInAnyOrder("readinessState", "db");
    }

    @Test
    @DisplayName("Redis and the database are reported in a separate group, to authorised callers")
    void dependenciesGroupReportsRedis() throws IOException {
        Binder binder = binder(Map.of());

        assertThat(set(binder, HEALTH + "dependencies.include")).containsExactlyInAnyOrder("db", "redis");
        assertThat(binder.bind(HEALTH + "dependencies.show-details", String.class).get())
                .isEqualTo("when-authorized");
    }

    @Test
    @DisplayName("an access token lives five minutes, which bounds how long a revoked permission lingers")
    void accessTokensAreShortLived() throws IOException {
        PlatformProperties properties = platform(Map.of());

        assertThat(properties.security().accessTokenTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("the variable names the launcher writes reach the settings they are meant for")
    void launcherVariablesBind() throws IOException {
        Map<String, Object> launcherEnv = new HashMap<>();
        launcherEnv.put("AIWOS_ENCRYPTION_KEY_ID", "launcher-1");
        launcherEnv.put("AIWOS_ENCRYPTION_MASTER_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        launcherEnv.put("AIWOS_SECURITY_DEVELOPMENT_SECRET", "generated-development-secret");
        launcherEnv.put("AIWOS_SECURITY_INTERNAL_SERVICE_SECRET", "generated-internal-secret");
        launcherEnv.put("AIWOS_EVENTS_ENABLED", "false");
        launcherEnv.put("AIWOS_DEMO_ENABLED", "false");

        PlatformProperties properties = platform(launcherEnv);

        assertThat(properties.security().encryption().masterKeyId()).isEqualTo("launcher-1");
        assertThat(properties.security().encryption().masterKeyBase64())
                .isEqualTo("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        assertThat(properties.security().developmentSecret()).isEqualTo("generated-development-secret");
        assertThat(properties.security().internalServiceSecret()).isEqualTo("generated-internal-secret");
        assertThat(properties.events().enabled()).isFalse();
        // The demo seeders and the demo-accounts endpoint are switched by this one property.
        assertThat(environment(launcherEnv).getProperty("aiwos.demo.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("without the launcher's variables, an install keeps the legacy key id")
    void legacyKeyIdByDefault() throws IOException {
        PlatformProperties properties = platform(Map.of());

        assertThat(properties.security().encryption().masterKeyId()).isEqualTo("local-dev");
    }

    private static PlatformProperties platform(Map<String, Object> env) throws IOException {
        return binder(env).bind("aiwos", Bindable.of(PlatformProperties.class)).get();
    }

    private static Binder binder(Map<String, Object> env) throws IOException {
        return Binder.get(environment(env));
    }

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Only what the test supplies: the machine running the build must not leak its own
        // AIWOS_* variables into the assertions.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addLast(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        List<PropertySource<?>> defaults =
                new YamlPropertySourceLoader().load("platform-defaults", new ClassPathResource("platform-defaults.yml"));
        defaults.forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static Set<String> set(Binder binder, String name) {
        return binder.bind(name, Bindable.setOf(String.class)).orElse(Set.of());
    }
}
