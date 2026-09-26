package os.aiworkforce.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * One turn in a conversation, in a shape every provider can be translated into.
 *
 * <p>The providers disagree about almost everything here: Anthropic keeps the system prompt
 * outside the message list, Gemini calls the assistant "model" and wraps content in parts,
 * Bedrock has its own Converse shape, and the OpenAI-compatible family puts tool results in a
 * message with a {@code tool_call_id}. Normalising once, here, means a fallback from one provider
 * to another is a routing decision rather than a rewrite of the conversation.
 *
 * @param role who is speaking
 * @param content the text, which is null for an assistant turn that only called tools
 * @param toolCalls tools the assistant asked to run
 * @param toolCallId the call this message answers, when the role is {@code TOOL}
 * @param name optional speaker name, used by some providers for multi-participant chats
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        Role role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null, null);
    }

    public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, content, calls, null, null);
    }

    /** The result of running a tool, fed back so the model can continue. */
    public static ChatMessage toolResult(String toolCallId, String toolName, String result) {
        return new ChatMessage(Role.TOOL, result, List.of(), toolCallId, toolName);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** Rough token cost, used to decide whether a candidate model can hold this conversation. */
    public int approximateTokens() {
        int textTokens = content == null ? 0 : TokenEstimate.forText(content);
        int toolTokens = toolCalls.stream()
                .mapToInt(call -> TokenEstimate.forText(call.name()) + TokenEstimate.forText(call.argumentsJson()))
                .sum();
        // Every provider adds framing per message - role markers, separators. Four tokens is the
        // conventional allowance and errs slightly high, which is the safe direction.
        return textTokens + toolTokens + 4;
    }
}
