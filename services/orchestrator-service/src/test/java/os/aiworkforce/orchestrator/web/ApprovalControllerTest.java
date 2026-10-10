// @find: tests for approval controller, approvals api, decide, /api/approvals/{id}/decision, approve, reject
// @what: Unit and integration tests (3 cases) for approval controller, for example: approve returns without driving the run; approve reports the stored status; reject resumes nothing.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * Deciding an approval answers as soon as the decision is saved. The approved call and the rest of
 * the run are handed to the executor, never carried out inside the approver's request.
 */
class ApprovalControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private ApprovalService approvals;
    private RunExecutor executor;
    private Runs runs;
    private ApprovalController controller;
    private Actor approver;

    @BeforeEach
    void setUp() {
        approvals = mock(ApprovalService.class);
        executor = mock(RunExecutor.class);
        runs = mock(Runs.class);
        controller = new ApprovalController(approvals, executor, runs);
        approver = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("approval:decide"), 0L);
        RequestContext.setActor(approver);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("approving hands the run to the executor and answers at once, reporting it as carrying on")
    void approveReturnsWithoutDrivingTheRun() {
        Run run = run("waiting_approval");
        Approval approval = decided(run, "approved");
        when(approvals.decide(ORG, approval.getId(), true, null)).thenReturn(approval);

        ApprovalController.DecisionResult result =
                controller.decide(approval.getId(), new ApprovalController.DecisionRequest(true, null));

        verify(executor).submitResume(ORG, run.getId(), approver);
        assertThat(result.approvalId()).isEqualTo(approval.getId());
        assertThat(result.status()).isEqualTo("approved");
        assertThat(result.runStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("a run already picked up again is reported as the database has it")
    void approveReportsTheStoredStatus() {
        Run run = run("running");
        Approval approval = decided(run, "approved");
        when(approvals.decide(ORG, approval.getId(), true, null)).thenReturn(approval);

        ApprovalController.DecisionResult result =
                controller.decide(approval.getId(), new ApprovalController.DecisionRequest(true, null));

        assertThat(result.runStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("rejecting resumes nothing, and reports the run as the rejection left it")
    void rejectResumesNothing() {
        Run run = run("cancelled");
        Approval approval = decided(run, "rejected");
        when(approvals.decide(ORG, approval.getId(), false, "Not this week.")).thenReturn(approval);

        ApprovalController.DecisionResult result =
                controller.decide(approval.getId(), new ApprovalController.DecisionRequest(false, "Not this week."));

        verify(executor, never()).submitResume(any(), any(), any());
        verify(executor, never()).submitResume(any(), any());
        assertThat(result.status()).isEqualTo("rejected");
        assertThat(result.runStatus()).isEqualTo("cancelled");
    }

    private Run run(String status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setStatus(status);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        return run;
    }

    private static Approval decided(Run run, String status) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(run.getId());
        approval.setStatus(status);
        return approval;
    }
}
