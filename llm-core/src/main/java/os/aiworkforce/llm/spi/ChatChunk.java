package os.aiworkforce.llm.spi;

import java.util.List;

import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.model.ToolCall;

/**
 * One piece of a streamed answer.
 *
 * <p>Tool calls arrive fragmented across chunks in every provider that supports them - the name
 * in one, the arguments a few characters at a time across a dozen more. Adapters accumulate those
 * fragments and emit {@code toolCalls} only once complete, so a consumer never sees half a JSON
 * object and never has to reassemble one itself.
 *
 * @param delta the text produced since the previous chunk
 * @param toolCalls complete tool calls, emitted once assembled
 * @param finishReason set only on the terminal chunk
 * @param usage set only on the terminal chunk, where the provider reports it
 */
public record ChatChunk(String delta, List<ToolCall> toolCalls, FinishReason finishReason, TokenUsage usage) {

    public ChatChunk {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatChunk text(String delta) {
        return new ChatChunk(delta, List.of(), null, null);
    }

    public static ChatChunk tools(List<ToolCall> calls) {
        return new ChatChunk(null, calls, null, null);
    }

    public static ChatChunk terminal(FinishReason reason, TokenUsage usage) {
        return new ChatChunk(null, List.of(), reason, usage);
    }

    public boolean isTerminal() {
        return finishReason != null;
    }
}
