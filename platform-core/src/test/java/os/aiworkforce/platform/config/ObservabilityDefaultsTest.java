package os.aiworkforce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * The logging, tracing and sampling settings every service imports, read the way a service reads
 * them.
 *
 * <p>These settings were once declared in a record nothing read, so setting them did nothing and
 * the sampling check in the startup validator always saw 1.0 - which production refuses - however
 * the deployment was configured. Each is pinned here against the real defaults file.
 */
class ObservabilityDefaultsTest {

    private static final String MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    @DisplayName("a production deployment that samples one trace in ten starts")
    void productionAtOneInTenStarts() throws IOException {
        PlatformProperties properties = platform(production(Map.of("AIWOS_TRACE_SAMPLE_RATIO", "0.1")));

        assertThat(properties.observability().traceSampleRatio()).isEqualTo(0.1);
        assertThat(properties.validateForEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("production still refuses to trace every request, and says which setting to change")
    void productionRefusesFullSampling() throws IOException {
        PlatformProperties properties = platform(production(Map.of()));

        assertThat(properties.observability().traceSampleRatio()).isEqualTo(1.0);
        assertThat(properties.validateForEnvironment())
                .singleElement()
                .asString()
                .contains("AIWOS_TRACE_SAMPLE_RATIO")
                .contains("unaffordable");
    }

    @Test
    @DisplayName("the standard Spring setting reaches the validator too")
    void springSettingReachesTheValidator() throws IOException {
        PlatformProperties properties =
                platform(production(Map.of("MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "0.25")));

        assertThat(properties.observability().traceSampleRatio()).isEqualTo(0.25);
        assertThat(properties.validateForEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("the record holds only what something reads")
    void deadFieldsAreGone() {
        List<String> names = Arrays.stream(PlatformProperties.Observability.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(names).containsExactly("traceSampleRatio", "redactKeys", "redactEmailAddresses");
    }

    @Test
    @DisplayName("logs are plain text unless a format is asked for, and AIWOS_LOG_FORMAT_CONSOLE asks for one")
    void consoleFormatIsOptIn() throws IOException {
        assertThat(binder(Map.of()).bind("logging.structured.format.console", String.class).orElse(""))
                .isEmpty();

        Binder ecs = binder(Map.of("AIWOS_LOG_FORMAT_CONSOLE", "ecs"));
        assertThat(ecs.bind("logging.structured.format.console", String.class).get())
                .isEqualTo("ecs");
    }

    @Test
    @DisplayName("plain-text lines carry the request, run and workspace the JSON fields carry")
    void correlationPatternNamesTheReferences() throws IOException {
        String pattern = binder(Map.of())
                .bind("logging.pattern.correlation", String.class)
                .get();

        assertThat(pattern).contains("%X{requestId").contains("%X{runId").contains("%X{orgId");
    }

    @Test
    @DisplayName("tracing is on unless switched off, and nothing is exported until an endpoint is set")
    void tracingDefaults() throws IOException {
        Binder defaults = binder(Map.of());
        assertThat(defaults.bind("management.tracing.enabled", Boolean.class).get())
                .isTrue();
        // Spring Boot builds an exporter for any value, a blank one included, so none is bound.
        assertThat(defaults.bind("management.otlp.tracing.endpoint", String.class).isBound())
                .isFalse();

        assertThat(binder(Map.of("AIWOS_TRACING_ENABLED", "false"))
                        .bind("management.tracing.enabled", Boolean.class)
                        .get())
                .isFalse();
        assertThat(binder(Map.of("MANAGEMENT_OTLP_TRACING_ENDPOINT", "http://collector:4318/v1/traces"))
                        .bind("management.otlp.tracing.endpoint", String.class)
                        .get())
                .isEqualTo("http://collector:4318/v1/traces");
    }

    @Test
    @DisplayName("the old observability variables are no longer part of the defaults")
    void oldVariablesAreGone() throws IOException {
        Binder binder = binder(Map.of());

        for (String dead : List.of("log-format", "tracing-enabled", "otlp-endpoint", "metrics-enabled", "log-level")) {
            assertThat(binder.bind("aiwos.observability." + dead, String.class).isBound())
                    .as(dead)
                    .isFalse();
        }
    }

    private static Map<String, Object> production(Map<String, Object> extra) {
        Map<String, Object> env = new HashMap<>();
        env.put("AIWOS_ENVIRONMENT", "production");
        env.put("AIWOS_ENCRYPTION_MASTER_KEY", MASTER_KEY);
        env.put("AIWOS_SECURITY_DEVELOPMENT_SECRET", "a-real-development-secret");
        env.put("AIWOS_SECURITY_INTERNAL_SERVICE_SECRET", "a-real-internal-secret");
        env.put("AIWOS_CORS_ORIGINS", "https://app.example.com");
        env.putAll(extra);
        return env;
    }

    private static PlatformProperties platform(Map<String, Object> env) throws IOException {
        return binder(env).bind("aiwos", Bindable.of(PlatformProperties.class)).get();
    }

    private static Binder binder(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Only what the test supplies: the machine running the build must not leak its own
        // variables into the assertions.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addLast(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        List<PropertySource<?>> defaults = new YamlPropertySourceLoader()
                .load("platform-defaults", new ClassPathResource("platform-defaults.yml"));
        defaults.forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment);
    }
}
