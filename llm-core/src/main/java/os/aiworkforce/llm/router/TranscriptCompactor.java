package os.aiworkforce.llm.router;

import java.util.ArrayList;
import java.util.List;

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
 *       results the model is currently reasoning about.
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

    /** Turns at the end that are always kept verbatim. */
    private static final int PROTECTED_RECENT_TURNS = 6;

    /** Fraction of the window to aim for, leaving room for the answer. */
    private static final double TARGET_FILL = 0.55;

    /**
     * Returns a shortened request, or null when nothing more can be removed.
     *
     * <p>Null means the caller should move to a model with a larger window rather than keep
     * trimming: past this point the only thing left to cut is the current task itself.
     */
    public ChatRequest compact(ChatRequest request, ModelSpec model) {
        List<ChatMessage> messages = request.messages();
        int budget = (int) (model.contextWindowTokens() * TARGET_FILL);

        List<ChatMessage> system = messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.SYSTEM)
                .toList();
        List<ChatMessage> conversation = messages.stream()
                .filter(message -> message.role() != ChatMessage.Role.SYSTEM)
                .toList();

        if (conversation.size() <= PROTECTED_RECENT_TURNS) {
            log.debug("Nothing left to compact: only {} turns remain", conversation.size());
            return null;
        }

        int keepFrom = conversation.size() - PROTECTED_RECENT_TURNS;
        // A tool result must keep the assistant turn that requested it, otherwise the transcript
        // contains an answer to a question nobody asked.
        keepFrom = adjustForToolPairing(conversation, keepFrom);

        List<ChatMessage> recent = conversation.subList(keepFrom, conversation.size());
        List<ChatMessage> older = conversation.subList(0, keepFrom);

        List<ChatMessage> compacted = new ArrayList<>(system);
        compacted.add(ChatMessage.system(summarise(older)));
        compacted.addAll(recent);

        ChatRequest result = request.withMessages(compacted);
        int before = TokenEstimate.forRequest(request);
        int after = TokenEstimate.forRequest(result);

        if (after >= before) {
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
     * A factual summary of the turns being removed.
     *
     * <p>Deliberately mechanical: it says what happened rather than interpreting it. An invented
     * summary of a conversation the model can no longer see is a source of confident errors, and
     * the model has no way to tell that the summary is the unreliable part.
     */
    private String summarise(List<ChatMessage> older) {
        long userTurns = older.stream().filter(m -> m.role() == ChatMessage.Role.USER).count();
        long assistantTurns = older.stream().filter(m -> m.role() == ChatMessage.Role.ASSISTANT).count();
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
            summary.append(" Tools used during that part: ").append(String.join(", ", toolsUsed)).append(".");
        }

        // The first request is kept verbatim: it is the task, and losing it is how an agent
        // finishes something nobody asked for.
        older.stream()
                .filter(message -> message.role() == ChatMessage.Role.USER && message.content() != null)
                .findFirst()
                .ifPresent(first -> {
                    String text = first.content().strip();
                    if (text.length() > 600) {
                        text = text.substring(0, 597) + "…";
                    }
                    summary.append(" The original request was: \"").append(text).append("\"");
                });

        summary.append(" Treat the removed detail as unavailable rather than as agreed.");
        return summary.toString();
    }
}
