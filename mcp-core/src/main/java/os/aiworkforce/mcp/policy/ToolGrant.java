// @find: tool grant, agent permissions, allowed tools, scopes, require approval, max calls per run, grant enabled, which agent may use which connector, agent connector access
// @what: Record describing what one agent may do with one connector server.
// @flow: Loaded per agent by the orchestrator; checked by ToolGateway
package os.aiworkforce.mcp.policy;

import java.util.List;
import java.util.Objects;

/**
 * What one agent may do with one server.
 *
 * <p>A grant is deliberately narrow. It names the server, the individual tools, and the scopes -
 * so "the HR agent may read the calendar and draft email, but not send it" is expressible, and
 * is the kind of thing an administrator will actually want.
 *
 * @param agentId the agent the grant belongs to
 * @param server the server it may reach
 * @param allowedTools tool names; empty means every tool the server offers
 * @param scopes scopes this grant carries
 * @param requireApproval forces a gate even on tools that would not otherwise need one
 * @param maxCallsPerRun ceiling on invocations within a single run
 * @param enabled whether the grant is currently in force
 */
public record ToolGrant(
        String agentId,
        String server,
        List<String> allowedTools,
        List<String> scopes,
        boolean requireApproval,
        Integer maxCallsPerRun,
        boolean enabled) {

    public ToolGrant {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(server, "server");
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /** An empty tool list means the whole server, which is the common case for a read-only one. */
    public boolean covers(String tool) {
        return enabled && (allowedTools.isEmpty() || allowedTools.contains(tool));
    }

    public boolean hasScopes(List<String> required) {
        return scopes.containsAll(required);
    }

    public List<String> missingScopes(List<String> required) {
        return required.stream().filter(scope -> !scopes.contains(scope)).toList();
    }
}
