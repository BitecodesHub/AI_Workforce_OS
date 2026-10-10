// @find: model router, LLM, model providers, tool call, model asks to run tool, tool arguments json, function call, ToolCall
// @what: A tool the model asked to run, with raw JSON arguments.
package os.aiworkforce.llm.model;

import java.util.Objects;

/**
 * A tool the model asked to run.
 *
 * <p>{@code argumentsJson} is kept as raw text rather than a parsed map, deliberately. Models
 * return malformed JSON often enough that it is an ordinary case, not an exception: a trailing
 * comma, an unterminated string, or prose wrapped around the object. Parsing at the boundary
 * would throw away the original text that the repair pass needs in order to fix it.
 *
 * @param id the provider's identifier for this call, echoed back with the result
 * @param name the tool being called
 * @param argumentsJson arguments exactly as the model produced them
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
