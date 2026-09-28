package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Runs;

class ChatGoalListenerTest {

    private static final UUID ORG = UUID.randomUUID();

    private ChatMessages messages;
    private Conversations conversations;
    private Runs runs;
    private ChatGoalListener listener;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        messages = mock(ChatMessages.class);
        conversations = mock(Conversations.class);
        runs = mock(Runs.class);
        listener = new ChatGoalListener(messages, conversations, runs);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Goal goalFor(UUID conversationId) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setConversationId(conversationId);
        return goal;
    }

    private Task taskFor(Goal goal, String result, String failureReason) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setAgentId(UUID.randomUUID());
        task.setResult(result);
        task.setFailureReason(failureReason);
        return task;
    }

    @Test
    @DisplayName("a completed task appends an answer message carrying the task's result")
    void completedTaskAppendsAnswer() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, "The email has been sent.", null);
        Run run = new Run();
        run.setId(UUID.randomUUID());
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));

        listener.onTaskFinished(goal, task, "completed");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        ChatMessage message = saved.getValue();
        assertThat(message.getKind()).isEqualTo("answer");
        assertThat(message.getAuthorKind()).isEqualTo("agent");
        assertThat(message.getAgentId()).isEqualTo(task.getAgentId());
        assertThat(message.getContent()).isEqualTo("The email has been sent.");
        assertThat(message.getGoalId()).isEqualTo(goal.getId());
        assertThat(message.getDetail())
                .containsEntry("taskId", task.getId().toString())
                .containsEntry("runId", run.getId().toString())
                .containsEntry("agentId", task.getAgentId().toString());
        assertThat(conversation.getMessageCount()).isEqualTo(1);
        assertThat(conversation.getLastMessagePreview()).isEqualTo("The email has been sent.");
    }

    @Test
    @DisplayName("a failed task appends an error message carrying the failure reason")
    void failedTaskAppendsError() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, "The tool call was refused.");
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.empty());

        listener.onTaskFinished(goal, task, "failed");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        ChatMessage message = saved.getValue();
        assertThat(message.getKind()).isEqualTo("error");
        assertThat(message.getContent()).isEqualTo("The tool call was refused.");
        assertThat(message.getDetail()).containsEntry("reason", "The tool call was refused.");
    }

    @Test
    @DisplayName("a goal with no conversation is ignored entirely")
    void goalWithNoConversationIgnored() {
        Goal goal = goalFor(null);
        Task task = taskFor(goal, "Done.", null);

        listener.onTaskFinished(goal, task, "completed");

        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("a skipped task is not narrated - the goal's own status already shows it")
    void skippedIgnored() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, null);

        listener.onTaskFinished(goal, task, "skipped");

        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("a cancelled task says in the thread that the work was stopped, and why")
    void cancelledNarrated() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, "An approver rejected the action this run needed.");

        listener.onTaskFinished(goal, task, "cancelled");

        verify(messages).save(org.mockito.ArgumentMatchers.argThat(message ->
                "error".equals(message.getKind())
                        && message.getContent().equals("Stopped: An approver rejected the action this run needed.")));
    }
}
