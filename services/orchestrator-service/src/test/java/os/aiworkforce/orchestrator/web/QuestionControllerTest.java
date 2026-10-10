// @find: tests for question controller, questions api, answer, extend, /api/orchestrator/questions
// @what: Unit and integration tests (8 cases) for question controller, for example: answer submits resume once; repeat answer does not resume again; answer by non requester without cancel is403; list pending mine filters.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The question endpoints, against the real question rules: an answer resumes its run once, and
 * only the right people can answer or extend.
 */
class QuestionControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final String QUESTIONS_JSON =
            """
            [{"id":"q1","header":"Audience","question":"Who is this for?","multiSelect":false,"options":[
              {"label":"The whole team","description":"Plain language, no assumed context.","recommended":true},
              {"label":"Managers","description":"Shorter, with the decisions first.","recommended":false}]}]
            """;

    private RunQuestions rows;
    private Runs runs;
    private RunExecutor executor;
    private QuestionController controller;
    private Run run;
    private RunQuestion question;

    @BeforeEach
    void setUp() {
        rows = mock(RunQuestions.class);
        runs = mock(Runs.class);
        executor = mock(RunExecutor.class);
        QuestionService questions = new QuestionService(
                rows,
                runs,
                mock(Tasks.class),
                mock(Goals.class),
                new ObjectMapper(),
                mock(AuditClient.class),
                Duration.ofHours(24));
        controller = new QuestionController(questions, executor, runs);

        run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setAgentId(UUID.randomUUID());
        run.setStatus("waiting_input");
        question = question(REQUESTER);
        lenient().when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        lenient().when(rows.lockByIdAndOrgId(question.getId(), ORG)).thenReturn(Optional.of(question));
        lenient().when(rows.save(any())).thenAnswer(call -> call.getArgument(0));
        actAs(REQUESTER, Set.of("task:create", "run:read"));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("a new answer submits the run's resume once, and reports the run's status")
    void answerSubmitsResumeOnce() {
        QuestionController.AnswerResult result = controller.answer(question.getId(), managers());

        verify(executor).submitResume(eq(ORG), eq(run.getId()), any());
        assertThat(result.question().status()).isEqualTo("answered");
        assertThat(result.runStatus()).isEqualTo("waiting_input");
    }

    @Test
    @DisplayName("the same answer sent again is accepted without resuming the run a second time")
    void repeatAnswerDoesNotResumeAgain() {
        controller.answer(question.getId(), managers());

        QuestionController.AnswerResult again = controller.answer(question.getId(), managers());

        verify(executor, times(1)).submitResume(eq(ORG), eq(run.getId()), any());
        assertThat(again.question().status()).isEqualTo("answered");
    }

    @Test
    @DisplayName("someone else without task:cancel cannot answer, and nothing resumes")
    void answerByNonRequesterWithoutCancelIs403() {
        actAs(UUID.randomUUID(), Set.of("task:create", "run:read"));

        assertThatThrownBy(() -> controller.answer(question.getId(), managers()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                    assertThat(e.details()).containsEntry("requiredPermission", "task:cancel");
                });
        verify(executor, never()).submitResume(any(), any(), any());
        assertThat(question.getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("mine lists only the questions asked for the caller's own work")
    void listPendingMineFilters() {
        RunQuestion someoneElses = question(UUID.randomUUID());
        when(rows.findPending(eq(ORG), any())).thenReturn(List.of(question, someoneElses));

        List<QuestionService.QuestionView> mine = controller.list("pending", true, 50);
        List<QuestionService.QuestionView> all = controller.list("pending", false, 50);

        assertThat(mine).extracting(QuestionService.QuestionView::id).containsExactly(question.getId());
        assertThat(all).hasSize(2);
        assertThat(all).extracting(QuestionService.QuestionView::canAnswer).containsExactly(true, false);
    }

    @Test
    @DisplayName("a status other than pending or all is refused")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> controller.list("answered", false, 50))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("extending returns the question with its later closing time")
    void extendReturnsTheExtendedView() {
        Instant before = question.getExpiresAt();

        QuestionService.QuestionView view = controller.extend(question.getId());

        assertThat(view.expiresAt()).isEqualTo(before.plus(Duration.ofHours(24)));
        assertThat(view.status()).isEqualTo("pending");
    }

    @Test
    @DisplayName("someone else without task:cancel cannot extend a question")
    void extendByNonRequesterIs403() {
        actAs(UUID.randomUUID(), Set.of("agent:run", "run:read"));
        Instant before = question.getExpiresAt();

        assertThatThrownBy(() -> controller.extend(question.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        assertThat(question.getExpiresAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("a question outside the workspace is not found")
    void getOutsideWorkspaceIsNotFound() {
        UUID elsewhere = UUID.randomUUID();
        when(rows.findByIdAndOrgId(elsewhere, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(elsewhere))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private RunQuestion question(UUID requestedBy) {
        RunQuestion row = new RunQuestion();
        row.setId(UUID.randomUUID());
        row.setOrgId(ORG);
        row.setRunId(run.getId());
        row.setAgentId(run.getAgentId());
        row.setToolCallId("t0_0_call_0");
        row.setQuestionsJson(QUESTIONS_JSON);
        row.setRequestedBy(requestedBy);
        row.setExpiresAt(Instant.now().plus(Duration.ofHours(23)));
        return row;
    }

    private static QuestionController.AnswerRequest managers() {
        return new QuestionController.AnswerRequest(
                List.of(new QuestionController.AnswerItemRequest("q1", List.of("Managers"), null)),
                null,
                false,
                "chat");
    }

    private static void actAs(UUID person, Set<String> permissions) {
        RequestContext.setActor(Actor.user(person.toString(), ORG.toString(), "role", permissions, 0L));
    }
}
