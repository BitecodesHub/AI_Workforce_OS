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
