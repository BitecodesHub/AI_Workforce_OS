package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.TaskProgress;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The Runs page's filters reach the database, always inside the caller's own workspace. */
class RunControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.randomUUID();

    private Runs runs;
    private Tasks tasks;
    private Goals goals;
    private ApprovalService approvals;
    private TaskProgress progress;
    private RunController controller;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        tasks = mock(Tasks.class);
        goals = mock(Goals.class);
        approvals = mock(ApprovalService.class);
        progress = mock(TaskProgress.class);
        controller = new RunController(runs, mock(RunSteps.class), tasks, goals, approvals, progress);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("no filter lists every run in the workspace")
    void unfiltered() {
        when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any())).thenReturn(page(run("completed")));

        assertThat(controller.list(0, 25, null, null)).hasSize(1);
    }

    @Test
    @DisplayName("a run for a task carries the goal it belongs to and who asked for it")
    void carriesGoalAndRequester() {
        UUID requestedBy = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Run run = run("completed");
        UUID taskId = UUID.randomUUID();
        run.setTaskId(taskId);
        Task task = new Task();
        task.setId(taskId);
        task.setGoalId(goalId);
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setRequestedBy(requestedBy);
        when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any())).thenReturn(page(run));
        when(tasks.findAllById(Set.of(taskId))).thenReturn(List.of(task));
        when(goals.findAllById(Set.of(goalId))).thenReturn(List.of(goal));

        RunController.RunView view = controller.list(0, 25, null, null).getFirst();

        assertThat(view.goalId()).isEqualTo(goalId);
        assertThat(view.requestedBy()).isEqualTo(requestedBy);
    }

    @Test
    @DisplayName("a run started directly on an agent, with no task, carries no goal or requester")
    void manualRunCarriesNoGoal() {
        when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any())).thenReturn(page(run("completed")));

        RunController.RunView view = controller.list(0, 25, null, null).getFirst();

        assertThat(view.goalId()).isNull();
        assertThat(view.requestedBy()).isNull();
    }

    @Test
    @DisplayName("a status filter searches every run with that status")
    void byStatus() {
        when(runs.findByOrgIdAndStatusOrderByStartedAtDesc(eq(ORG), eq("failed"), any()))
                .thenReturn(page(run("failed")));

        assertThat(controller.list(0, 25, "failed", null)).extracting(RunController.RunView::status)
                .containsExactly("failed");
    }

    @Test
    @DisplayName("an agent filter searches every run by that agent")
    void byAgent() {
        when(runs.findByOrgIdAndAgentIdOrderByStartedAtDesc(eq(ORG), eq(AGENT), any()))
                .thenReturn(page(run("completed")));

        assertThat(controller.list(0, 25, "", AGENT)).hasSize(1);
    }

    @Test
    @DisplayName("both filters together narrow by both")
    void byAgentAndStatus() {
        when(runs.findByOrgIdAndAgentIdAndStatusOrderByStartedAtDesc(eq(ORG), eq(AGENT), eq("waiting_approval"), any()))
                .thenReturn(page(run("waiting_approval")));

        assertThat(controller.list(0, 25, " Waiting_Approval ", AGENT)).hasSize(1);
    }

    @Test
    @DisplayName("a status that no run can have is refused rather than silently returning nothing")
    void unknownStatusRefused() {
        assertThatThrownBy(() -> controller.list(0, 25, "finished", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "status");
                });
        verifyNoInteractions(runs);
    }

    @Test
    @DisplayName("the page size is capped at 100 and a nonsense page is read as the first")
    void pagingClamped() {
        when(runs.findByOrgIdOrderByStartedAtDesc(eq(ORG), any())).thenReturn(page());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        controller.list(-2, 500, null, null);

        verify(runs).findByOrgIdOrderByStartedAtDesc(eq(ORG), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("cancelling a run withdraws its approval and cancels its task")
    void cancelReportsToTask() {
        Run run = run("waiting_approval");
        when(runs.findByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

        RunController.RunView view = controller.cancel(run.getId());

        assertThat(view.status()).isEqualTo("cancelled");
        assertThat(view.failureReason()).isEqualTo(RunController.RUN_STOPPED);
        verify(approvals).cancelForRun(run.getId());
        verify(progress).onRunFinished(eq(run), eq("cancelled"), eq(null), any());
    }

    private static Page<Run> page(Run... rows) {
        return new PageImpl<>(List.of(rows));
    }

    private static Run run(String status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setAgentId(AGENT);
        run.setAgentVersionId(UUID.randomUUID());
        run.setStatus(status);
        return run;
    }
}
