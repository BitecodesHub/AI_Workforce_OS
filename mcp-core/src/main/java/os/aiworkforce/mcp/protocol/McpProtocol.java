// @find: mcp protocol types, model context protocol, tool call, resource, initialize request, capabilities, protocol records
// @what: Plain records for the Model Context Protocol messages.
// @flow: Reference types; not on the main tool call path
package os.aiworkforce.mcp.protocol;

import java.util.List;
import java.util.Map;

/**
 * Core MCP protocol types for tool calling and resource access.
 */
public class McpProtocol {

    /** A request to run a named tool with its arguments. */
    public record ToolCall(String name, Map<String, Object> arguments) {}
    
    /**
     * Outcome of a tool call as the protocol describes it. Distinct from the richer
     * {@code model.ToolResult} the platform uses internally.
     */
    public record ToolResult(boolean success, Object result, String error) {}
    
    /** Description of a readable resource a server exposes. */
    public record Resource(String uri, String name, String description, String mimeType) {}
    
    /** The content of a resource, as text or as a binary blob. */
    public record ResourceContent(String uri, String mimeType, String text, byte[] blob) {}
    
    /** First message a client sends: the protocol version and what the client supports. */
    public record InitializeRequest(String protocolVersion, ClientCapabilities capabilities) {}
    
    /** Features the client supports. */
    public record ClientCapabilities(boolean tools, boolean resources, boolean prompts) {}
    
    /** Server reply to the initialize request. */
    public record InitializeResponse(String protocolVersion, ServerCapabilities capabilities) {}
    
    /** Features the server offers. */
    public record ServerCapabilities(boolean tools, boolean resources, boolean prompts) {}
}
