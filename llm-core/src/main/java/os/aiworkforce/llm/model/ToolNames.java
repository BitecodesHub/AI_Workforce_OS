package os.aiworkforce.llm.model;

/**
 * Tool names as providers accept them.
 *
 * <p>The platform names a tool {@code server.tool} ({@code gmail.draft_message}), but the major
 * function-calling APIs only accept {@code [a-zA-Z0-9_-]{1,64}} as a function name. Some routes
 * tolerate the dot and others reject the whole request with a 400 - Gemini through OpenRouter does
 * - which reads as a provider failure and silently pushes every tool-using run down the fallback
 * chain. Every adapter therefore sends {@link #toWire} and reads calls back through
 * {@link #fromWire}, so the rest of the platform only ever sees the dotted form.
 */
public final class ToolNames {

    private static final String DOT_ON_THE_WIRE = "__";

    private ToolNames() {}

    /** {@code gmail.draft_message} → {@code gmail__draft_message}. */
    public static String toWire(String name) {
        return name == null ? null : name.replace(".", DOT_ON_THE_WIRE);
    }

    /** {@code gmail__draft_message} → {@code gmail.draft_message}; a name without the marker is unchanged. */
    public static String fromWire(String name) {
        return name == null ? null : name.replace(DOT_ON_THE_WIRE, ".");
    }
}
