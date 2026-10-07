package os.aiworkforce.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * The gateway's readiness depends on nothing but itself.
 *
 * <p>The gateway is the platform's only entry point, so when readiness fails every request fails.
 * Redis backs only the rate limiter, which lets requests through when Redis is down; tying
 * readiness to it would turn a cache restart into a full outage. This loads the gateway's
 * configuration the way the application does - its own file plus the imported platform defaults,
 * which win over it - and checks the group that results.
 */
class GatewayHealthGroupsTest {

    private static final String READINESS = "management.endpoint.health.group.readiness";
    private static final String DEPENDENCIES = "management.endpoint.health.group.dependencies";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("readiness is the gateway's own state only, whatever the platform defaults list")
    void readinessIsOwnStateOnly() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            Set<String> members = new LinkedHashSet<>(set(binder, READINESS + ".include"));
            members.removeAll(set(binder, READINESS + ".exclude"));

            assertThat(members).containsExactly("readinessState");
            assertThat(flag(context.getEnvironment(), "management.endpoint.health.validate-group-membership"))
                    .isFalse();
        });
    }

    @Test
    @DisplayName("Redis is still reported, in a group that does not route traffic")
    void redisReportedSeparately() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());

            assertThat(set(binder, DEPENDENCIES + ".include")).contains("redis");
            assertThat(context.getEnvironment().getProperty(DEPENDENCIES + ".show-details"))
                    .isEqualTo("when-authorized");
        });
    }

    private static Set<String> set(Binder binder, String name) {
        return binder.bind(name, Bindable.setOf(String.class)).orElse(Set.of());
    }

    private static boolean flag(Environment environment, String name) {
        return environment.getProperty(name, Boolean.class, true);
    }
}
