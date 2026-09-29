package os.aiworkforce.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A piece of work a person asked for, before it is broken down.
 *
 * <p>The goal is what somebody wanted; the tasks beneath it are how the platform decided to get
 * there. Keeping them separate means a decomposition can be revised without losing the request.
 */
@Entity
@Table(name = "goals")
public class Goal extends OrgScopedEntity {

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description = "";

    @Column(name = "status", nullable = false)
    private String status = "planning";

    @Column(name = "requested_by")
    private UUID requestedBy;

    /** Where this goal came from: a person creating it directly, chat, or a schedule firing. */
    @Column(nullable = false)
    private String source = "manual";

    /** The chat conversation this goal was created from, when {@code source} is {@code chat}. */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** The schedule that created this goal, when {@code source} is {@code schedule}. */
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "completed_at")
    private Instant completedAt;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source == null ? "manual" : source;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public boolean isFinished() {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
}
