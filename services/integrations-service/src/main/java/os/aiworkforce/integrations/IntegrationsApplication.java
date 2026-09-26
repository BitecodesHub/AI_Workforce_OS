package os.aiworkforce.integrations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.mcp.McpCore;
import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Integrations: scoped tool access through Model Context Protocol servers.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(
        scanBasePackageClasses = {IntegrationsApplication.class, PlatformCore.class, PlatformWeb.class, McpCore.class})
@ConfigurationPropertiesScan(
        basePackageClasses = {IntegrationsApplication.class, PlatformCore.class, PlatformWeb.class, McpCore.class})
public class IntegrationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationsApplication.class, args);
    }
}
