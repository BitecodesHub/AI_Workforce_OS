// @find: model router, LLM, model providers, finish reason, stop, length, truncated answer, tool calls, content filter, FinishReason
// @what: Why generation stopped, such as finished, cut by length or content filter.
package os.aiworkforce.llm.model;

/**
 * Why generation stopped.
 *
 * <p>The distinction that matters most is between {@link #STOP} and {@link #LENGTH}: the first is
 * a complete answer, the second is a sentence cut in half. Treating them alike is how a truncated
 * email gets sent, so the router surfaces {@code LENGTH} rather than returning it as success.
 */
public enum FinishReason {
    /** The model finished on its own. */
    STOP,
    /** The output limit was reached; the answer is incomplete. */
    LENGTH,
    /** The model wants tools run before it continues. */
    TOOL_CALLS,
    /** The provider's safety system stopped it. Not a fault, and not retryable. */
    CONTENT_FILTER,
    /** A configured stop sequence matched. */
    STOP_SEQUENCE,
    /** The stream ended without a terminal signal; treat the output as partial. */
    INCOMPLETE,
    /** The provider gave a reason this platform does not recognise. */
    UNKNOWN;

    public boolean isComplete() {
        return this == STOP || this == STOP_SEQUENCE || this == TOOL_CALLS;
    }

    public boolean isTruncated() {
        return this == LENGTH || this == INCOMPLETE;
    }
}
