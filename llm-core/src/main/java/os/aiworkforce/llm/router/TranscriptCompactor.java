// @find: model router, LLM, model providers, compaction, shorten conversation, context window overflow, long running agents, truncate tool results, TranscriptCompactor
// @what: Shortens a conversation that no longer fits a model's window.
// @flow: Called by ModelRouter before retrying a too-long request.
package os.aiworkforce.llm.router;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.TokenEstimate;

/**
 * Shortens a conversation that no longer fits.
 *
 * <p>Long-running agents overflow their window as a matter of course, not as an exception: a task
 * that runs for twenty turns with tool results attached will exceed any window eventually. The
 * alternatives to compaction are failing the run or silently losing the earliest instructions, and
 * both are worse than a visible, rule-governed summary.
 *
 * <p>Three rules, in order, because each protects something the next would otherwise destroy:
 *
 * <ol>
 *   <li><b>The system prompt is never dropped.</b> It holds the agent's instructions and its
 *       guardrails. An agent that forgets its own constraints mid-task is worse than one that
 *       stops.
 *   <li><b>The most recent turns are never dropped.</b> They hold the immediate task and the
 *       results the model is currently reasoning about. An oversized tool result among them is
 *       shortened, though, because one list call against a live system can be larger than the
 *       whole window, and it is usually what made the prompt too large in the first place.
 *   <li><b>The middle is summarised, not deleted.</b> Deleting it would leave a tool result
 *       answering a call that is no longer in the transcript, which several providers reject
 *       outright and all of them handle badly.
 * </ol>
 *
 * <p>The summary is produced mechanically rather than by asking a model. Calling a model to
 * recover from a model call that just failed adds a second point of failure to an error path, and
 * the failure being recovered from is frequently that no model is answering at all.
 */
@Component
public class TranscriptCompactor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCompactor.class);

    /** Turns at the end that are always kept, though an oversized tool result among them is cut. */
    private static final int PROTECTED_RECENT_TURNS = 6;

    /** Fraction of the window to aim for, leaving room for the answer. */
    private static final double TARGET_FILL = 0.55;

    /** How much of the original request the summary keeps: the task, in full, within reason. */
    static final int REQUEST_KEEP_CHARS = 2_000;

    /** A tool result is never cut below this; past it, the result says little enough to be useless. */
    static final int MIN_TOOL_RESULT_CHARS = 1_000;

    /** The line chat puts between its context preamble and what the person actually asked. */
    private static final String REQUEST_MARKER = "Request:";

    /** How a shortened tool result begins, so a second compaction can find the original text. */
    private static final String SHORTENED_PREFIX = "{\"truncated\":true,";

    private static final ObjectMapper JSON = new ObjectMapper();

    // @find: compact conversation to fit context window
    /**
     * Returns a shortened request, or null when nothing more can be removed.
     *
     * <p>Aims for a little over half of {@code model}'s window. The result can still be over that
     * aim when the protected turns alone are larger; the caller re-checks whether it fits.
     *
     * <p>Null means the caller should move to a model with a larger window rather than keep
     * trimming: past this point the only thing left to cut is the current task itself.
     */
    public ChatRequest compact(ChatRequest request, ModelSpec model) {
        List<ChatMessage> messages = request.messages();
        int budget = (int) (model.contextWindowTokens() * TARGET_FILL);
        int before = TokenEstimate.forRequest(request);

        List<ChatMessage> system = messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.SYSTEM)
                .toList();
        List<ChatMessage> conversation = messages.stream()
                .filter(message -> message.role() != ChatMessage.Role.SYSTEM)
                .toList();

        List<ChatMessage> compacted = new ArrayList<>(system);
        int keepFrom = conversation.size() <= PROTECTED_RECENT_TURNS
                ? 0
                // A tool result must keep the assistant turn that requested it, otherwise the
                // transcript contains an answer to a question nobody asked.
                : adjustForToolPairing(conversation, conversation.size() - PROTECTED_RECENT_TURNS);
        if (keepFrom > 0) {
            compacted.add(ChatMessage.system(summarise(conversation.subList(0, keepFrom))));
        }
        compacted.addAll(conversation.subList(keepFrom, conversation.size()));

        ChatRequest result = request.withMessages(compacted);
        if (TokenEstimate.forRequest(result) > budget) {
            result = shrinkToolResults(result, budget);
        }
        int after = TokenEstimate.forRequest(result);

        if (after >= before) {
            log.debug("Nothing left to compact: about {} tokens against a budget of {}", before, budget);
            return null;
        }
        log.info("Compacted transcript from about {} to about {} tokens (budget {})", before, after, budget);
        return result;
    }

    /**
     * Moves the cut backwards so a tool result is never separated from its call.
     *
     * <p>Every provider rejects, or badly mishandles, a tool result whose originating call is
     * missing. Cutting on a clean boundary costs a few extra turns of context and avoids a class
     * of failure that is very hard to read from a trace.
     */
    private int adjustForToolPairing(List<ChatMessage> conversation, int keepFrom) {
        int index = keepFrom;
        while (index > 0 && conversation.get(index).role() == ChatMessage.Role.TOOL) {
            index--;
        }
        return index;
    }

    /**
     * Cuts every tool result longer than one shared limit, the largest limit that brings the
     * request within {@code budget}, and never below {@link #MIN_TOOL_RESULT_CHARS}.
     *
     * <p>One shared limit rather than cutting the newest or the oldest first: the result the
     * model is reasoning about is usually the newest and the one that set the task is usually the
     * oldest, so neither end is safe to sacrifice whole. The cut keeps the beginning, which is
     * where list responses put their count and first items, and says plainly that it is a cut.
     */
    private ChatRequest shrinkToolResults(ChatRequest request, int budget) {
        List<ChatMessage> messages = request.messages();
        int longest = 0;
        for (ChatMessage message : messages) {
            if (isToolResult(message)) {
                longest = Math.max(longest, originalText(message.content()).text().length());
            }
        }
        if (longest <= MIN_TOOL_RESULT_CHARS) {
            return request;
        }

        int low = MIN_TOOL_RESULT_CHARS;
        int high = longest;
        while (low < high) {
            int limit = low + (high - low + 1) / 2;
            if (TokenEstimate.forRequest(withToolResultsCut(request, limit)) <= budget) {
                low = limit;
            } else {
                high = limit - 1;
            }
        }
        return withToolResultsCut(request, low);
    }

    private static ChatRequest withToolResultsCut(ChatRequest request, int limit) {
        List<ChatMessage> cut = new ArrayList<>(request.messages().size());
        for (ChatMessage message : request.messages()) {
            if (isToolResult(message)) {
                cut.add(new ChatMessage(
                        message.role(),
                        shorten(message.content(), limit),
                        message.toolCalls(),
                        message.toolCallId(),
                        message.name()));
            } else {
                cut.add(message);
            }
        }
        return request.withMessages(cut);
    }

    private static boolean isToolResult(ChatMessage message) {
        return message.role() == ChatMessage.Role.TOOL && message.content() != null;
    }

    /** The text a tool result started as, looking through a cut made by an earlier compaction. */
    private record Original(String text, int length) {}

    private static Original originalText(String content) {
        if (content.startsWith(SHORTENED_PREFIX)) {
            try {
                JsonNode node = JSON.readTree(content);
                String text = node.path("text").asText("");
                return new Original(text, node.path("originalCharacters").asInt(text.length()));
            } catch (JsonProcessingException e) {
                // Not one of ours after all; treat it as ordinary text.
            }
        }
        return new Original(content, content.length());
    }

    /**
     * A tool result cut to about {@code limit} characters, as valid JSON that says it was cut.
     *
     * <p>Valid JSON rather than the text with an ellipsis on the end: a model given half a JSON
     * document tends to "repair" it with invented values, and says nothing about having done so.
     */
    static String shorten(String content, int limit) {
        if (content.length() <= limit) {
            return content;
        }
        // Cut from the original text, so a second compaction never wraps the first one's wrapper.
        Original original = originalText(content);
        // Room for the wrapper's own fields, so the whole value stays near the limit.
        int keep = Math.min(original.text().length(), Math.max(0, limit - 200));
        if (keep > 0 && Character.isHighSurrogate(original.text().charAt(keep - 1))) {
            keep--;
        }
        String head = original.text().substring(0, keep);
        ObjectNode node = JSON.createObjectNode();
        node.put("truncated", true);
        node.put("originalCharacters", original.length());
        node.put("shownCharacters", head.length());
        node.put(
                "note",
                "This result was shortened to fit the model's context window. Ask again with a narrower"
                        + " query, or for the next page, if the rest is needed.");
        node.put("text", head);
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Writing a tree of strings and numbers cannot fail; this keeps the compiler content.
            throw new IllegalStateException(e);
        }
    }

    /**
     * A factual summary of the turns being removed.
     *
     * <p>Deliberately mechanical: it says what happened rather than interpreting it. An invented
     * summary of a conversation the model can no longer see is a source of confident errors, and
     * the model has no way to tell that the summary is the unreliable part.
     */
    private String summarise(List<ChatMessage> older) {
        long userTurns =
                older.stream().filter(m -> m.role() == ChatMessage.Role.USER).count();
        long assistantTurns = older.stream()
                .filter(m -> m.role() == ChatMessage.Role.ASSISTANT)
                .count();
        List<String> toolsUsed = older.stream()
                .flatMap(message -> message.toolCalls().stream())
                .map(call -> call.name())
                .distinct()
                .limit(12)
                .toList();

        StringBuilder summary = new StringBuilder();
        summary.append("Earlier in this conversation, ")
                .append(userTurns)
                .append(" message(s) from the person and ")
                .append(assistantTurns)
                .append(" reply or replies were removed to fit the model's context window.");
        if (!toolsUsed.isEmpty()) {
            summary.append(" Tools used during that part: ")
                    .append(String.join(", ", toolsUsed))
                    .append(".");
        }

        // The first request is kept: it is the task, and losing it is how an agent finishes
        // something nobody asked for.
        older.stream()
                .filter(message -> message.role() == ChatMessage.Role.USER && message.content() != null)
                .findFirst()
                .ifPresent(first -> summary
                        .append(" The original request was: \"")
                        .append(originalRequest(first.content()))
                        .append("\""));

        summary.append(" Treat the removed detail as unavailable rather than as agreed.");
        return summary.toString();
    }

    /**
     * The part of a first message that is the request itself.
     *
     * <p>Chat sends its context first - the thread so far, document passages - and the person's
     * words after a closing "Request:" line. Keeping the start of such a message kept the
     * preamble and lost the request, so the text after the last marker is kept instead, and the
     * start of the message only when there is no marker.
     */
    static String originalRequest(String content) {
        String text = content.strip();
        int marker = text.lastIndexOf(REQUEST_MARKER);
        if (marker >= 0) {
            String request = text.substring(marker + REQUEST_MARKER.length()).strip();
            if (!request.isEmpty()) {
                text = request;
            }
        }
        if (text.length() > REQUEST_KEEP_CHARS) {
            int keep = REQUEST_KEEP_CHARS - 1;
            if (Character.isHighSurrogate(text.charAt(keep - 1))) {
                keep--;
            }
            text = text.substring(0, keep) + "…";
        }
        return text;
    }
}
