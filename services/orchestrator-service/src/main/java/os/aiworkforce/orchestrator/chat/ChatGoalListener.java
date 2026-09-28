package os.aiworkforce.orchestrator.chat;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener;

/**
 * Turns a task's outcome into a reply in the conversation that asked for it.
 *
 * <p>{@link os.aiworkforce.orchestrator.service.TaskProgress} calls every listener the moment a
 * task finishes, from inside the same write - this is what lets a person watching a conversation
 * see an agent's answer land the instant its task completes, without anything in the engine
 * package knowing that chat exists.
 */
@Component
public class ChatGoalListener implements GoalLifecycleListener {

    private static final String DEFAULT_FAILURE_REASON = "The agent did not complete this task.";

    private final ChatMessages messages;
    private final Conversations conversations;
    private final Runs runs;

    public ChatGoalListener(ChatMessages messages, Conversations conversations, Runs runs) {
        this.messages = messages;
        this.conversations = conversations;
        this.runs = runs;
    }

    @Override
    @Transactional
    public void onTaskFinished(Goal goal, Task task, String status) {
        UUID conversationId = goal.getConversationId();
        if (conversationId == null) {
            return;
        }
        if ("completed".equals(status)) {
            append(goal, conversationId, task, "answer",
                    task.getResult() == null ? "" : task.getResult(),
                    Map.of(
                            "taskId", task.getId().toString(),
                            "runId", runIdOf(task),
                            "agentId", task.getAgentId() == null ? "" : task.getAgentId().toString()));
        } else if ("failed".equals(status)) {
            String reason = task.getFailureReason() == null ? DEFAULT_FAILURE_REASON : task.getFailureReason();
            append(goal, conversationId, task, "error", reason, Map.of("reason", reason));
        } else if ("cancelled".equals(status)) {
            // A rejected approval or a stopped run ends the work; without a line in the thread the
            // conversation would still read as though the agent were working on it.
            String reason = task.getFailureReason() == null || task.getFailureReason().isBlank()
                    ? "This work was stopped before it finished."
                    : "Stopped: " + task.getFailureReason();
            append(goal, conversationId, task, "error", reason, Map.of("reason", reason));
        }
        // Skipped tasks are ordinary chain outcomes with nothing new to tell a person that the
        // progress card, driven off the goal itself, does not already show.
    }

    private String runIdOf(Task task) {
        return runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId()).map(Run::getId).map(UUID::toString).orElse("");
    }

    private void append(Goal goal, UUID conversationId, Task task, String kind, String content, Map<String, Object> detail) {
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        int position = conversation.nextPosition();
        conversation.recordMessage(content);
        conversations.save(conversation);
        messages.save(os.aiworkforce.orchestrator.domain.ChatMessage.of(
                goal.getOrgId(), conversationId, position, "agent", null, task.getAgentId(),
                kind, content, detail, goal.getId()));
    }
}
