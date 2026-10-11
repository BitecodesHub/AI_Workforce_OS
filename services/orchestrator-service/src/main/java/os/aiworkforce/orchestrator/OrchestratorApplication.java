// @find: orchestrator service, spring boot main, agent engine startup, agents goals runs approvals, model routing, scheduling enabled, OrchestratorApplication
// @what: Boot class of the orchestrator microservice that hosts agents, goals, runs, approvals, questions, providers and budgets.
// @flow: Starts the Spring context; every service and controller under orchestrator/service and orchestrator/web is wired from here
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

    /** Starts the Spring context and, with it, the embedded web server, the schedulers and the event listeners of this service. */
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
