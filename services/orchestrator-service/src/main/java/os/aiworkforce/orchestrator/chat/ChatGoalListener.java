// @find: chat goal listener, agent answer lands in chat, task finished message, goal finished, goal cancelled, goal retried, question asked in chat, ChatGoalListener, GoalLifecycleListener, announce result in conversation
// @what: Turns task outcomes, stops, retries and agent questions into messages in the conversation that started the work.
// @flow: Called by LifecycleAnnouncer and TaskProgress after commits; writes through ChatAppender and starts the next queued message.
package os.aiworkforce.orchestrator.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener;

/**
 * Turns a task's outcome, a goal being stopped or retried, and a run asking a question into a
 * message in the conversation that started the work.
 *
 * <p>Every callback here is called by {@link os.aiworkforce.orchestrator.service.LifecycleAnnouncer}
 * or {@link os.aiworkforce.orchestrator.service.TaskProgress} only after the write it reports on
 * has committed, and each in a transaction of its own - this is what lets a person watching a
 * conversation see an agent's answer land the instant its task completes, and what keeps a failure
 * appending one of these messages from ever rolling back the run, the stop or the retry it is
 * reporting on.
 */
@Component
public class ChatGoalListener implements GoalLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(ChatGoalListener.class);

    /** The failure text shown when a task failed without recording a reason. */
    private static final String DEFAULT_FAILURE_REASON = "The agent did not complete this task.";
    /** A question is never posted twice: this many of the newest messages are checked for its id first. */
    private static final int DUPLICATE_CHECK_WINDOW = 50;

    /** The only writer of chat messages; used for every message this listener adds. */
    private final ChatAppender appender;

    /** Starts the next queued message once work ends; absent only where a test builds this by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatQueueRunner queueRunner;
    /** Read to check whether a question has already been posted. */
    private final ChatMessages messages;
    /** Read to find the latest run of a task, for the links in a message. */
    private final Runs runs;
    /** Read to find whether a run used the sandbox, used practice data or ended in an error code. */
    private final RunSteps steps;
    /** Read for an agent's name in the retry notice. */
    private final Agents agents;
    /** Parses the questions a run stored as JSON. */
    private final ObjectMapper json;

    /** Creates the listener over the appender and the repositories it reads. */
    public ChatGoalListener(
            ChatAppender appender, ChatMessages messages, Runs runs, RunSteps steps, Agents agents, ObjectMapper json) {
        this.appender = appender;
        this.messages = messages;
        this.runs = runs;
        this.steps = steps;
        this.agents = agents;
        this.json = json;
    }

    /**
     * Posts the outcome of a finished task into the conversation that started the goal.
     *
     * <p>Called after the task's status change has committed, in a transaction of its own. A completed
     * task becomes an agent {@code answer} message carrying the task, run and agent ids and flags for
     * sandbox and practice data; a failed task becomes an {@code error} message with the reason, an error
     * code from the run when there is one, and any partial answer; a cancelled task becomes a system
     * {@code notice}. Skipped tasks add nothing. Goals with no conversation are ignored. Finally the
     * conversation's queue is looked at once this transaction commits. Writes go through {@link
     * ChatAppender}, which locks the conversation row.
     */
    // @find: task finished, agent answer appears in chat, task completed or failed message
    @Override
    @Transactional
    public void onTaskFinished(Goal goal, Task task, String status) {
        if (goal == null) {
            return;
        }
        UUID conversationId = goal.getConversationId();
        if (conversationId == null) {
            return;
        }
        UUID runId = runs.findFirstByTaskIdOrderByStartedAtDesc(task.getId())
                .map(Run::getId)
                .orElse(null);

        if ("completed".equals(status)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("taskId", task.getId().toString());
            detail.put("runId", runId == null ? "" : runId.toString());
            detail.put(
                    "agentId",
                    task.getAgentId() == null ? "" : task.getAgentId().toString());
            // The web stops fetching a run's steps for an answer once it already knows the answer
            // came from the offline sandbox rather than a real model.
            detail.put("sandbox", runId != null && steps.existsByRunIdAndProviderId(runId, "sandbox"));
            // A connector with no real account behind it answers from practice data. The model is
            // told so, yet still writes "The email has been sent", so the answer carries the fact.
            if (runId != null && steps.usedPracticeData(runId)) {
                detail.put("practiceData", true);
            }
            appendAs(
                    "agent",
                    goal,
                    conversationId,
                    task.getAgentId(),
                    "answer",
                    task.getResult() == null ? "" : task.getResult(),
                    detail);
        } else if ("failed".equals(status)) {
            String reason = task.getFailureReason() == null ? DEFAULT_FAILURE_REASON : task.getFailureReason();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            detail.put("taskId", task.getId().toString());
            detail.put("runId", runId == null ? "" : runId.toString());
            String code = errorCodeFor(runId);
            if (code != null) {
                detail.put("code", code);
            }
            // A run that stopped at its step or output limit still wrote what it had. The card
            // offers it as an incomplete answer: worth reading, not to be mistaken for a finished one.
            if (task.getResult() != null && !task.getResult().isBlank()) {
                detail.put("incompleteAnswer", task.getResult());
            }
            appendAs("agent", goal, conversationId, task.getAgentId(), "error", reason, detail);
        } else if ("cancelled".equals(status)) {
            // A rejected approval or a stopped run ends the work; without a line in the thread the
            // conversation would still read as though the agent were working on it.
            String reason =
                    task.getFailureReason() == null || task.getFailureReason().isBlank()
                            ? "This work was stopped before it finished."
                            : "Stopped: " + task.getFailureReason();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("goalId", goal.getId().toString());
            detail.put("taskId", task.getId().toString());
            detail.put("event", "cancelled");
            appendAs("system", goal, conversationId, task.getAgentId(), "notice", reason, detail);
        }
        // Skipped tasks are ordinary chain outcomes with nothing new to tell a person that the
        // progress card, driven off the goal itself, does not already show.
        lookAtQueue(goal.getOrgId(), conversationId);
    }

    /**
     * Looks at the conversation's queue when a whole goal has finished, so the next waiting message
     * can start. Adds no message of its own; goals without a conversation are ignored.
     */
    // @find: goal finished, final summary message in chat
    @Override
    public void onGoalFinished(Goal goal) {
        if (goal != null && goal.getConversationId() != null) {
            lookAtQueue(goal.getOrgId(), goal.getConversationId());
        }
    }

    /**
     * Once this commits, the conversation's next waiting message may start.
     *
     * <p>Schedules {@link ChatQueueRunner#drainSoon} for after the surrounding transaction commits;
     * does nothing when no runner is wired or an id is missing.
     */
    private void lookAtQueue(UUID orgId, UUID conversationId) {
        if (queueRunner == null || orgId == null || conversationId == null) {
            return;
        }
        ChatQueueRunner runner = queueRunner;
        os.aiworkforce.orchestrator.service.LifecycleAnnouncer.afterCommit(
                () -> runner.drainSoon(orgId, conversationId));
    }

    /**
     * "Stopped. <why>", without saying it twice when the reason already starts "Stopped from the chat."
     *
     * <p>With no reason the text is just {@code "Stopped."}. Pure; used by {@link #onGoalCancelled}.
     */
    static String stoppedText(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Stopped.";
        }
        String why = reason.strip();
        return why.regionMatches(true, 0, "Stopped", 0, "Stopped".length()) ? why : "Stopped. " + why;
    }

    /**
     * Posts a system notice that a goal was stopped, then looks at the queue.
     *
     * <p>Called after the cancellation commits, in a transaction of its own. Nothing is posted when the
     * reason is the conversation-deleted marker, because the conversation is about to disappear and a
     * write to it mid-delete could race the deletion.
     */
    // @find: goal cancelled, stopped work message in chat
    @Override
    @Transactional
    public void onGoalCancelled(Goal goal, String reason) {
        if (goal == null || goal.getConversationId() == null) {
            return;
        }
        if (ConversationAdmin.DELETED_REASON.equals(reason)) {
            // The conversation is about to go with it; narrating a cancel nobody will ever read
            // would only be noise, and appending to a row mid-delete risks the two racing.
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("goalId", goal.getId().toString());
        detail.put("event", "cancelled");
        detail.put("reason", reason);
        appendAs("system", goal, goal.getConversationId(), null, "notice", stoppedText(reason), detail);
        lookAtQueue(goal.getOrgId(), goal.getConversationId());
    }

    /**
     * Posts a system notice that work is being retried from a given step.
     *
     * <p>Called after the retry commits, in a transaction of its own. The notice names the step number
     * and the agent. Ignored when the goal has no conversation or no task is given.
     */
    // @find: goal retried, retry message in chat
    @Override
    @Transactional
    public void onGoalRetried(Goal goal, Task fromTask) {
        if (goal == null || goal.getConversationId() == null || fromTask == null) {
            return;
        }
        String agentName = agentNameFor(fromTask.getAgentId());
        String content = "Trying again from step " + (fromTask.getPosition() + 1) + ": " + agentName + ".";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("goalId", goal.getId().toString());
        detail.put("event", "retried");
        detail.put("fromTaskId", fromTask.getId().toString());
        appendAs("system", goal, goal.getConversationId(), null, "notice", content, detail);
    }

    /**
     * Posts the question an agent asked as a {@code question} message in its conversation.
     *
     * <p>Called after the question is stored, in a transaction of its own. Locks the conversation, skips
     * the post when the same question id is already among the newest messages, and otherwise appends one
     * agent message whose content is the first question and whose detail carries the ids, the question
     * headers and the expiry time. Does nothing when the question has no conversation or the
     * conversation no longer exists. Unreadable stored questions are logged and give an empty message.
     */
    // @find: question asked, agent asks the person in chat, run question
    @Override
    @Transactional
    public void onQuestionAsked(Goal goal, Task task, RunQuestion question) {
        if (question == null || question.getConversationId() == null) {
            return;
        }
        Conversation conversation =
                appender.lock(question.getOrgId(), question.getConversationId()).orElse(null);
        if (conversation == null) {
            return;
        }
        if (alreadyPosted(question.getConversationId(), question.getId())) {
            return;
        }
        List<Map<String, Object>> parsed = readQuestions(question);
        String content =
                parsed.isEmpty() ? "" : String.valueOf(parsed.getFirst().get("question"));
        List<String> headers =
                parsed.stream().map(q -> String.valueOf(q.get("header"))).toList();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("questionId", question.getId().toString());
        detail.put("runId", question.getRunId().toString());
        detail.put(
                "taskId",
                question.getTaskId() == null ? null : question.getTaskId().toString());
        detail.put(
                "agentId",
                question.getAgentId() == null ? null : question.getAgentId().toString());
        detail.put("count", parsed.size());
        detail.put("headers", headers);
        detail.put(
                "expiresAt",
                question.getExpiresAt() == null ? null : question.getExpiresAt().toString());
        appender.append(
                conversation, "agent", null, question.getAgentId(), "question", content, detail, question.getGoalId());
    }

    /** Read from the conversation's own last messages, because a question message never carries a version to race on. */
    private boolean alreadyPosted(UUID conversationId, UUID questionId) {
        return messages
                .findByConversationIdOrderByPositionDesc(conversationId, PageRequest.of(0, DUPLICATE_CHECK_WINDOW))
                .stream()
                .anyMatch(m -> "question".equals(m.getKind())
                        && questionId
                                .toString()
                                .equals(String.valueOf(m.getDetail().get("questionId"))));
    }

    /**
     * The questions stored on a run question; malformed JSON is logged as a warning and yields an empty list.
     */
    private List<Map<String, Object>> readQuestions(RunQuestion question) {
        try {
            return json.readValue(question.getQuestionsJson(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException | RuntimeException malformed) {
            log.warn("Could not read the questions stored on {}", question.getId(), malformed);
            return List.of();
        }
    }

    /** The code of the run's last error step, or null when the run has none. */
    private String errorCodeFor(UUID runId) {
        if (runId == null) {
            return null;
        }
        return steps.findFirstByRunIdAndKindOrderByPositionDesc(runId, "error")
                .map(step -> step.getDetail() == null ? null : step.getDetail().get("code"))
                .map(String::valueOf)
                .orElse(null);
    }

    /** The agent's name, or {@code the agent} when it is unknown or deleted. */
    private String agentNameFor(UUID agentId) {
        if (agentId == null) {
            return "the agent";
        }
        return agents.findById(agentId).map(Agent::getName).orElse("the agent");
    }

    /**
     * Locks the conversation and appends one message tied to the goal.
     *
     * <p>Quietly does nothing when the conversation no longer exists. Runs inside the caller's
     * transaction.
     */
    private void appendAs(
            String authorKind,
            Goal goal,
            UUID conversationId,
            UUID agentId,
            String kind,
            String content,
            Map<String, Object> detail) {
        Conversation conversation =
                appender.lock(goal.getOrgId(), conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        appender.append(conversation, authorKind, null, agentId, kind, content, detail, goal.getId());
    }
}
