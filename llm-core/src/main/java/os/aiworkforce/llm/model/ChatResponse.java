// @find: model router, LLM, model providers, chat response, model answer, tokens used, cost, which provider answered, tool calls, finish reason, ChatResponse
// @what: What a model returned, plus cost, usage and who answered.
// @flow: Returned by ModelRouter.route and provider adapters.
package os.aiworkforce.llm.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a model returned, plus what it cost and who answered.
 *
 * <p>The provider and model are carried on the response rather than assumed from the request,
 * because under fallover the model that answered is frequently not the one first asked. Showing
 * a person "answered by Groq, after OpenRouter timed out" is the difference between a system that
 * degrades visibly and one that degrades silently.
 *
 * @param content the text, null when the model only called tools
 * @param toolCalls tools the model wants run
 * @param finishReason why generation stopped
 * @param usage what the attempt consumed
 * @param provider the provider that answered
 * @param model the model that answered
 * @param latency wall-clock time for the successful attempt
 * @param attempts every attempt made, including the failures before this one
 * @param providerMetadata raw provider fields kept for the trace
 * @param compactedConversation when the router had to shorten the conversation to get this
 *     answer, the shortened messages it actually sent, system turns included; null when it sent
 *     the conversation as given. A caller that keeps a conversation across turns adopts it, or
 *     every later turn overflows and is compacted again from scratch.
 */
public record ChatResponse(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        String provider,
        String model,
        Duration latency,
        List<AttemptRecord> attempts,
        Map<String, String> providerMetadata,
        List<ChatMessage> compactedConversation) {

    public ChatResponse {
        Objects.requireNonNull(finishReason, "finishReason");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.NONE : usage;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        compactedConversation = compactedConversation == null ? null : List.copyOf(compactedConversation);
    }

    /** A response as a provider builds it: nothing has been compacted at that level. */
    public ChatResponse(
            String content,
            List<ToolCall> toolCalls,
            FinishReason finishReason,
            TokenUsage usage,
            String provider,
            String model,
            Duration latency,
            List<AttemptRecord> attempts,
            Map<String, String> providerMetadata) {
        this(content, toolCalls, finishReason, usage, provider, model, latency, attempts, providerMetadata, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean isTruncated() {
        return finishReason.isTruncated();
    }

    /** True when the answer came from a candidate other than the first one tried. */
    public boolean usedFallback() {
        return attempts.size() > 1;
    }

    /** The response with the full attempt history attached, added by the router. */
    public ChatResponse withAttempts(List<AttemptRecord> history) {
        return new ChatResponse(
                content,
                toolCalls,
                finishReason,
                usage,
                provider,
                model,
                latency,
                history,
                providerMetadata,
                compactedConversation);
    }

    /** The response carrying the shortened conversation the router sent, added by the router. */
    public ChatResponse withCompactedConversation(List<ChatMessage> messages) {
        return new ChatResponse(
                content, toolCalls, finishReason, usage, provider, model, latency, attempts, providerMetadata, messages);
    }

    /** True when the router shortened the conversation to get this answer. */
    public boolean wasCompacted() {
        return compactedConversation != null;
    }

    /** The answer as one message, ready to append to the conversation. */
    public ChatMessage asMessage() {
        return ChatMessage.assistantToolCalls(content, toolCalls);
    }
}
