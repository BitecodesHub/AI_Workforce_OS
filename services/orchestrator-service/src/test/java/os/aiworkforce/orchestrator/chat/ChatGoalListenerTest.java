package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;

class ChatGoalListenerTest {

    private static final UUID ORG = UUID.randomUUID();

    private ChatMessages messages;
    private Conversations conversations;
    private Runs runs;
    private RunSteps steps;
    private Agents agents;
    private ChatGoalListener listener;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        messages = mock(ChatMessages.class);
        conversations = mock(Conversations.class);
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        agents = mock(Agents.class);
        EntityManager entityManager = mock(EntityManager.class);
        ChatAppender appender = new ChatAppender(conversations, messages, entityManager);
        listener = new ChatGoalListener(appender, messages, runs, steps, agents, new ObjectMapper());

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
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
        when(steps.existsByRunIdAndProviderId(run.getId(), "sandbox")).thenReturn(false);

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
                .containsEntry("agentId", task.getAgentId().toString())
                .containsEntry("sandbox", false);
        assertThat(conversation.getMessageCount()).isEqualTo(1);
        assertThat(conversation.getLastMessagePreview()).isEqualTo("The email has been sent.");
    }

    @Test
    @DisplayName("the answer notes when it came from the offline sandbox provider")
    void completedTaskFromSandboxIsMarked() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, "Done.", null);
        Run run = new Run();
        run.setId(UUID.randomUUID());
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));
        when(steps.existsByRunIdAndProviderId(run.getId(), "sandbox")).thenReturn(true);

        listener.onTaskFinished(goal, task, "completed");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getDetail()).containsEntry("sandbox", true);
    }

    @Test
    @DisplayName("a failed task appends an error message carrying the failure reason and, when known, a code")
    void failedTaskAppendsError() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, "The tool call was refused.");
        Run run = new Run();
        run.setId(UUID.randomUUID());
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.of(run));
        RunStep errorStep = RunStep.of(ORG, run.getId(), 0, "error", Map.of("code", "tool_refused"));
        when(steps.findFirstByRunIdAndKindOrderByPositionDesc(run.getId(), "error"))
                .thenReturn(Optional.of(errorStep));

        listener.onTaskFinished(goal, task, "failed");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        ChatMessage message = saved.getValue();
        assertThat(message.getKind()).isEqualTo("error");
        assertThat(message.getContent()).isEqualTo("The tool call was refused.");
        assertThat(message.getDetail())
                .containsEntry("reason", "The tool call was refused.")
                .containsEntry("code", "tool_refused");
    }

    @Test
    @DisplayName("a failed task with no error step in its trace carries no code key at all")
    void failedTaskWithNoCodeOmitsTheKey() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, "Something went wrong.");
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.empty());

        listener.onTaskFinished(goal, task, "failed");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getDetail()).doesNotContainKey("code");
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
    @DisplayName("a cancelled task appends a system notice saying the work was stopped, and why")
    void cancelledNarrated() {
        Goal goal = goalFor(conversation.getId());
        Task task = taskFor(goal, null, "An approver rejected the action this run needed.");
        when(runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())).thenReturn(Optional.empty());

        listener.onTaskFinished(goal, task, "cancelled");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        ChatMessage message = saved.getValue();
        assertThat(message.getKind()).isEqualTo("notice");
        assertThat(message.getAuthorKind()).isEqualTo("system");
        assertThat(message.getContent()).isEqualTo("Stopped: An approver rejected the action this run needed.");
    }

    @Test
    @DisplayName("onGoalCancelled appends a stopped notice, unless the conversation is being deleted")
    void goalCancelledAppendsNotice() {
        Goal goal = goalFor(conversation.getId());

        listener.onGoalCancelled(goal, "A person stopped every run in this workspace.");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("notice");
        assertThat(saved.getValue().getAuthorKind()).isEqualTo("system");
        assertThat(saved.getValue().getContent()).isEqualTo("Stopped. A person stopped every run in this workspace.");
    }

    @Test
    @DisplayName("noNoticeWhenTheConversationIsBeingDeleted")
    void noNoticeWhenTheConversationIsBeingDeleted() {
        Goal goal = goalFor(conversation.getId());

        listener.onGoalCancelled(goal, ConversationAdmin.DELETED_REASON);

        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("onGoalRetried names the step and the agent it resumes from")
    void goalRetriedNamesStepAndAgent() {
        Goal goal = goalFor(conversation.getId());
        Task fromTask = taskFor(goal, null, null);
        fromTask.setPosition(2);
        Agent agent = new Agent();
        agent.setId(fromTask.getAgentId());
        agent.setName("Customer Support");
        when(agents.findById(fromTask.getAgentId())).thenReturn(Optional.of(agent));

        listener.onGoalRetried(goal, fromTask);

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("Trying again from step 3: Customer Support.");
        assertThat(saved.getValue().getDetail())
                .containsEntry("fromTaskId", fromTask.getId().toString());
    }

    @Test
    @DisplayName("onQuestionAsked appends a question message once, and skips a duplicate")
    void questionAskedAppendsOnceAndSkipsDuplicate() {
        Goal goal = goalFor(conversation.getId());
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setRunId(UUID.randomUUID());
        question.setConversationId(conversation.getId());
        question.setGoalId(goal.getId());
        question.setAgentId(UUID.randomUUID());
        question.setQuestionsJson("[{\"id\":\"q1\",\"header\":\"Audience\",\"question\":\"Who is this for?\"}]");
        when(messages.findByConversationIdOrderByPositionDesc(any(), any())).thenReturn(List.of());

        listener.onQuestionAsked(goal, null, question);

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        ChatMessage message = saved.getValue();
        assertThat(message.getKind()).isEqualTo("question");
        assertThat(message.getAuthorKind()).isEqualTo("agent");
        assertThat(message.getContent()).isEqualTo("Who is this for?");
        assertThat(message.getDetail())
                .containsEntry("questionId", question.getId().toString());
        assertThat(message.getDetail()).containsEntry("headers", List.of("Audience"));

        // A duplicate call, with the question already among the recent messages, appends nothing more.
        ChatMessage existing = ChatMessage.of(
                ORG,
                conversation.getId(),
                0,
                "agent",
                null,
                question.getAgentId(),
                "question",
                "Who is this for?",
                Map.of("questionId", question.getId().toString()),
                goal.getId());
        when(messages.findByConversationIdOrderByPositionDesc(any(), any())).thenReturn(List.of(existing));

        listener.onQuestionAsked(goal, null, question);

        verify(messages, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("a question with no conversation is never posted to chat")
    void questionWithNoConversationIsIgnored() {
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setRunId(UUID.randomUUID());

        listener.onQuestionAsked(null, null, question);

        verify(messages, never()).save(any());
    }
}
