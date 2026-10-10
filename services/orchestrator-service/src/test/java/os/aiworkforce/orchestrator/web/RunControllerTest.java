// @find: tests for run controller, runs api, run trace, steps, cancel run, /api/runs
// @what: Unit and integration tests (12 cases) for run controller, for example: unfiltered; carries goal and requester; manual run carries no goal; hidden work filtered in the query.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.orchestrator.service.QuestionService;
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
    private QuestionService questions;
    private GoalService goalService;
    private RunController controller;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        tasks = mock(Tasks.class);
        goals = mock(Goals.class);
        questions = mock(QuestionService.class);
        goalService = mock(GoalService.class);
        controller = new RunController(runs, mock(RunSteps.class), tasks, goals, questions, goalService);
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
    @DisplayName("runs of private conversations the caller is not part of are left out in the query, so pages stay full")
    void hiddenWorkFilteredInTheQuery() {
        UUID hiddenConversation = UUID.randomUUID();
        os.aiworkforce.orchestrator.chat.ConversationAccess access =
                mock(os.aiworkforce.orchestrator.chat.ConversationAccess.class);
        when(access.hiddenConversationIds(eq(ORG), any())).thenReturn(Set.of(hiddenConversation));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "access", access);
        when(runs.findVisible(
                        eq(ORG), eq(false), eq("failed"), eq(true), any(), eq(Set.of(hiddenConversation)), any()))
                .thenReturn(page(run("failed")));

        assertThat(controller.list(0, 25, "failed", null))
                .extracting(RunController.RunView::status)
                .containsExactly("failed");
        verify(runs, org.mockito.Mockito.never()).findByOrgIdAndStatusOrderByStartedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("a status filter searches every run with that status")
    void byStatus() {
        when(runs.findByOrgIdAndStatusOrderByStartedAtDesc(eq(ORG), eq("failed"), any()))
                .thenReturn(page(run("failed")));

        assertThat(controller.list(0, 25, "failed", null))
                .extracting(RunController.RunView::status)
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
    @DisplayName("cancelling a run goes through the one stop path, which locks it and withdraws what it waits on")
    void cancelDelegatesToStopRun() {
        Run run = run("waiting_input");
        when(goalService.stopRun(ORG, run.getId(), RunController.RUN_STOPPED)).thenAnswer(call -> {
            run.finish("cancelled", RunController.RUN_STOPPED);
            return new GoalService.CancelCounts(0, 1, 0, 1);
        });
        when(runs.findByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));

        RunController.RunView view = controller.cancel(run.getId());

        verify(goalService).stopRun(ORG, run.getId(), RunController.RUN_STOPPED);
        assertThat(view.status()).isEqualTo("cancelled");
        assertThat(view.failureReason()).isEqualTo(RunController.RUN_STOPPED);
    }

    @Test
    @DisplayName("a run waiting for an answer can be listed by that status")
    void listAcceptsWaitingInput() {
        when(runs.findByOrgIdAndStatusOrderByStartedAtDesc(eq(ORG), eq("waiting_input"), any()))
                .thenReturn(page(run("waiting_input")));

        assertThat(controller.list(0, 25, "waiting_input", null))
                .extracting(RunController.RunView::status)
                .containsExactly("waiting_input");
    }

    @Test
    @DisplayName("a run's questions are only read after the run is found in the caller's workspace")
    void questionsForRunRequiresOrgRun() {
        UUID elsewhere = UUID.randomUUID();
        when(runs.findByIdAndOrgId(elsewhere, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.questions(elsewhere))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(questions, never()).forRun(any(), any());

        Run run = run("waiting_input");
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        when(runs.findByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));
        when(questions.forRun(ORG, run.getId())).thenReturn(List.of(question));
        when(questions.views(eq(List.of(question)), any())).thenReturn(List.of());

        controller.questions(run.getId());

        verify(questions).forRun(ORG, run.getId());
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
