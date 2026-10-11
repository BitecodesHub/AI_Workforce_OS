// @find: startup validator, refuse to start, unsafe configuration, placeholder signing key, default encryption secret, production checks, sampling check, fail fast
// @what: Refuses to start a service in a production-like environment when its settings are unsafe.
// @flow: Runs on ApplicationReadyEvent; reads PlatformProperties
package os.aiworkforce.platform.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to serve traffic with a configuration that is unsafe for the environment.
 *
 * <p>Failing at startup is the point. A service that starts with a placeholder signing key will
 * happily issue tokens nobody can trust, and the failure surfaces days later as a security
 * incident rather than immediately as a failed deployment. Every problem is reported at once, so
 * an operator fixes them in one pass.
 */
@Component
public class PlatformStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(PlatformStartupValidator.class);

    private final PlatformProperties properties;

    /**
     * Receives the bound settings tree, which is read again by {@link #validate()}.
     */
    public PlatformStartupValidator(PlatformProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks the settings once the application is ready, and refuses to continue when they are unsafe.
     *
     * <p>Flow: asks {@link PlatformProperties#validateForEnvironment()} for every problem; when there
     * are none, logs the service name, version and environment and returns. Otherwise it logs each
     * problem at error level and throws.
     *
     * <p>Triggered by Spring's {@code ApplicationReadyEvent}. No database, network or audit side
     * effects. Local and test environments always pass, because the underlying check returns nothing
     * for them.
     *
     * @throws IllegalStateException listing the number of problems, which stops the process
     */
    // @find: scheduled job or startup listener validate, startup validator
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> problems = properties.validateForEnvironment();
        if (problems.isEmpty()) {
            log.info(
                    "{} {} started in {} mode",
                    properties.serviceName(),
                    properties.serviceVersion(),
                    properties.environment());
            return;
        }
        problems.forEach(problem -> log.error("Configuration problem: {}", problem));
        throw new IllegalStateException("Refusing to serve traffic with "
                + problems.size()
                + " configuration problem(s) in "
                + properties.environment()
                + " mode. See the errors above.");
    }
}
