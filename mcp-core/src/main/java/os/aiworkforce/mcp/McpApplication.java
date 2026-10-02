

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

