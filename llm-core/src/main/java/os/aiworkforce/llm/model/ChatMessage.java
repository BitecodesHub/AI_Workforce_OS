// @find: model router, LLM, model providers, chat message, conversation turn, system user assistant tool role, message content, images, ChatMessage
// @what: One conversation turn in a provider-neutral shape.
// @flow: Translated by each provider adapter.
package os.aiworkforce.llm.model;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param images pictures sent with a user turn, for a model that can look at them; empty for every
 *     other turn. The router strips them, with a note in their place, for a model that cannot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        Role role, String content, List<ToolCall> toolCalls, String toolCallId, String name, List<ImagePart> images) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** A turn with no pictures, which is every turn but a person's message with images attached. */
    public ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
        this(role, content, toolCalls, toolCallId, name, List.of());
    }

    /** A person's turn with pictures beside its text. */
    public static ChatMessage userWithImages(String content, List<ImagePart> images) {
        return new ChatMessage(Role.USER, content, List.of(), null, null, images);
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    /**
     * The same turn with its pictures replaced by a note for each, for a model that cannot see them.
     *
     * @param model the model answering, as it is named to people
     */
    public ChatMessage withImagesAsNotes(String model) {
        if (images.isEmpty()) {
            return this;
        }
        StringBuilder text = new StringBuilder(content == null ? "" : content);
        for (ImagePart image : images) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(image.unreadableNote(model));
        }
        return new ChatMessage(role, text.toString(), toolCalls, toolCallId, name, List.of());
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
        return textTokens + toolTokens + images.size() * ImagePart.APPROXIMATE_TOKENS + 4;
    }
}
