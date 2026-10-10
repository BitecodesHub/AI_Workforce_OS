// @find: tool definition, tool spec, side effect, read write outbound destructive, required scopes, idempotent, timeout, rate limit, always requires approval, qualified name server.tool, tools list, connector tools
// @what: Record defining one tool of one connector, including its side-effect class which decides whether approval is needed.
// @flow: Declared in SandboxServerRegistry; read by ToolGateway and the live adapters
package os.aiworkforce.mcp.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import os.aiworkforce.llm.model.ToolSpec;

/**
 * One tool offered by one Model Context Protocol server.
 *
 * <p>The definition is a row, not a class, so connecting a new server is configuration. Three
 * fields carry the governance and are worth stating plainly:
 *
 * <ul>
 *   <li>{@code sideEffect} decides whether an approval gate applies, independently of what any
 *       policy says. Sending an email leaves the workspace and cannot be recalled.
 *   <li>{@code requiredScopes} is what the agent's grant is checked against. A tool whose scopes
 *       the agent lacks is never described to the model at all, so the model cannot keep reaching
 *       for a capability it will only be refused.
 *   <li>{@code idempotent} decides whether a timeout may be retried. For a tool that is not, an
 *       unknown outcome stays unknown rather than being repeated.
 * </ul>
 *
 * @param server the server offering it, for example {@code gmail}
 * @param name the tool identifier, unique within the server
 * @param description written for the model rather than for a person
 * @param parametersJson JSON Schema for the arguments, used to validate and to prompt
 * @param sideEffect what running it does
 * @param requiredScopes scopes the agent's grant must include
 * @param idempotent whether repeating the identical call is safe
 * @param defaultTimeout how long one invocation may take
 * @param rateLimitPerMinute client-side ceiling, kept below the provider's own
 */
public record ToolDefinition(
        String server,
        String name,
        String description,
        String parametersJson,
        ToolSpec.SideEffect sideEffect,
        List<String> requiredScopes,
        boolean idempotent,
        Duration defaultTimeout,
        Integer rateLimitPerMinute) {

    public ToolDefinition {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(name, "name");
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        sideEffect = sideEffect == null ? ToolSpec.SideEffect.READ : sideEffect;
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(30) : defaultTimeout;
    }

    /** The fully qualified name the model sees, for example {@code gmail.send_message}. */
    public String qualifiedName() {
        return server + "." + name;
    }

    // @find: always requires approval, outbound destructive approval gate, cannot be disabled
    /**
     * Whether this tool always needs approval, whatever the workspace policy says.
     *
     * <p>A policy can widen the gate but never remove it for these two classes. An agent that can
     * delete a repository or send mail on somebody's behalf without a person seeing it first is
     * the failure that ends trust in the whole platform, and no configuration should be able to
     * produce it by accident.
     */
    public boolean alwaysRequiresApproval() {
        return sideEffect == ToolSpec.SideEffect.OUTBOUND || sideEffect == ToolSpec.SideEffect.DESTRUCTIVE;
    }

    // @find: tool shown to model, tool spec for LLM
    /** The shape the model is shown, derived so the two can never drift apart. */
    public ToolSpec toSpec() {
        return new ToolSpec(qualifiedName(), description, parametersJson, sideEffect);
    }
}
