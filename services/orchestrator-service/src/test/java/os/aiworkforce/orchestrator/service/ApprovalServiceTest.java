// @find: tests for approval service, approvals, approve, reject, four-eyes, send back, expiry, pending approvals, decision
// @what: Unit and integration tests (36 cases) for approval service, for example: rejection follows through; rejection without note; rejection reason is capped; approval leaves the run to its resume.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

/**
 * Rejection and expiry end the run, its task and its goal. A decision is recorded with who, when and
 * why, the workspace may ask for four eyes, and a request is announced as it is raised and as it
 * expires.
 */
class ApprovalServiceTest {

    private WorkFixture work;
    private Approvals approvals;
    private Runs runs;
    private AuditClient audit;
    private AgentRunner runner;
    private LifecycleAnnouncer announcer;
    private RuntimeConfigService settings;
    private ApprovalService service;
    private Actor approver;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        approvals = mock(Approvals.class);
        runs = mock(Runs.class);
        audit = mock(AuditClient.class);
        runner = mock(AgentRunner.class);
        announcer = mock(LifecycleAnnouncer.class);
        settings = mock(RuntimeConfigService.class);
        service = new ApprovalService(
                approvals,
                runs,
                work.tasks,
                work.goals,
                new ObjectMapper(),
                audit,
                new TaskProgress(work.tasks, work.goals, List.of()),
                runner);
        service.setAnnouncer(announcer);
        service.setRuntimeConfig(settings);
        approver = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L);
        RequestContext.setActor(approver);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("a rejection cancels the run, its task and its goal")
    void rejectionFollowsThrough() {
        Goal goal = work.goal("running");
        Task task = work.task(goal, 0, "waiting_approval");
        Run run = runFor(task, "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        service.decide(ORG, approval.getId(), false, "  Not this week.  ");

        assertThat(approval.getStatus()).isEqualTo("rejected");
        assertThat(approval.getDecisionNote()).isEqualTo("Not this week.");
        assertThat(run.getStatus()).isEqualTo("cancelled");
        assertThat(task.getStatus()).isEqualTo("cancelled");
        assertThat(task.getFailureReason()).isEqualTo("An approver rejected the action this run needed: Not this week.");
        assertThat(goal.getStatus()).isEqualTo("cancelled");
        // Ended like every other run: its budget released and its ending audited, after the commit.
        verify(runner).finishTerminal(run, "cancelled", "An approver rejected the action this run needed: Not this week.");
    }

    @Test
    @DisplayName("a rejection without a reason keeps the plain sentence, and a blank note counts as none")
    void rejectionWithoutNote() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        service.decide(ORG, approval.getId(), false, "  \n ");

        assertThat(run.getFailureReason()).isEqualTo("An approver rejected the action this run needed.");
        assertThat(approval.getDecisionNote()).isNull();
    }

    @Test
    @DisplayName("a long reason is cut so the run's failure reason stays within 300 characters, on one line")
    void rejectionReasonIsCapped() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        service.decide(ORG, approval.getId(), false, "Too long.\n" + "x".repeat(1_500));

        assertThat(run.getFailureReason())
                .hasSize(300)
                .startsWith("An approver rejected the action this run needed: Too long. xxx")
                .doesNotContain("\n");
        // The full note, to its own limit, stays with the decision.
        assertThat(approval.getDecisionNote()).hasSize(1_000);
    }

    @Test
    @DisplayName("an approval does not end the run, so nothing is released or audited as its ending")
    void approvalLeavesTheRunToItsResume() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        service.decide(ORG, approval.getId(), true, null);

        assertThat(run.getStatus()).isEqualTo("waiting_approval");
        verify(runner, never()).finishTerminal(any(), any(), any());
    }

    @Test
    @DisplayName("an approval is raised with the action class it is given, and OUTBOUND when none is known")
    void raiseRecordsTheActionClass() {
        Run run = runFor(work.task(work.goal("running"), 0, "running"), "running");
        Agent agent = new Agent();
        agent.setId(run.getAgentId());
        agent.setName("Finance Agent");
        ToolInvocation refund = new ToolInvocation(
                ORG.toString(),
                run.getAgentId().toString(),
                run.getId().toString(),
                "stripe",
                "refund_payment",
                "{\"payment\":\"pi_1\"}",
                run.getId() + ":call_0",
                Map.of());
        ApprovalDecision.AwaitApproval await = new ApprovalDecision.AwaitApproval(
                "Permanently remove something using stripe.refund_payment", "approval:decide");

        ToolCall first = new ToolCall("call_0", "stripe.refund_payment", "{}");
        ToolCall second = new ToolCall("call_1", "stripe.refund_payment", "{}");

        Approval destructive = service.raise(run, agent, refund, await, first, "DESTRUCTIVE");
        Approval unknown = service.raise(run, agent, refund, await, second, null);

        assertThat(destructive.getActionClass()).isEqualTo("DESTRUCTIVE");
        assertThat(destructive.getTool()).isEqualTo("stripe.refund_payment");
        assertThat(unknown.getActionClass()).isEqualTo("OUTBOUND");
    }

    @Test
    @DisplayName("the audit entry for a decision names the run, the tool, the agent, the wait and the note")
    @SuppressWarnings("unchecked")
    void auditCarriesRun() {
        Goal goal = work.goal("running");
        Task task = work.task(goal, 0, "waiting_approval");
        Run run = runFor(task, "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        approval.setRequestedAt(Instant.now().minusSeconds(95));

        service.decide(ORG, approval.getId(), true, "  Looks right.  ");

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        eq(approver),
                        eq("approval.decide"),
                        eq("approval"),
                        eq(approval.getId().toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("approved", true)
                .containsEntry("runId", run.getId().toString())
                .containsEntry("tool", "gmail.send_message")
                .containsEntry("agentId", run.getAgentId().toString())
                .containsEntry("taskId", task.getId().toString())
                .containsEntry("note", "Looks right.");
        assertThat((Long) detail.getValue().get("waitedSeconds")).isBetween(95L, 100L);
    }

    @Test
    @DisplayName("the audit entry leaves out the tool, the task and the note when there are none")
    @SuppressWarnings("unchecked")
    void auditWithoutTool() {
        Run run = runFor(null, "waiting_approval");
        Approval approval = approval(run, null, Instant.now().plus(1, ChronoUnit.DAYS));

        service.decide(ORG, approval.getId(), true, null);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(any(), any(), any(), any(), any(), any(), detail.capture());
        assertThat(detail.getValue()).containsOnlyKeys("approved", "runId", "agentId", "waitedSeconds");
    }

    @Test
    @DisplayName("the audit entry is written after the commit, so a decision that rolls back is never on record")
    void auditWaitsForTheCommit() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.decide(ORG, approval.getId(), true, null);
            verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(audit).record(eq(ORG), eq(approver), eq("approval.decide"), any(), any(), eq("succeeded"), any());
    }

    @Test
    @DisplayName("an expired approval cancels the run and its task, and the goal closes")
    void expiryFollowsThrough() {
        Goal goal = work.goal("running");
        Task task = work.task(goal, 0, "waiting_approval");
        Run run = runFor(task, "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().minus(1, ChronoUnit.HOURS));
        when(approvals.findExpired(any(), any())).thenReturn(List.of(approval));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        assertThat(service.expireOverdue(50)).isEqualTo(1);

        assertThat(approval.getStatus()).isEqualTo("expired");
        assertThat(run.getStatus()).isEqualTo("cancelled");
        assertThat(task.getStatus()).isEqualTo("cancelled");
        assertThat(task.getFailureReason()).isEqualTo("The approval this run needed expired before anybody decided.");
        assertThat(goal.getStatus()).isEqualTo("cancelled");
        verify(runner).finishTerminal(run, "cancelled", "The approval this run needed expired before anybody decided.");
    }

    @Test
    @DisplayName("an expiry is audited as the platform's own act, with how long it waited, and announced")
    @SuppressWarnings("unchecked")
    void expiryIsAudited() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().minus(1, ChronoUnit.HOURS));
        approval.setRequestedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        when(approvals.findExpired(any(), any())).thenReturn(List.of(approval));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        service.expireOverdue(50);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        eq(Actor.SYSTEM),
                        eq("approval.expire"),
                        eq("approval"),
                        eq(approval.getId().toString()),
                        eq("denied"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("runId", run.getId().toString())
                .containsEntry("tool", "gmail.send_message")
                .containsEntry("agentId", run.getAgentId().toString())
                .containsEntry("outcome", "denied");
        assertThat((Long) detail.getValue().get("waitedSeconds")).isBetween(25L * 3_600, 25L * 3_600 + 10);
        verify(announcer).approvalExpired(approval);
    }

    @Test
    @DisplayName("deciding an approval past its deadline expires it, audits that, and refuses the decision")
    void decidingAnOverdueApprovalExpiresIt() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().minus(1, ChronoUnit.MINUTES));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.decide(ORG, approval.getId(), true, null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.APPROVAL_EXPIRED));

        assertThat(approval.getStatus()).isEqualTo("expired");
        verify(audit).record(eq(ORG), eq(Actor.SYSTEM), eq("approval.expire"), any(), any(), eq("denied"), any());
    }

    @Test
    @DisplayName("an approval is raised with who asked for the work, and announced once it commits")
    void raiseRecordsTheRequester() {
        Goal goal = work.goal("running");
        UUID requester = UUID.randomUUID();
        goal.setRequestedBy(requester);
        Run run = runFor(work.task(goal, 0, "running"), "running");

        Approval approval = service.raise(run, agent(run), invocation(run), awaiting(), call(), "OUTBOUND");

        assertThat(approval.getRequestedBy()).isEqualTo(requester);
        verify(announcer).approvalRaised(approval);
    }

    @Test
    @DisplayName("a run started directly names the person who started it, and a goal nobody asked for names no one")
    void raiseForDirectAndUnownedRuns() {
        UUID starter = UUID.randomUUID();
        Run direct = runFor(null, "running");
        ReflectionTestUtils.setField(direct, "createdBy", starter.toString());
        Run system = runFor(null, "running");
        ReflectionTestUtils.setField(system, "createdBy", "system");
        Run unowned = runFor(work.task(work.goal("running"), 0, "running"), "running");

        assertThat(service.raise(direct, agent(direct), invocation(direct), awaiting(), call(), "OUTBOUND")
                        .getRequestedBy())
                .isEqualTo(starter);
        assertThat(service.raise(system, agent(system), invocation(system), awaiting(), call(), "OUTBOUND")
                        .getRequestedBy())
                .isNull();
        assertThat(service.raise(unowned, agent(unowned), invocation(unowned), awaiting(), call(), "OUTBOUND")
                        .getRequestedBy())
                .isNull();
    }

    // ---- The four-eyes rule ------------------------------------------------------------------

    @Test
    @DisplayName("with the rule off, the requester may approve their own request")
    void requesterMayApproveWhenTheRuleIsOff() {
        Approval approval = requestedBy(approver, "OUTBOUND");
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("off");

        service.decide(ORG, approval.getId(), true, null);

        assertThat(approval.getStatus()).isEqualTo("approved");
    }

    @Test
    @DisplayName("a workspace that never set the rule behaves as if it were off")
    void anUnsetRuleIsOff() {
        Approval approval = requestedBy(approver, "DESTRUCTIVE");

        service.decide(ORG, approval.getId(), true, null);

        assertThat(approval.getStatus()).isEqualTo("approved");
    }

    @Test
    @DisplayName("with the rule on for everything, the requester is refused, and somebody else may decide")
    void requesterCannotApproveWhenTheRuleIsAll() {
        Approval approval = requestedBy(approver, "OUTBOUND");
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("all");

        assertThatThrownBy(() -> service.decide(ORG, approval.getId(), true, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.getMessage()).isEqualTo("Someone other than the requester must decide this");
                });
        assertThat(approval.getStatus()).isEqualTo("pending");
        assertThat(service.canDecide(approver, approval)).isFalse();

        Actor colleague =
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L);
        RequestContext.setActor(colleague);
        assertThat(service.canDecide(colleague, approval)).isTrue();
        service.decide(ORG, approval.getId(), true, null);
        assertThat(approval.getStatus()).isEqualTo("approved");
        assertThat(approval.getDecidedBy()).isEqualTo(UUID.fromString(colleague.id()));
    }

    @Test
    @DisplayName("with the rule on for destructive actions, only those are refused to the requester")
    void requesterCannotApproveDestructiveWhenTheRuleIsDestructive() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("destructive");
        Approval outbound = requestedBy(approver, "OUTBOUND");
        Approval destructive = requestedBy(approver, "DESTRUCTIVE");

        service.decide(ORG, outbound.getId(), true, null);
        assertThat(outbound.getStatus()).isEqualTo("approved");

        assertThatThrownBy(() -> service.decide(ORG, destructive.getId(), true, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(destructive.getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("a run no person asked for is never stopped by the rule")
    void aRunWithNoRequesterIsUnaffected() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("all");
        Approval approval = requestedBy(null, "DESTRUCTIVE");

        service.decide(ORG, approval.getId(), true, null);

        assertThat(approval.getStatus()).isEqualTo("approved");
    }

    @Test
    @DisplayName("a setting that cannot be read is treated as the strictest, not the loosest")
    void anUnreadableRuleIsStrict() {
        when(settings.getString(any(ConfigKey.class), any())).thenThrow(new IllegalStateException("store down"));
        Approval approval = requestedBy(approver, "OUTBOUND");

        assertThatThrownBy(() -> service.decide(ORG, approval.getId(), true, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    @DisplayName("an admin sets the rule for the workspace, and the change is audited with what it replaced")
    @SuppressWarnings("unchecked")
    void settingTheRule() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("off");

        assertThat(service.setRequesterRule(ORG, "  Destructive ", approver)).isEqualTo("destructive");

        verify(settings).set("approvals.requesterCannotApprove", ORG.toString(), "destructive", approver.id());
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(eq(ORG), eq(approver), eq("approval.settings_update"), eq("workspace"), eq(ORG.toString()), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("requesterCannotApprove", "destructive").containsEntry("previous", "off");
    }

    @Test
    @DisplayName("a rule that is not off, destructive or all is refused, and nothing is stored")
    void anUnknownRuleIsRefused() {
        assertThatThrownBy(() -> service.setRequesterRule(ORG, "sometimes", approver))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "requesterCannotApprove");
                });
        assertThatThrownBy(() -> service.setRequesterRule(ORG, null, approver)).isInstanceOf(ApiException.class);

        verify(settings, never()).set(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the rule reads as it is stored, and as off when nothing is")
    void readingTheRule() {
        assertThat(service.requesterRuleName(ORG)).isEqualTo("off");
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("all");
        assertThat(service.requesterRuleName(ORG)).isEqualTo("all");
    }

    @Test
    @DisplayName("the pending count says how many the caller could decide, with the rule applied in the database rows")
    void countHonoursPermissionAndTheRule() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("all");
        UUID me = UUID.fromString(approver.id());
        when(approvals.countPendingByDecider(ORG))
                .thenReturn(List.of(
                        new Object[] {"approval:decide", me, "OUTBOUND", 2L},
                        new Object[] {"approval:decide", UUID.randomUUID(), "OUTBOUND", 3L},
                        new Object[] {"approval:decide", null, "DESTRUCTIVE", 1L},
                        new Object[] {"settings:update", UUID.randomUUID(), "OUTBOUND", 4L}));

        ApprovalService.Counts counts = service.count(ORG, approver, null);

        assertThat(counts.pending()).isEqualTo(10);
        assertThat(counts.canDecide()).isEqualTo(4);
    }

    @Test
    @DisplayName("an agent's count reads only that agent's rows")
    void countForOneAgent() {
        UUID agentId = UUID.randomUUID();
        when(approvals.countPendingByDeciderForAgent(ORG, agentId))
                .thenReturn(List.<Object[]>of(new Object[] {"approval:decide", null, "OUTBOUND", 5L}));

        assertThat(service.count(ORG, approver, agentId)).isEqualTo(new ApprovalService.Counts(5, 5));
        verify(approvals, never()).countPendingByDecider(any());
    }

    // ---- Reading approvals ---------------------------------------------------------------------

    @Test
    @DisplayName("the queue is soonest-expiring first, the history newest decision first, and a page is bounded")
    void listOrdersEachViewForReading() {
        service.list(ORG, null, null, null, 0, 50);
        ArgumentCaptor<Pageable> queue = ArgumentCaptor.forClass(Pageable.class);
        verify(approvals).findByOrgIdAndStatusIn(eq(ORG), eq(List.of("pending")), queue.capture());
        assertThat(queue.getValue().getSort().toString()).startsWith("expiresAt: ASC");

        service.list(ORG, "decided", null, null, 2, 100_000);
        ArgumentCaptor<Pageable> history = ArgumentCaptor.forClass(Pageable.class);
        verify(approvals)
                .findByOrgIdAndStatusIn(
                        eq(ORG), eq(List.of("approved", "rejected", "expired", "cancelled")), history.capture());
        assertThat(history.getValue().getSort().toString()).startsWith("decidedAt: DESC");
        assertThat(history.getValue().getPageNumber()).isEqualTo(2);
        assertThat(history.getValue().getPageSize()).isEqualTo(ApprovalService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("an agent or a run narrows the list, and one outcome can be asked for alone")
    void listNarrows() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        service.list(ORG, "rejected", agentId, null, 0, 20);
        verify(approvals).findByOrgIdAndStatusInAndAgentId(eq(ORG), eq(List.of("rejected")), eq(agentId), any());

        service.list(ORG, "pending", agentId, runId, 0, 20);
        verify(approvals).findByOrgIdAndStatusInAndRunId(eq(ORG), eq(List.of("pending")), eq(runId), any());

        assertThatThrownBy(() -> service.list(ORG, "later", null, null, 0, 20))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("an approval's context carries its goal, who asked and what the task was told, in a few lookups")
    void contextForNamesTheWorkBehindAnApproval() {
        Goal goal = work.goal("running");
        UUID requester = UUID.randomUUID();
        goal.setRequestedBy(requester);
        Task task = work.task(goal, 0, "waiting_approval");
        task.setInstruction("y".repeat(900));
        Run run = runFor(task, "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(work.tasks.findAllById(anyCollection())).thenReturn(List.of(task));
        when(work.goals.findAllById(anyCollection())).thenReturn(List.of(goal));

        ApprovalService.Context context =
                service.contextFor(List.of(approval, approval)).get(approval.getId());

        assertThat(context.goalId()).isEqualTo(goal.getId());
        assertThat(context.goalTitle()).isEqualTo("Onboard the new starter");
        assertThat(context.requestedBy()).isEqualTo(requester);
        assertThat(context.taskInstruction()).hasSize(500).endsWith("\u2026");
        verify(work.tasks).findAllById(anyCollection());
        verify(work.goals).findAllById(anyCollection());
    }

    @Test
    @DisplayName("an approval from another workspace's goal is never described by it")
    void contextForIgnoresAnotherWorkspace() {
        Goal goal = work.goal("running");
        goal.setOrgId(UUID.randomUUID());
        Task task = work.task(goal, 0, "waiting_approval");
        task.setOrgId(goal.getOrgId());
        Run run = runFor(task, "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(work.tasks.findAllById(anyCollection())).thenReturn(List.of(task));
        when(work.goals.findAllById(anyCollection())).thenReturn(List.of(goal));

        assertThat(service.contextFor(List.of(approval)).get(approval.getId()))
                .isEqualTo(ApprovalService.Context.NONE);
    }

    @Test
    @DisplayName("deciding an approval that was already withdrawn is refused")
    void withdrawnApprovalRefused() {
        Run run = runFor(work.task(work.goal("cancelled"), 0, "cancelled"), "cancelled");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        approval.setStatus("cancelled");

        assertThatThrownBy(() -> service.decide(ORG, approval.getId(), true, null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    @Test
    @DisplayName("withdrawing a stopped run's approvals is one conditional bulk update, never a load and save")
    void cancelForRunWithdrawsWithBulkUpdate() {
        UUID runId = UUID.randomUUID();
        when(approvals.withdrawPending(eq(runId), any())).thenReturn(2);

        service.cancelForRun(runId);
        assertThat(service.withdrawForRun(runId)).isEqualTo(2);

        verify(approvals, org.mockito.Mockito.times(2)).withdrawPending(eq(runId), any());
        verify(approvals, never()).findByRunIdAndStatus(any(), any());
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("an approved run still parked is offered to the resume sweep once")
    void approvedAwaitingResumeListsEachRunOnce() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval first = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        Approval second = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(approvals.findApprovedAwaitingResume(any(), any())).thenReturn(List.of(first, second));

        assertThat(service.approvedAwaitingResume(Instant.now(), 50)).containsExactly(new RunRef(ORG, run.getId()));
    }

    private Approval requestedBy(Actor requester, String actionClass) {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "stripe.refund_payment", Instant.now().plus(1, ChronoUnit.DAYS));
        approval.setActionClass(actionClass);
        approval.setRequestedBy(requester == null ? null : UUID.fromString(requester.id()));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        return approval;
    }

    private static Agent agent(Run run) {
        Agent agent = new Agent();
        agent.setId(run.getAgentId());
        agent.setName("Finance Agent");
        return agent;
    }

    private static ToolCall call() {
        return new ToolCall("call_0", "stripe.refund_payment", "{}");
    }

    private static ApprovalDecision.AwaitApproval awaiting() {
        return new ApprovalDecision.AwaitApproval("Permanently remove something using stripe.refund_payment", "approval:decide");
    }

    // ---- Send back with feedback ---------------------------------------------------------------

    @Test
    @DisplayName("sending back keeps the run alive, marks the approval and records the feedback")
    void sendBackKeepsTheRunGoing() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(approvals.findByRunIdAndSentBackTrueOrderByRequestedAtAsc(run.getId())).thenReturn(List.of());

        ApprovalService.SendBack sent = service.sendBack(ORG, approval.getId(), "  Use a softer tone.  ");

        assertThat(sent.ended()).isFalse();
        assertThat(approval.getStatus()).isEqualTo("rejected");
        assertThat(approval.isSentBack()).isTrue();
        assertThat(approval.getDecisionNote()).isEqualTo("Use a softer tone.");
        assertThat(run.getStatus()).isEqualTo("waiting_approval");
        verify(runner, never()).finishTerminal(any(), any(), any());
    }

    @Test
    @DisplayName("sending back needs a note, because feedback with no content gives the agent nothing to revise")
    void sendBackNeedsANote() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.sendBack(ORG, approval.getId(), "   "))
                .isInstanceOf(ApiException.class);
        assertThat(approval.isPending()).isTrue();
    }

    @Test
    @DisplayName("sending the same tool back a second time ends the run after two rounds")
    void secondRoundEndsTheRun() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval first = approval(run, "gmail.send_message", Instant.now().minus(1, ChronoUnit.HOURS));
        first.decide("rejected", UUID.randomUUID(), "Softer.");
        first.setSentBack(true);
        Approval second = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(approvals.findByRunIdAndSentBackTrueOrderByRequestedAtAsc(run.getId())).thenReturn(List.of(first));

        ApprovalService.SendBack sent = service.sendBack(ORG, second.getId(), "Still too blunt.");

        assertThat(sent.ended()).isTrue();
        verify(runner).finishTerminal(run, "cancelled", ApprovalService.STOPPED_AFTER_ROUNDS);
    }

    @Test
    @DisplayName("a second approver giving the same answer a moment later is not turned away")
    void concurrentApproverWithTheSameAnswerSucceeds() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        approval.decide("approved", UUID.randomUUID(), null);

        Approval again = service.decide(ORG, approval.getId(), true, null);

        assertThat(again).isSameAs(approval);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the opposite answer to one already given is still refused")
    void oppositeAnswerIsRefused() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));
        approval.decide("approved", UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.decide(ORG, approval.getId(), false, null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    private static ToolInvocation invocation(Run run) {
        return new ToolInvocation(
                ORG.toString(),
                run.getAgentId().toString(),
                run.getId().toString(),
                "stripe",
                "refund_payment",
                "{\"payment\":\"pi_1\"}",
                run.getId() + ":call_0",
                Map.of());
    }

    private Approval approval(Run run, String tool, Instant expiresAt) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(run.getId());
        approval.setTaskId(run.getTaskId());
        approval.setAgentId(run.getAgentId());
        approval.setTool(tool);
        approval.setActionClass("OUTBOUND");
        approval.setSummary("Send the welcome email");
        approval.setExpiresAt(expiresAt);
        when(approvals.lockByIdAndOrgId(approval.getId(), ORG)).thenReturn(Optional.of(approval));
        return approval;
    }
}
