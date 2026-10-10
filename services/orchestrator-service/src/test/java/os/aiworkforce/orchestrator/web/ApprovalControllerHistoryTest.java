// @find: tests for approval controller history, approval history, list approvals, status filter, paging, /api/approvals
// @what: Unit and integration tests (9 cases) for approval controller history, for example: view exposes the decision and the work behind it; list passes its filters on; count view; get one.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The queue and its history: what an approval card carries, the counts, the one-approval read, and
 * a bulk decision that answers for each approval on its own.
 */
class ApprovalControllerHistoryTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private ApprovalService approvals;
    private RunExecutor executor;
    private ApprovalController controller;
    private Actor approver;

    @BeforeEach
    void setUp() {
        approvals = mock(ApprovalService.class);
        executor = mock(RunExecutor.class);
        controller = new ApprovalController(approvals, executor, mock(Runs.class));
        approver = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L);
        RequestContext.setActor(approver);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("an approval carries who decided it and why, the goal and requester behind it, and whether this caller may decide")
    void viewExposesTheDecisionAndTheWorkBehindIt() {
        UUID goalId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID decider = UUID.randomUUID();
        Approval decided = approval("rejected");
        decided.setDecidedBy(decider);
        decided.setDecidedAt(Instant.parse("2026-10-04T10:42:00Z"));
        decided.setDecisionNote("Wrong recipient.");
        Approval waiting = approval("pending");
        when(approvals.list(ORG, "all", null, null, 0, 100)).thenReturn(List.of(decided, waiting));
        when(approvals.contextFor(any()))
                .thenReturn(Map.of(
                        decided.getId(),
                        new ApprovalService.Context(goalId, "Welcome the new starter", requester, "Email the candidate."),
                        waiting.getId(),
                        ApprovalService.Context.NONE));
        when(approvals.canDecide(approver, waiting)).thenReturn(true);

        List<ApprovalController.ApprovalView> views = controller.list("all", null, null, 0, 100);

        ApprovalController.ApprovalView first = views.get(0);
        assertThat(first.status()).isEqualTo("rejected");
        assertThat(first.decidedBy()).isEqualTo(decider);
        assertThat(first.decidedAt()).isEqualTo(Instant.parse("2026-10-04T10:42:00Z"));
        assertThat(first.decisionNote()).isEqualTo("Wrong recipient.");
        assertThat(first.goalId()).isEqualTo(goalId);
        assertThat(first.goalTitle()).isEqualTo("Welcome the new starter");
        assertThat(first.requestedBy()).isEqualTo(requester);
        assertThat(first.taskInstruction()).isEqualTo("Email the candidate.");
        assertThat(first.actionClass()).isEqualTo("OUTBOUND");
        assertThat(first.expiresAt()).isNotNull();
        assertThat(first.canDecide()).isFalse();

        ApprovalController.ApprovalView second = views.get(1);
        assertThat(second.goalId()).isNull();
        assertThat(second.goalTitle()).isNull();
        assertThat(second.decidedBy()).isNull();
        assertThat(second.canDecide()).isTrue();
    }

    @Test
    @DisplayName("the list passes its filters on, and asks for the queue when none is given")
    void listPassesItsFiltersOn() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        controller.list("pending", null, null, 0, 100);
        controller.list("decided", agentId, null, 2, 25);
        controller.list("pending", null, runId, 0, 5);

        verify(approvals).list(ORG, "pending", null, null, 0, 100);
        verify(approvals).list(ORG, "decided", agentId, null, 2, 25);
        verify(approvals).list(ORG, "pending", null, runId, 0, 5);
    }

    @Test
    @DisplayName("the count says how many are waiting and how many this caller could decide")
    void countView() {
        UUID agentId = UUID.randomUUID();
        when(approvals.count(ORG, approver, null)).thenReturn(new ApprovalService.Counts(7, 4));
        when(approvals.count(ORG, approver, agentId)).thenReturn(new ApprovalService.Counts(2, 0));

        assertThat(controller.count(null)).isEqualTo(new ApprovalController.CountView(7, 4));
        assertThat(controller.count(agentId)).isEqualTo(new ApprovalController.CountView(2, 0));
    }

    @Test
    @DisplayName("one approval is read by its id, and an unknown one is not found")
    void getOne() {
        Approval approval = approval("approved");
        when(approvals.get(ORG, approval.getId())).thenReturn(approval);
        when(approvals.contextFor(any())).thenReturn(Map.of());
        UUID missing = UUID.randomUUID();
        when(approvals.get(ORG, missing)).thenThrow(ApiException.notFound("approval", missing));

        assertThat(controller.get(approval.getId()).id()).isEqualTo(approval.getId());
        assertThatThrownBy(() -> controller.get(missing))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("the four-eyes rule is read by anyone who reads approvals, and set through the service")
    void settings() {
        when(approvals.requesterRuleName(ORG)).thenReturn("destructive");
        when(approvals.setRequesterRule(ORG, "all", approver)).thenReturn("all");

        assertThat(controller.settings()).isEqualTo(new ApprovalController.SettingsView("destructive"));
        assertThat(controller.updateSettings(new ApprovalController.UpdateSettingsRequest("all")))
                .isEqualTo(new ApprovalController.SettingsView("all"));
    }

    // ---- Bulk decisions ------------------------------------------------------------------------

    @Test
    @DisplayName("a bulk decision answers for each approval, and only an approval that was decided resumes its run")
    void bulkDecisionReportsEachOne() {
        Approval decided = approval("approved");
        UUID already = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID notYours = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(approvals.decide(ORG, decided.getId(), true, "Fine.")).thenReturn(decided);
        when(approvals.decide(ORG, already, true, "Fine."))
                .thenThrow(new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED).with("status", "rejected"));
        when(approvals.decide(ORG, expired, true, "Fine.")).thenThrow(new ApiException(ErrorCode.APPROVAL_EXPIRED));
        when(approvals.decide(ORG, notYours, true, "Fine."))
                .thenThrow(new ApiException(ErrorCode.POLICY_VIOLATION, "Someone other than the requester must decide this"));
        when(approvals.decide(ORG, missing, true, "Fine.")).thenThrow(ApiException.notFound("approval", missing));

        ApprovalController.BulkDecisionResult result = controller.decideMany(new ApprovalController.BulkDecisionRequest(
                List.of(decided.getId(), already, expired, notYours, missing), true, "Fine."));

        assertThat(result.results())
                .extracting(ApprovalController.BulkItemResult::result)
                .containsExactly("decided", "already_decided", "expired", "forbidden", "not_found");
        assertThat(result.results())
                .extracting(ApprovalController.BulkItemResult::status)
                .containsExactly("approved", "rejected", "expired", null, null);
        assertThat(result.results().get(3).message()).isEqualTo("Someone other than the requester must decide this");
        assertThat(result.results().get(0).message()).isNull();
        verify(executor).submitResume(ORG, decided.getRunId(), approver);
        verify(executor, times(1)).submitResume(any(), any(), any());
    }

    @Test
    @DisplayName("a missing permission reads as forbidden, the same as the four-eyes rule")
    void permissionDeniedIsForbidden() {
        UUID id = UUID.randomUUID();
        when(approvals.decide(ORG, id, false, null)).thenThrow(ApiException.permissionDenied("approval:decide"));

        ApprovalController.BulkDecisionResult result =
                controller.decideMany(new ApprovalController.BulkDecisionRequest(List.of(id), false, null));

        assertThat(result.results().get(0).result()).isEqualTo("forbidden");
    }

    @Test
    @DisplayName("rejecting in bulk resumes nothing, and a repeated id is decided once")
    void bulkRejectionResumesNothing() {
        Approval rejected = approval("rejected");
        when(approvals.decide(ORG, rejected.getId(), false, "No.")).thenReturn(rejected);

        ApprovalController.BulkDecisionResult result = controller.decideMany(
                new ApprovalController.BulkDecisionRequest(List.of(rejected.getId(), rejected.getId()), false, "No."));

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).result()).isEqualTo("decided");
        verify(approvals).decide(ORG, rejected.getId(), false, "No.");
        verify(executor, never()).submitResume(any(), any(), any());
        verify(executor, never()).submitResume(any(), any());
    }

    @Test
    @DisplayName("each approved run is handed to the executor as the approver, never carried on inside the request")
    void everyApprovedRunIsHandedOn() {
        Approval first = approval("approved");
        Approval second = approval("approved");
        when(approvals.decide(eq(ORG), eq(first.getId()), eq(true), any())).thenReturn(first);
        when(approvals.decide(eq(ORG), eq(second.getId()), eq(true), any())).thenReturn(second);

        controller.decideMany(
                new ApprovalController.BulkDecisionRequest(List.of(first.getId(), second.getId()), true, null));

        verify(executor).submitResume(ORG, first.getRunId(), approver);
        verify(executor).submitResume(ORG, second.getRunId(), approver);
    }

    private static Approval approval(String status) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setTool("gmail.send_message");
        approval.setActionClass("OUTBOUND");
        approval.setSummary("Send something outside the workspace using gmail.send_message");
        approval.setStatus(status);
        approval.setExpiresAt(Instant.parse("2026-10-05T10:00:00Z"));
        return approval;
    }
}
