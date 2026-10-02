package os.aiworkforce.mcp.protocol;

import java.util.List;
import java.util.Map;

/**
 * Core MCP protocol types for tool calling and resource access.
 */
public class McpProtocol {

    public record ToolCall(String name, Map<String, Object> arguments) {}
    
    public record ToolResult(boolean success, Object result, String error) {}
    
    public record Resource(String uri, String name, String description, String mimeType) {}
    
    public record ResourceContent(String uri, String mimeType, String text, byte[] blob) {}
    
    public record InitializeRequest(String protocolVersion, ClientCapabilities capabilities) {}
    
    public record ClientCapabilities(boolean tools, boolean resources, boolean prompts) {}
    
    public record InitializeResponse(String protocolVersion, ServerCapabilities capabilities) {}
    
    public record ServerCapabilities(boolean tools, boolean resources, boolean prompts) {}
}
