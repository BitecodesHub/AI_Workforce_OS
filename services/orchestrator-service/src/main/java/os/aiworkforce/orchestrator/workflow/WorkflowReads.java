// @find: workflow reads, enabled grants of agents, connector grants in workspace, approvals of runs, questions of runs, waits for timeline, WorkflowReads
// @what: Batched read-only queries the workflow features need: every enabled connector grant of a set of agents, and the approvals and questions of a set of runs.
// @flow: Used by WorkflowValidator, WorkflowService (palette) and GoalTimelineService
package os.aiworkforce.orchestrator.workflow;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;

/**
 * Read-only queries over other features' tables, each answering for a whole set at once.
 *
 * <p>A canvas holds up to forty steps; checking each step's grants, or each task's approvals, one
 * query at a time would turn every save and every live-view poll into dozens of round trips. Kept
 * in this package rather than added to the shared repositories so the workflow feature owns its
 * own query shapes. Bound to {@link AgentToolGrant} only because Spring Data needs a domain type;
 * every query names its entity explicitly.
 */
public interface WorkflowReads extends Repository<AgentToolGrant, UUID> {

    // @find: enabled grants of several agents, which connectors an agent may use
    /** Every enabled connector grant of the given agents in one workspace. */
    @Query("select g from AgentToolGrant g where g.orgId = :orgId and g.agentId in :agentIds and g.enabled = true")
    List<AgentToolGrant> enabledGrants(@Param("orgId") UUID orgId, @Param("agentIds") Collection<UUID> agentIds);

    // @find: enabled grants in workspace, palette connectors per agent
    /** Every enabled connector grant in one workspace, for the canvas palette. */
    @Query("select g from AgentToolGrant g where g.orgId = :orgId and g.enabled = true")
    List<AgentToolGrant> enabledGrantsInWorkspace(@Param("orgId") UUID orgId);

    // @find: approvals of several runs, approval waits for timeline
    /** Every approval raised by the given runs, oldest first. */
    @Query("select a from Approval a where a.orgId = :orgId and a.runId in :runIds order by a.requestedAt")
    List<Approval> approvalsOfRuns(@Param("orgId") UUID orgId, @Param("runIds") Collection<UUID> runIds);

    // @find: questions of several runs, question waits for timeline
    /** Every question asked by the given runs, oldest first. */
    @Query("select q from RunQuestion q where q.orgId = :orgId and q.runId in :runIds order by q.createdAt")
    List<RunQuestion> questionsOfRuns(@Param("orgId") UUID orgId, @Param("runIds") Collection<UUID> runIds);
}
