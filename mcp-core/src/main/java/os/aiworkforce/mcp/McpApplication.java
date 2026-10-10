// @find: mcp application, MCP core service, spring boot main, application entry point, tool calling service
// @what: Spring Boot entry point class for the MCP core module.
// @flow: Starts the module standalone; normally mcp-core is used as a library by integrations-service


package os.aiworkforce.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * MCP (Model Context Protocol) Core Service.
 * Provides standardized interfaces for LLM tool calling and resource access.
 */
@SpringBootApplication
@EnableAspectJAutoProxy
public class McpApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}

