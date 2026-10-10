// @find: model router, LLM, model providers, tool names, server__tool names, wire names, gmail.draft_message, tool name sanitising, person tools, memory tools, toWire, fromWire, ToolNames
// @what: Converts tool names such as server.tool to and from the form providers accept.
// @flow: Used by provider adapters and orchestrator-service engine.
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

    /**
     * The server name of the tools a person answers rather than a connected server, such as
     * {@code person.ask_question}. No adapter may register under it.
     */
    public static final String PERSON_SERVER = "person";

    /**
     * The server name of the tools an agent uses on its own memory, {@code memory.remember} and
     * {@code memory.recall}. Like {@code person}, no adapter may register under it.
     */
    public static final String MEMORY_SERVER = "memory";

    private ToolNames() {}

    // @find: is this a person tool
    /** A tool answered by a person rather than a server: person.ask_question, in either form. */
    public static boolean isPersonTool(String name) {
        return name != null
                && (name.startsWith(PERSON_SERVER + ".") || name.startsWith(PERSON_SERVER + DOT_ON_THE_WIRE));
    }

    // @find: is this a memory tool
    /** A tool an agent uses on its own memory: memory.remember or memory.recall, in either form. */
    public static boolean isMemoryTool(String name) {
        return name != null
                && (name.startsWith(MEMORY_SERVER + ".") || name.startsWith(MEMORY_SERVER + DOT_ON_THE_WIRE));
    }

    // @find: tool name to provider form, dot to double underscore
    /** {@code gmail.draft_message} → {@code gmail__draft_message}. */
    public static String toWire(String name) {
        return name == null ? null : name.replace(".", DOT_ON_THE_WIRE);
    }

    // @find: tool name from provider form
    /** {@code gmail__draft_message} → {@code gmail.draft_message}; a name without the marker is unchanged. */
    public static String fromWire(String name) {
        return name == null ? null : name.replace(DOT_ON_THE_WIRE, ".");
    }
}
