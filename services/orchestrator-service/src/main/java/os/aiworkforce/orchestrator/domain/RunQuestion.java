// @find: run question, agent asks a question, ask the user, clarifying question, answer question, waiting input, question expiry, run_questions, RunQuestion entity, Questions inbox
// @what: Entity for a question a run stopped to ask a person; the run resumes when it is answered or expires.
// @flow: Stored by RunQuestions; answered through the question controller; resumes its Run.
// @find: run question, agent asks a question, ask the user, clarifying question, answer question, waiting input, question expiry, run_questions, RunQuestion entity, Questions inbox
// @what: Entity for a question a run stopped to ask a person; the run resumes when it is answered or expires.
// @flow: Stored by RunQuestions; answered through the question controller; resumes its Run.
package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A question a run stopped to ask the person it works for.
 *
 * <p>The run is parked in {@code waiting_input} while this is pending, and resumes exactly once
 * when it is answered or expires, with the answer delivered as the result of the ask call named by
 * {@code toolCallId}. The questions and the answer are JSON because their shape is the model's
 * (one to four questions, each with its own options), and a table of mostly-empty columns would
 * say less than the document does.
 */
@Entity
@Table(name = "run_questions")
public class RunQuestion extends OrgScopedEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "goal_id")
    private UUID goalId;

    /** The chat conversation this question is shown in, when its goal came from chat. */
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The ask call's id, unique within the run; the answer is returned as this call's result. */
    @Column(name = "tool_call_id", nullable = false)
    private String toolCallId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions", nullable = false)
    private String questionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer")
    private String answerJson;

    @Column(name = "status", nullable = false)
    private String status = "pending";

    @Column(name = "answered_by")
    private UUID answeredBy;

    @Column(name = "answered_via")
    private String answeredVia;

    @Column(name = "answered_at")
    private Instant answeredAt;

    /** Who may answer by default: the goal's requester, or the person who started a direct run. */
    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "closed_reason", columnDefinition = "text")
    private String closedReason;

    // @find: is question pending
    // @find: is question pending
    public boolean isPending() {
        return "pending".equals(status);
    }

    // @find: question expired, past expiry
    // @find: question expired, past expiry
    /** Still pending although its time is up. The expiry sweep closes it; an answer is refused. */
    public boolean hasExpired(Instant now) {
        return isPending() && expiresAt.isBefore(now);
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public void setGoalId(UUID goalId) {
        this.goalId = goalId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getQuestionsJson() {
        return questionsJson;
    }

    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }

    public String getAnswerJson() {
        return answerJson;
    }

    public void setAnswerJson(String answerJson) {
        this.answerJson = answerJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getAnsweredBy() {
        return answeredBy;
    }

    public void setAnsweredBy(UUID answeredBy) {
        this.answeredBy = answeredBy;
    }

    public String getAnsweredVia() {
        return answeredVia;
    }

    public void setAnsweredVia(String answeredVia) {
        this.answeredVia = answeredVia;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getClosedReason() {
        return closedReason;
    }

    public void setClosedReason(String closedReason) {
        this.closedReason = closedReason;
    }
}
