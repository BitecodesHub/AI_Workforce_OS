package os.aiworkforce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
