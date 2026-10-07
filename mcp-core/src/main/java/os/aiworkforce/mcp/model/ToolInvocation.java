package os.aiworkforce.mcp.model;

import java.util.Map;
import java.util.Objects;

/**
 * A request to run one tool.
 *
 * <p>Carries the agent and the run so the audit entry names both, and an idempotency key so a
 * retried invocation reaches the provider as one action rather than two.
 *
 * @param orgId the workspace the action belongs to
 * @param agentId the agent asking
 * @param runId the run it is part of
 * @param server the server to call
 * @param tool the tool to run
 * @param argumentsJson arguments, exactly as the model produced them
 * @param idempotencyKey stable across retries of the same logical action
 * @param context correlation values recorded with the invocation
 */
public record ToolInvocation(
        String orgId,
        String agentId,
        String runId,
        String server,
        String tool,
        String argumentsJson,
        String idempotencyKey,
        Map<String, String> context) {

    public ToolInvocation {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(tool, "tool");
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** The same invocation with its arguments replaced, for example after they were normalised. */
    public ToolInvocation withArguments(String arguments) {
        return new ToolInvocation(orgId, agentId, runId, server, tool, arguments, idempotencyKey, context);
    }

    public String qualifiedName() {
        return server + "." + tool;
    }
}
