package os.aiworkforce.orchestrator.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * An ordered list of models to try, and what to do when none of them work.
 *
 * <p>A null {@code agentId} makes this the workspace default, inherited by every agent without a
 * policy of its own. That single indirection is what lets an administrator move an entire
 * workspace off a provider having a bad day by editing one row.
 */
@Entity
@Table(name = "model_policies")
public class ModelPolicyEntity extends OrgScopedEntity {

    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * What happens when every candidate has failed.
     *
     * <p>{@code FAIL_CLOSED} by default. Quietly substituting the offline model would let a
     * person act on a placeholder answer believing a real model produced it.
     */
    @Column(name = "exhausted_behaviour", nullable = false)
    private String exhaustedBehaviour = "FAIL_CLOSED";

    @Column(name = "max_attempts_per_candidate", nullable = false)
    private int maxAttemptsPerCandidate = 2;

    @Column(name = "overall_deadline_seconds", nullable = false)
    private int overallDeadlineSeconds = 300;

    @Column(name = "compact_on_overflow", nullable = false)
    private boolean compactOnOverflow = true;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "policy_id")
    @OrderBy("position ASC")
    private List<ModelPolicyCandidate> candidates = new ArrayList<>();

    public boolean isWorkspaceDefault() {
        return agentId == null;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getExhaustedBehaviour() {
        return exhaustedBehaviour;
    }

    public void setExhaustedBehaviour(String exhaustedBehaviour) {
        this.exhaustedBehaviour = exhaustedBehaviour;
    }

    public int getMaxAttemptsPerCandidate() {
        return maxAttemptsPerCandidate;
    }

    public void setMaxAttemptsPerCandidate(int maxAttemptsPerCandidate) {
        this.maxAttemptsPerCandidate = maxAttemptsPerCandidate;
    }

    public int getOverallDeadlineSeconds() {
        return overallDeadlineSeconds;
    }

    public void setOverallDeadlineSeconds(int overallDeadlineSeconds) {
        this.overallDeadlineSeconds = overallDeadlineSeconds;
    }

    public boolean isCompactOnOverflow() {
        return compactOnOverflow;
    }

    public void setCompactOnOverflow(boolean compactOnOverflow) {
        this.compactOnOverflow = compactOnOverflow;
    }

    public List<ModelPolicyCandidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<ModelPolicyCandidate> candidates) {
        this.candidates.clear();
        if (candidates != null) {
            this.candidates.addAll(candidates);
        }
    }
}
