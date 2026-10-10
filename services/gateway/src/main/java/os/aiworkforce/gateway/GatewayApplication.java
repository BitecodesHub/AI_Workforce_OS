// @find: gateway application, gateway main, start gateway, api gateway entry point, single edge, reactive gateway, spring boot main class, GatewayApplication, context propagation, request id logging
// @what: Boot class of the gateway, the platform's single reactive entry point that holds no business logic or database.
// @flow: Starts Spring Boot, scans the gateway and platform-core packages; routes are declared in application.yml.
package os.aiworkforce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import reactor.core.publisher.Hooks;

import os.aiworkforce.platform.PlatformCore;

/**
 * The single edge of the platform.
 *
 * <p>The gateway is reactive. It verifies the access token, applies rate limits, injects the
 * request identifier and forwards to one of the seven business services. It deliberately holds
 * no business logic and no database: a rule that belongs to a service is enforced by that
 * service, because an edge check alone can be bypassed by anything inside the cluster.
 */
@SpringBootApplication(scanBasePackageClasses = {GatewayApplication.class, PlatformCore.class})
@ConfigurationPropertiesScan(basePackageClasses = {GatewayApplication.class, PlatformCore.class})
public class GatewayApplication {

    // @find: start the gateway, main method, enable automatic context propagation, request id in logs, trace context across threads
    public static void main(String[] args) {
        // A request here hops between reactor threads. Without this the logging context (request id)
        // and the trace context stay on the thread that accepted the request, so a log line written
        // after the first hop names no request and the span the proxy call belongs to is lost.
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(GatewayApplication.class, args);
    }
}
