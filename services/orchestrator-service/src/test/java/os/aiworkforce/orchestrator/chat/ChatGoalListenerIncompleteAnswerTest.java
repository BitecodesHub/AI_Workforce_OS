package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;

/**
 * A run that stopped at its step or output limit still wrote something, and the task keeps it. The
 * error message in the conversation carries it, for the card to offer as an incomplete answer.
 */
class ChatGoalListenerIncompleteAnswerTest {

    private static final UUID ORG = UUID.randomUUID();

    private ChatMessages messages;
    private Runs runs;
    private RunSteps steps;
    private ChatGoalListener listener;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        messages = mock(ChatMessages.class);
        Conversations conversations = mock(Conversations.class);
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        ChatAppender appender = new ChatAppender(conversations, messages, mock(EntityManager.class));
        listener = new ChatGoalListener(appender, messages, runs, steps, mock(Agents.class), new ObjectMapper());

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Task failedTask(Goal goal, String result) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setAgentId(UUID.randomUUID());
        task.setStatus("failed");
        task.setResult(result);
        task.setFailureReason("The agent reached its step limit of 2 without finishing.");
        return task;
    }

    private Goal goal() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setConversationId(conversation.getId());
        return goal;
    }

    private ChatMessage appended() {
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("a failed task that kept an answer puts it in the error message, beside the reason and the code")
    void failedTaskWithAnAnswerCarriesIt() {
        Goal goal = goal();
        Task task = failedTask(goal, "Two of the three suppliers are checked so far.");
        Run run = new Run();
        run.setId(UUID.randomUUID());
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));
        when(steps.findFirstByRunIdAndKindOrderByPositionDesc(run.getId(), "error"))
                .thenReturn(Optional.of(RunStep.of(ORG, run.getId(), 4, "error", Map.of("code", "step_limit"))));

        listener.onTaskFinished(goal, task, "failed");

        ChatMessage message = appended();
        assertThat(message.getKind()).isEqualTo("error");
        assertThat(message.getContent()).isEqualTo("The agent reached its step limit of 2 without finishing.");
        assertThat(message.getDetail())
                .containsEntry("incompleteAnswer", "Two of the three suppliers are checked so far.")
                .containsEntry("code", "step_limit")
                .containsEntry("runId", run.getId().toString());
    }

    @Test
    @DisplayName("a failed task with no answer, or a blank one, carries none")
    void failedTaskWithoutAnAnswerCarriesNone() {
        for (String result : new String[] {null, "", "   "}) {
            ChatMessages fresh = mock(ChatMessages.class);
            when(fresh.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            Conversations conversations = mock(Conversations.class);
            when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
            ChatGoalListener plain = new ChatGoalListener(
                    new ChatAppender(conversations, fresh, mock(EntityManager.class)),
                    fresh,
                    runs,
                    steps,
                    mock(Agents.class),
                    new ObjectMapper());
            Goal goal = goal();
            Task task = failedTask(goal, result);

            plain.onTaskFinished(goal, task, "failed");

            ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
            verify(fresh).save(saved.capture());
            assertThat(saved.getValue().getDetail()).doesNotContainKey("incompleteAnswer");
        }
    }
}
