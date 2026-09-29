package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

/** Rejection and expiry end the run, and now its task and goal too. */
class ApprovalServiceTest {

    private WorkFixture work;
    private Approvals approvals;
    private Runs runs;
    private AuditClient audit;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        approvals = mock(Approvals.class);
        runs = mock(Runs.class);
        audit = mock(AuditClient.class);
        service = new ApprovalService(
                approvals, runs, new ObjectMapper(), audit, new TaskProgress(work.tasks, work.goals, List.of()));
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L));
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

        service.decide(ORG, approval.getId(), false, "Not this week.");

        assertThat(approval.getStatus()).isEqualTo("rejected");
        assertThat(run.getStatus()).isEqualTo("cancelled");
        assertThat(task.getStatus()).isEqualTo("cancelled");
        assertThat(task.getFailureReason()).isEqualTo("An approver rejected the action this run needed.");
        assertThat(goal.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("the audit entry for a decision names the run and the tool")
    @SuppressWarnings("unchecked")
    void auditCarriesRun() {
        Goal goal = work.goal("running");
        Run run = runFor(work.task(goal, 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, "gmail.send_message", Instant.now().plus(1, ChronoUnit.DAYS));

        service.decide(ORG, approval.getId(), true, null);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        any(),
                        eq("approval.decide"),
                        eq("approval"),
                        eq(approval.getId().toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue())
                .containsEntry("approved", true)
                .containsEntry("runId", run.getId().toString())
                .containsEntry("tool", "gmail.send_message");
    }

    @Test
    @DisplayName("the audit entry leaves the tool out when the approval has none")
    @SuppressWarnings("unchecked")
    void auditWithoutTool() {
        Run run = runFor(work.task(work.goal("running"), 0, "waiting_approval"), "waiting_approval");
        Approval approval = approval(run, null, Instant.now().plus(1, ChronoUnit.DAYS));

        service.decide(ORG, approval.getId(), true, null);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(any(), any(), any(), any(), any(), any(), detail.capture());
        assertThat(detail.getValue()).containsOnlyKeys("approved", "runId");
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
        when(approvals.findByIdAndOrgId(approval.getId(), ORG)).thenReturn(Optional.of(approval));
        return approval;
    }
}
