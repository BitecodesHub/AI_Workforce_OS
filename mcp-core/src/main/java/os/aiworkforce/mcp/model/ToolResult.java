// @find: tool result, succeeded failed blocked indeterminate, timeout unknown outcome, tool output, result for model, never retry send, run trace summary
// @what: Record for what a tool produced, including the indeterminate status used when an outcome is unknown.
// @flow: Returned by adapters and ToolGateway.invoke; shown in run traces and audit
package os.aiworkforce.mcp.model;

import java.time.Duration;
import java.util.Map;

/**
 * What running a tool produced.
 *
 * <p>{@link Status#INDETERMINATE} is the case that matters and the one most systems omit. When a
 * request to send an email times out, the message may or may not have gone. Recording that as a
 * failure invites a retry that sends it twice; recording it as a success hides a message that
 * never arrived. It is recorded as unknown, surfaced to a person, and never retried automatically
 * for a tool that is not idempotent.
 *
 * @param status how it ended
 * @param contentJson the result, as JSON, to be fed back to the model
 * @param summary one sentence for the run trace and the audit entry
 * @param duration how long it took
 * @param metadata provider identifiers kept for reconciliation
 */
public record ToolResult(
        Status status, String contentJson, String summary, Duration duration, Map<String, String> metadata) {

    public enum Status {
        SUCCEEDED,
        /** The tool ran and refused: bad arguments, a missing record, a rejected action. */
        FAILED,
        /** The call did not confirm an outcome. The action may or may not have happened. */
        INDETERMINATE,
        /** Blocked before it ran: no grant, no scope, no approval, or a rate limit. */
        BLOCKED
    }

    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        duration = duration == null ? Duration.ZERO : duration;
    }

    public static ToolResult succeeded(String contentJson, String summary, Duration duration) {
        return new ToolResult(Status.SUCCEEDED, contentJson, summary, duration, Map.of());
    }

    public static ToolResult failed(String summary) {
        return new ToolResult(Status.FAILED, "{}", summary, Duration.ZERO, Map.of());
    }

    public static ToolResult blocked(String summary) {
        return new ToolResult(Status.BLOCKED, "{}", summary, Duration.ZERO, Map.of());
    }

    public static ToolResult indeterminate(String summary, Duration duration) {
        return new ToolResult(Status.INDETERMINATE, "{}", summary, duration, Map.of());
    }

    public boolean isSuccess() {
        return status == Status.SUCCEEDED;
    }

    /**
     * What the model is told.
     *
     * <p>A blocked or indeterminate call is reported to the model in plain words rather than as an
     * empty result, so it stops rather than retrying the same tool in a loop.
     */
    public String forModel() {
        return switch (status) {
            case SUCCEEDED -> contentJson;
            case FAILED -> "{\"error\":\"" + escape(summary) + "\"}";
            case BLOCKED -> "{\"error\":\"" + escape(summary) + "\",\"retry\":false}";
            case INDETERMINATE ->
                "{\"error\":\"" + escape(summary)
                        + "\",\"retry\":false,\"note\":\"The outcome is unknown. Do not repeat this action.\"}";
        };
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
