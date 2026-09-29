package os.aiworkforce.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.llm.LlmCore;
import os.aiworkforce.mcp.McpCore;
import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Orchestrator: agents, goals, runs, approvals and model routing.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(
        scanBasePackageClasses = {
            OrchestratorApplication.class,
            PlatformCore.class,
            PlatformWeb.class,
            LlmCore.class,
            McpCore.class
        })
@ConfigurationPropertiesScan(
        basePackageClasses = {
            OrchestratorApplication.class,
            PlatformCore.class,
            PlatformWeb.class,
            LlmCore.class,
            McpCore.class
        })
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
