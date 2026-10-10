// @find: model policy, routing policy, model routing, fallback chain, workspace default policy, per agent policy, exhausted behaviour, max attempts, deadline, compact on overflow, model_policies, ModelPolicyEntity, Models page
// @what: Entity for an ordered list of models to try and what to do when all fail; null agent means workspace default.
// @flow: Stored by ModelPolicies; read by the model router when a run calls a model.
// @find: model policy, routing policy, model routing, fallback chain, workspace default policy, per agent policy, exhausted behaviour, max attempts, deadline, compact on overflow, model_policies, ModelPolicyEntity, Models page
// @what: Entity for an ordered list of models to try and what to do when all fail; null agent means workspace default.
// @flow: Stored by ModelPolicies; read by the model router when a run calls a model.
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

    /*
     * The join column is part of each candidate's own key and is written by the candidate itself,
     * so the collection never writes it: with the defaults, taking a model out of a chain made
     * Hibernate try to null the key column of the removed row, and every shortened chain failed to
     * save ("No value specified for parameter 2").
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "policy_id", nullable = false, insertable = false, updatable = false)
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

    // @find: replace routing chain candidates, reorder models in policy
    // @find: replace routing chain candidates, reorder models in policy
    /**
     * Replaces the chain. Rows are updated in place by position, extra rows added and surplus rows
     * removed, so no row is ever deleted and re-inserted under the same key in one flush (Hibernate
     * inserts before it deletes, which would collide on the primary key).
     */
    public void setCandidates(List<ModelPolicyCandidate> incoming) {
        List<ModelPolicyCandidate> wanted = incoming == null ? List.of() : new ArrayList<>(incoming);
        wanted.sort(java.util.Comparator.comparing(ModelPolicyCandidate::getPosition));
        java.util.Map<Integer, ModelPolicyCandidate> byPosition = new java.util.HashMap<>();
        for (ModelPolicyCandidate existing : candidates) {
            byPosition.put(existing.getPosition(), existing);
        }
        List<ModelPolicyCandidate> next = new ArrayList<>();
        for (int position = 0; position < wanted.size(); position++) {
            ModelPolicyCandidate source = wanted.get(position);
            ModelPolicyCandidate row = byPosition.remove(position);
            if (row == null || row == source) {
                row = source;
                row.setPosition(position);
                row.setPolicyId(getId());
            } else {
                row.setProviderId(source.getProviderId());
                row.setModelId(source.getModelId());
                row.setTemperature(source.getTemperature());
                row.setMaxOutputTokens(source.getMaxOutputTokens());
                row.setWeight(source.getWeight());
            }
            next.add(row);
        }
        candidates.clear();
        candidates.addAll(next);
    }
}
