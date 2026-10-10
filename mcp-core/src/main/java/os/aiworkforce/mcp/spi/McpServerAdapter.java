// @find: mcp server adapter, connector adapter interface, server, tools, invoke, health check, check credential, all scopes, is sandbox, vendor adapter contract, add a new connector
// @what: Interface every connector adapter implements: name, tools, run a tool, check a credential.
// @flow: Implemented by SandboxServerAdapter and LiveServerAdapter; used by ToolGateway
package os.aiworkforce.mcp.spi;

import java.util.List;
import java.util.Optional;

import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;

/**
 * What a Model Context Protocol server must be able to do.
 *
 * <p>An adapter translates one vendor's API into the platform's vocabulary and nothing more. It
 * does not decide whether the agent is allowed to call the tool, whether an approval is needed,
 * or whether a rate limit applies: those decisions belong to {@code ToolGateway}, which applies
 * them identically to every server. An adapter that made its own would make the governance
 * depend on which vendor was involved.
 */
public interface McpServerAdapter {

    /** The server this adapter serves, for example {@code gmail}. */
    String server();

    /** Every tool it offers. Read at startup and cached; a change needs a restart or a refresh. */
    List<ToolDefinition> tools();

    /**
     * Runs one tool.
     *
     * <p>A timeout on a non-idempotent tool must be reported as indeterminate rather than as a
     * failure. The distinction decides whether the platform may try again.
     */
    Mono<ToolResult> invoke(ToolInvocation invocation, String credential);

    /** Whether the server is reachable and the credential is accepted. */
    Mono<Boolean> healthCheck(String credential);

    /**
     * Checks a credential and says, in plain words, what was found.
     *
     * <p>A live adapter overrides this with a real "who am I" call so the console can show which
     * account a token belongs to. The default only restates {@link #healthCheck}.
     */
    default Mono<ConnectionCheck> check(String credential) {
        return healthCheck(credential)
                .map(ok -> ok ? ConnectionCheck.passed(null) : ConnectionCheck.failed("The connection check failed."));
    }

    /** Scopes this server needs in total, for the consent screen. */
    default List<String> allScopes() {
        return tools().stream()
                .flatMap(tool -> tool.requiredScopes().stream())
                .distinct()
                .toList();
    }

    default Optional<ToolDefinition> tool(String name) {
        return tools().stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    /** Whether this adapter talks to a real provider or to the seeded fixture store. */
    default boolean isSandbox() {
        return false;
    }
}
