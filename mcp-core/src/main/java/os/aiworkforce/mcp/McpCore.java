// @find: component scan marker, mcp-core package scan, tool gateway beans, argument validator bean, sandbox servers bean, orchestrator uses mcp
// @what: Marker class so services can component-scan the mcp-core package.
// @flow: Scanned by integrations-service and the orchestrator to get ToolGateway and sandbox servers
package os.aiworkforce.mcp;

/**
 * Marker for component scanning.
 *
 * <p>The tool gateway, the argument validator and the sandbox servers are Spring beans. A service
 * that calls tools scans this package; one that does not, does not - which is why integrations
 * and the orchestrator include it and the others do not.
 */
public final class McpCore {

    private McpCore() {}
}
