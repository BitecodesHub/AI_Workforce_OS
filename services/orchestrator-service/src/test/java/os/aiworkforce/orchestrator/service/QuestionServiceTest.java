package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;
import static os.aiworkforce.orchestrator.service.WorkFixture.runFor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * Questions a run asks: who may answer, what an answer must hold, and that an answer never
 * reaches work that has stopped.
 */
class QuestionServiceTest {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final Set<String> EMPLOYEE = Set.of("task:create", "chat:use");
    private static final Set<String> MANAGER = Set.of("task:create", "task:cancel", "chat:use");

    private final ObjectMapper json = new ObjectMapper();
    private WorkFixture work;
    private RunQuestions rows;
    private Runs runs;
    private AuditClient audit;
    private QuestionService service;
    private final List<RunQuestion> allQuestions = new ArrayList<>();
    private final List<Run> allRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        rows = mock(RunQuestions.class);
        runs = mock(Runs.class);
        audit = mock(AuditClient.class);
        lenient().when(rows.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(rows.lockByIdAndOrgId(any(), any())).thenAnswer(call -> findQuestion(call.getArgument(0)));
        lenient().when(rows.lockById(any())).thenAnswer(call -> findQuestion(call.getArgument(0)));
        lenient().when(rows.findByIdAndOrgId(any(), any())).thenAnswer(call -> findQuestion(call.getArgument(0)));
        lenient().when(runs.findById(any())).thenAnswer(call -> allRuns.stream()
                .filter(run -> run.getId().equals(call.getArgument(0)))
                .findFirst());
        lenient().when(runs.findAllById(any())).thenAnswer(call -> {
            Iterable<?> ids = call.getArgument(0);
            List<Object> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return allRuns.stream().filter(run -> wanted.contains(run.getId())).toList();
        });
        lenient().when(work.goals.findAllById(any())).thenAnswer(call -> {
            Iterable<?> ids = call.getArgument(0);
            List<Object> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return work.allGoals.stream()
                    .filter(goal -> wanted.contains(goal.getId()))
                    .toList();
        });
        service = new QuestionService(rows, runs, work.tasks, work.goals, json, audit, WINDOW);
        actAs(REQUESTER, EMPLOYEE);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // ---- Asking ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("raising a question")
    class Raise {

        @Test
        @DisplayName("takes the conversation and the requester from the run's goal")
        void takesConversationAndRequesterFromGoal() {
            Goal goal = work.goal("running");
            UUID conversation = UUID.randomUUID();
            goal.setConversationId(conversation);
            goal.setRequestedBy(REQUESTER);
            Task task = work.task(goal, 0, "running");
            Run run = runFor(task, "running");
            when(rows.findByRunIdAndToolCallId(run.getId(), "t0_0_call_0")).thenReturn(Optional.empty());

            RunQuestion raised = service.raise(run, agent(), "t0_0_call_0", oneQuestion());

            assertThat(raised.getGoalId()).isEqualTo(goal.getId());
            assertThat(raised.getTaskId()).isEqualTo(task.getId());
            assertThat(raised.getConversationId()).isEqualTo(conversation);
            assertThat(raised.getRequestedBy()).isEqualTo(REQUESTER);
            assertThat(raised.getToolCallId()).isEqualTo("t0_0_call_0");
            assertThat(raised.getStatus()).isEqualTo("pending");
            assertThat(raised.getExpiresAt()).isAfter(Instant.now().plus(WINDOW).minusSeconds(60));
            assertThat(service.readQuestions(raised))
                    .extracting(AskPersonTool.Question::id)
                    .containsExactly("q1");
            verify(rows).save(raised);
        }

        @Test
        @DisplayName("is idempotent on the tool call id: a replay returns the row already written")
        void idempotentOnToolCallId() {
            Run run = runFor(null, "running");
            RunQuestion existing = question(run, "pending");
            when(rows.findByRunIdAndToolCallId(run.getId(), existing.getToolCallId()))
                    .thenReturn(Optional.of(existing));

            RunQuestion raised = service.raise(run, agent(), existing.getToolCallId(), oneQuestion());

            assertThat(raised).isSameAs(existing);
            verify(rows, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the ask policy")
    class Policy {

        @Test
        @DisplayName("refuses a scheduled goal, because nobody is watching")
        void refusesScheduleGoal() {
            Goal goal = work.goal("running");
            goal.setSource("schedule");
            Run run = runFor(work.task(goal, 0, "running"), "running");

            QuestionService.AskPolicy policy = service.policyFor(run);

            assertThat(policy.allowed()).isFalse();
            assertThat(policy.reason()).contains("scheduled work");
        }

        @Test
        @DisplayName("refuses a fourth ask, and says the run has asked before")
        void refusesFourthAsk() {
            Run run = runFor(null, "running");
            when(rows.countByRunId(run.getId())).thenReturn(3L);

            QuestionService.AskPolicy policy = service.policyFor(run);

            assertThat(policy.allowed()).isFalse();
            assertThat(policy.everAsked()).isTrue();
            assertThat(policy.reason()).contains("as many questions as it may");
        }

        @Test
        @DisplayName("refuses a run with an unanswered, expired question")
        void refusesAfterExpiry() {
            Run run = runFor(null, "running");
            when(rows.countByRunId(run.getId())).thenReturn(1L);
            when(rows.existsByRunIdAndStatus(run.getId(), "expired")).thenReturn(true);

            QuestionService.AskPolicy policy = service.policyFor(run);

            assertThat(policy.allowed()).isFalse();
            assertThat(policy.reason()).contains("went unanswered");
        }

        @Test
        @DisplayName("allows a chat goal that has not asked yet")
        void allowsFirstAsk() {
            Goal goal = work.goal("running");
            goal.setSource("chat");
            Run run = runFor(work.task(goal, 0, "running"), "running");

            QuestionService.AskPolicy policy = service.policyFor(run);

            assertThat(policy.allowed()).isTrue();
            assertThat(policy.everAsked()).isFalse();
            assertThat(policy.reason()).isNull();
        }
    }

    // ---- Answering ------------------------------------------------------------------------------

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("records the answer, who gave it and where")
        void answers() {
            RunQuestion question = parkedQuestion();

            QuestionService.AnswerOutcome outcome = service.answer(ORG, question.getId(), pick("q1", "Managers"));

            assertThat(outcome.newlyAnswered()).isTrue();
            assertThat(question.getStatus()).isEqualTo("answered");
            assertThat(question.getAnsweredBy()).isEqualTo(REQUESTER);
            assertThat(question.getAnsweredVia()).isEqualTo("chat");
            assertThat(question.getAnsweredAt()).isNotNull();
            JsonNode stored = readTree(question.getAnswerJson());
            assertThat(stored.at("/answers/0/selected/0").asText()).isEqualTo("Managers");
            assertThat(stored.get("skipped").asBoolean()).isFalse();
            verify(audit)
                    .record(
                            eq(ORG),
                            any(),
                            eq("question.answer"),
                            eq("question"),
                            eq(question.getId().toString()),
                            eq("succeeded"),
                            any());
        }

        @Test
        @DisplayName("the same person repeating the same answer is told it was received, and nothing is new")
        void repeatAnswerIsIdempotent() {
            RunQuestion question = parkedQuestion();
            service.answer(ORG, question.getId(), pick("q1", "Managers"));

            QuestionService.AnswerOutcome again = service.answer(ORG, question.getId(), pick("q1", " Managers "));

            assertThat(again.newlyAnswered()).isFalse();
            assertThat(again.question().getStatus()).isEqualTo("answered");
        }

        @Test
        @DisplayName("a repeat is still idempotent when the stored JSON has its keys in another order")
        void repeatAnswerWithReorderedKeysIsIdempotent() {
            RunQuestion question = parkedQuestion();
            question.setStatus("answered");
            question.setAnsweredBy(REQUESTER);
            // How JSONB may hand it back: keys reordered, nothing else changed.
            question.setAnswerJson("{\"skipped\":false,\"note\":null,"
                    + "\"answers\":[{\"other\":null,\"selected\":[\"Managers\"],\"questionId\":\"q1\"}]}");

            QuestionService.AnswerOutcome again = service.answer(ORG, question.getId(), pick("q1", "Managers"));

            assertThat(again.newlyAnswered()).isFalse();
        }

        @Test
        @DisplayName("a different second answer is refused as already answered")
        void differentSecondAnswerConflicts() {
            RunQuestion question = parkedQuestion();
            service.answer(ORG, question.getId(), pick("q1", "Managers"));

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "A customer")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.QUESTION_ALREADY_ANSWERED);
                        assertThat(e.status()).isEqualTo(409);
                        assertThat(e.details()).containsEntry("status", "answered");
                    });
        }

        @Test
        @DisplayName("a question past its time is closed as expired and stays expired")
        void expiredQuestionIsClosed() {
            RunQuestion question = parkedQuestion();
            question.setExpiresAt(Instant.now().minusSeconds(5));

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "Managers")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.QUESTION_CLOSED);
                        assertThat(e.details()).containsEntry("status", "expired");
                    });
            assertThat(question.getStatus()).isEqualTo("expired");
            assertThat(question.getAnswerJson()).isNull();
            verify(rows).save(question);
        }

        @Test
        @DisplayName("a pending question whose run was stopped is closed, and the answer refused")
        void answerForStoppedRunIsClosed() {
            RunQuestion question = parkedQuestion();
            runOf(question).setStatus("cancelled");

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "Managers")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.QUESTION_CLOSED);
                        assertThat(e.details()).containsEntry("status", "cancelled");
                    });
            assertThat(question.getStatus()).isEqualTo("cancelled");
            assertThat(question.getClosedReason()).isEqualTo(QuestionService.STOPPED_WORK);
        }

        @Test
        @DisplayName("a withdrawn question is refused as closed")
        void withdrawnQuestionIsClosed() {
            RunQuestion question = parkedQuestion();
            question.setStatus("cancelled");

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "Managers")))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.QUESTION_CLOSED));
        }

        @Test
        @DisplayName("someone else without task:cancel is refused, and told which permission would do")
        void nonRequesterWithoutCancelIs403() {
            RunQuestion question = parkedQuestion();
            actAs(UUID.randomUUID(), EMPLOYEE);

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "Managers")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                        assertThat(e.details()).containsEntry("requiredPermission", "task:cancel");
                    });
            assertThat(question.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("a manager, who can cancel work, may answer someone else's question")
        void managerMayAnswer() {
            RunQuestion question = parkedQuestion();
            UUID manager = UUID.randomUUID();
            actAs(manager, MANAGER);

            QuestionService.AnswerOutcome outcome = service.answer(ORG, question.getId(), pick("q1", "Managers"));

            assertThat(outcome.newlyAnswered()).isTrue();
            assertThat(question.getAnsweredBy()).isEqualTo(manager);
        }

        @Test
        @DisplayName("a question in another workspace is not found")
        void otherWorkspaceIsNotFound() {
            RunQuestion question = parkedQuestion();
            when(rows.lockByIdAndOrgId(eq(question.getId()), eq(ORG))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.answer(ORG, question.getId(), pick("q1", "Managers")))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("validating an answer")
    class Validation {

        @Test
        @DisplayName("an option that was not offered is refused")
        void unknownLabel() {
            RunQuestion question = parkedQuestion();

            assertValidationFails(question, pick("q1", "Everyone in the building"), "not offered");
        }

        @Test
        @DisplayName("two options on a single-choice question are refused")
        void twoLabelsOnSingleSelect() {
            RunQuestion question = parkedQuestion();

            assertValidationFails(
                    question,
                    input(
                            List.of(new QuestionService.AnswerItem("q1", List.of("Managers", "A customer"), null)),
                            null,
                            false),
                    "choose one option");
        }

        @Test
        @DisplayName("an unanswered question with no reply in the person's own words is refused")
        void missingQuestionWithoutNote() {
            RunQuestion question = parkedQuestion(twoQuestions());

            assertValidationFails(question, pick("q1", "Managers"), "answer every question");
        }

        @Test
        @DisplayName("a partial answer with a reply in the person's own words is accepted")
        void partialWithNotePasses() {
            RunQuestion question = parkedQuestion(twoQuestions());

            QuestionService.AnswerOutcome outcome = service.answer(
                    ORG,
                    question.getId(),
                    input(
                            List.of(new QuestionService.AnswerItem("q1", List.of("Managers"), null)),
                            "Whatever you think is useful for the rest.",
                            false));

            assertThat(outcome.newlyAnswered()).isTrue();
        }

        @Test
        @DisplayName("several options, and a written answer, are accepted on a multi-select question")
        void multiSelectTakesSeveral() {
            RunQuestion question = parkedQuestion(twoQuestions());

            QuestionService.AnswerOutcome outcome = service.answer(
                    ORG,
                    question.getId(),
                    input(
                            List.of(
                                    new QuestionService.AnswerItem("q1", List.of("Managers"), null),
                                    new QuestionService.AnswerItem(
                                            "q2", List.of("A summary", "Next steps"), "Keep it to one page")),
                            null,
                            false));

            assertThat(outcome.newlyAnswered()).isTrue();
        }

        @Test
        @DisplayName("letting the agent decide with answers attached is refused")
        void skippedWithAnswersIs422() {
            RunQuestion question = parkedQuestion();

            assertValidationFails(
                    question,
                    input(List.of(new QuestionService.AnswerItem("q1", List.of("Managers"), null)), null, true),
                    "leave answers empty");
        }

        @Test
        @DisplayName("letting the agent decide with no answers is accepted")
        void skippedAlonePasses() {
            RunQuestion question = parkedQuestion();

            assertThat(service.answer(ORG, question.getId(), input(List.of(), null, true))
                            .newlyAnswered())
                    .isTrue();
            assertThat(readTree(question.getAnswerJson()).get("skipped").asBoolean())
                    .isTrue();
        }

        @Test
        @DisplayName("an answer to a question that was not asked is refused")
        void unknownQuestionId() {
            RunQuestion question = parkedQuestion();

            assertValidationFails(question, pick("q7", "Managers"), "not asked");
        }

        private void assertValidationFails(RunQuestion question, QuestionService.AnswerInput input, String problem) {
            assertThatThrownBy(() -> service.answer(ORG, question.getId(), input))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.status()).isEqualTo(422);
                        assertThat(String.valueOf(e.details().get("problem"))).contains(problem);
                    });
            assertThat(question.getStatus()).isEqualTo("pending");
        }
    }

    // ---- Expiry, withdrawal and extension -------------------------------------------------------

    @Nested
    @DisplayName("closing questions")
    class Closing {

        @Test
        @DisplayName("withdrawing a run's questions is one conditional bulk update, never a load and save")
        void cancelForRunIsBulk() {
            UUID runId = UUID.randomUUID();
            when(rows.withdrawPending(eq(runId), eq("Stopped."), any())).thenReturn(1);

            assertThat(service.cancelForRun(runId, "Stopped.")).isEqualTo(1);

            verify(rows).withdrawPending(eq(runId), eq("Stopped."), any());
            verifyNoMoreInteractions(rows);
        }

        @Test
        @DisplayName("an overdue pending question is expired, and its run handed back to resume")
        void expireOneExpiresOverdue() {
            RunQuestion question = parkedQuestion();
            question.setExpiresAt(Instant.now().minusSeconds(1));

            Optional<RunRef> ref = service.expireOne(question.getId());

            assertThat(ref).contains(new RunRef(ORG, question.getRunId()));
            assertThat(question.getStatus()).isEqualTo("expired");
            assertThat(question.getClosedReason()).isEqualTo("No answer arrived before the question closed.");
            verify(audit)
                    .record(eq(ORG), any(), eq("question.expire"), eq("question"), anyString(), eq("succeeded"), any());
        }

        @Test
        @DisplayName("an expiry that finds the locked row already answered changes nothing")
        void expireOneLosesToConcurrentAnswer() {
            RunQuestion question = parkedQuestion();
            question.setExpiresAt(Instant.now().minusSeconds(1));
            question.setStatus("answered");

            assertThat(service.expireOne(question.getId())).isEmpty();
            assertThat(question.getStatus()).isEqualTo("answered");
            verify(rows, never()).save(any());
        }

        @Test
        @DisplayName("a question still inside its window is not expired")
        void expireOneLeavesOpenQuestion() {
            RunQuestion question = parkedQuestion();

            assertThat(service.expireOne(question.getId())).isEmpty();
            assertThat(question.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("a pending question whose run stopped is closed; one whose run still waits is left")
        void closeStranded() {
            RunQuestion stranded = parkedQuestion();
            runOf(stranded).setStatus("failed");
            RunQuestion waiting = parkedQuestion();

            service.closeStranded(stranded.getId());
            service.closeStranded(waiting.getId());

            assertThat(stranded.getStatus()).isEqualTo("cancelled");
            assertThat(stranded.getClosedReason()).isEqualTo(QuestionService.STOPPED_WORK);
            assertThat(waiting.getStatus()).isEqualTo("pending");
        }

        @Test
        @DisplayName("extending adds one window, up to seven days from when it was asked")
        void extendAddsAWindow() {
            RunQuestion question = parkedQuestion();
            Instant before = question.getExpiresAt();

            RunQuestion extended = service.extend(ORG, question.getId());

            assertThat(extended.getExpiresAt()).isEqualTo(before.plus(WINDOW));
            verify(audit)
                    .record(eq(ORG), any(), eq("question.extend"), eq("question"), anyString(), eq("succeeded"), any());
        }

        @Test
        @DisplayName("an extension past seven days is refused")
        void extendLimits() {
            RunQuestion question = parkedQuestion();
            Instant asked = Instant.now().minus(6, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);
            setCreatedAt(question, asked);
            question.setExpiresAt(asked.plus(Duration.ofDays(6)).plus(Duration.ofHours(12)));
            Instant before = question.getExpiresAt();

            assertThatThrownBy(() -> service.extend(ORG, question.getId()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(e.getMessage()).contains("open as long as it can be");
                    });
            assertThat(question.getExpiresAt()).isEqualTo(before);
        }

        @Test
        @DisplayName("extending a question whose run stopped closes it instead")
        void extendOnStoppedRun() {
            RunQuestion question = parkedQuestion();
            runOf(question).setStatus("cancelled");

            assertThatThrownBy(() -> service.extend(ORG, question.getId()))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.QUESTION_CLOSED));
            assertThat(question.getStatus()).isEqualTo("cancelled");
        }

        @Test
        @DisplayName("someone who may not answer may not extend either")
        void extendByNonRequesterIs403() {
            RunQuestion question = parkedQuestion();
            actAs(UUID.randomUUID(), EMPLOYEE);

            assertThatThrownBy(() -> service.extend(ORG, question.getId()))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        }
    }

    // ---- What the model, chat and the views receive ---------------------------------------------

    @Nested
    @DisplayName("what is reported")
    class Reported {

        @Test
        @DisplayName("an answer reaches the model with the full question text and the chosen labels")
        void modelResultAnswered() {
            RunQuestion question = parkedQuestion();
            service.answer(ORG, question.getId(), pick("q1", "Managers"));

            JsonNode result = readTree(service.modelResult(question));

            assertThat(result.get("status").asText()).isEqualTo("answered");
            assertThat(result.at("/answers/0/header").asText()).isEqualTo("Audience");
            assertThat(result.at("/answers/0/question").asText()).isEqualTo("Who is this for?");
            assertThat(result.at("/answers/0/selected/0").asText()).isEqualTo("Managers");
            assertThat(result.at("/answers/0/other").isNull()).isTrue();
            assertThat(result.get("instruction").asText()).contains("do not ask the same thing again");
            assertThat(service.resultSummary(question)).isEqualTo("Answered: Managers");
        }

        @Test
        @DisplayName("a skipped question tells the model to use its judgement")
        void modelResultSkipped() {
            RunQuestion question = parkedQuestion();
            service.answer(ORG, question.getId(), input(List.of(), null, true));

            assertThat(readTree(service.modelResult(question)).get("status").asText())
                    .isEqualTo("skipped");
            assertThat(service.resultSummary(question)).startsWith("Skipped");
        }

        @Test
        @DisplayName("an expired question tells the model nobody answered")
        void modelResultExpired() {
            RunQuestion question = parkedQuestion();
            question.setStatus("expired");

            JsonNode result = readTree(service.modelResult(question));

            assertThat(result.get("status").asText()).isEqualTo("no_answer");
            assertThat(result.get("instruction").asText()).contains("say exactly what you still need");
            assertThat(service.resultSummary(question)).isEqualTo("No answer arrived before the question closed.");
        }

        @Test
        @DisplayName("the trace's ask step names the question and carries every option")
        void askStepDetail() {
            RunQuestion question = parkedQuestion(twoQuestions());

            var detail = service.askStepDetail(question);

            assertThat(detail)
                    .containsEntry("tool", AskPersonTool.NAME)
                    .containsEntry("toolCallId", question.getToolCallId())
                    .containsEntry("summary", "Asked 2 questions: Audience, Include");
            assertThat((List<?>) detail.get("questions")).hasSize(2);
        }

        @Test
        @DisplayName("views carry the run's live status, the goal's title and whether the viewer may answer")
        void viewsFillRunStatus() {
            RunQuestion open = parkedQuestion();
            RunQuestion orphan = parkedQuestion();
            allRuns.remove(runOf(orphan));

            List<QuestionService.QuestionView> views =
                    service.views(List.of(open, orphan), RequestContext.requireActor());

            assertThat(views)
                    .extracting(QuestionService.QuestionView::runStatus)
                    .containsExactly("waiting_input", "unknown");
            assertThat(views.getFirst().goalTitle()).isEqualTo("Onboard the new starter");
            assertThat(views.getFirst().canAnswer()).isTrue();
            assertThat(views.getFirst().extendable()).isTrue();
            assertThat(views.getFirst().questions()).hasSize(1);
        }

        @Test
        @DisplayName("a conversation's pending question older than the newest page is never dropped")
        void forConversationKeepsOldPending() {
            UUID conversation = UUID.randomUUID();
            RunQuestion old = parkedQuestion();
            setCreatedAt(old, Instant.now().minus(3, ChronoUnit.DAYS));
            List<RunQuestion> recent = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                RunQuestion answered = question(runFor(null, "completed"), "answered");
                setCreatedAt(answered, Instant.now().minusSeconds(100 - i));
                recent.add(answered);
            }
            when(rows.findPendingForConversation(ORG, conversation)).thenReturn(List.of(old));
            when(rows.findRecentForConversation(eq(ORG), eq(conversation), any(Pageable.class)))
                    .thenReturn(recent.reversed());

            List<RunQuestion> shown = service.forConversation(ORG, conversation, 100);

            assertThat(shown).hasSize(101);
            assertThat(shown.getFirst()).isSameAs(old);
            assertThat(shown.getLast()).isSameAs(recent.getLast());
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private void actAs(UUID person, Set<String> permissions) {
        RequestContext.setActor(Actor.user(person.toString(), ORG.toString(), "role", permissions, 0L));
    }

    private Optional<RunQuestion> findQuestion(Object id) {
        return allQuestions.stream()
                .filter(question -> question.getId().equals(id))
                .findFirst();
    }

    private Run runOf(RunQuestion question) {
        return allRuns.stream()
                .filter(run -> run.getId().equals(question.getRunId()))
                .findFirst()
                .orElseThrow();
    }

    /** A chat goal's run, parked on one question its requester can answer. */
    private RunQuestion parkedQuestion() {
        return parkedQuestion(oneQuestion());
    }

    private RunQuestion parkedQuestion(AskPersonTool.Ask ask) {
        Goal goal = work.goal("waiting");
        goal.setRequestedBy(REQUESTER);
        Task task = work.task(goal, 0, "waiting_input");
        Run run = runFor(task, "waiting_input");
        allRuns.add(run);
        RunQuestion question = question(run, "pending");
        question.setGoalId(goal.getId());
        question.setTaskId(task.getId());
        question.setRequestedBy(REQUESTER);
        question.setQuestionsJson(write(ask.questions()));
        return question;
    }

    private RunQuestion question(Run run, String status) {
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setRunId(run.getId());
        question.setAgentId(run.getAgentId());
        question.setToolCallId("t0_0_call_" + allQuestions.size());
        question.setQuestionsJson(write(oneQuestion().questions()));
        question.setStatus(status);
        Instant asked = Instant.now().minusSeconds(60);
        setCreatedAt(question, asked);
        question.setExpiresAt(asked.plus(WINDOW));
        allQuestions.add(question);
        return question;
    }

    private static AskPersonTool.Ask oneQuestion() {
        return new AskPersonTool.Ask(List.of(audience()));
    }

    private static AskPersonTool.Ask twoQuestions() {
        return new AskPersonTool.Ask(List.of(
                audience(),
                new AskPersonTool.Question(
                        "q2",
                        "Include",
                        "What should it include?",
                        true,
                        List.of(
                                new AskPersonTool.Option("A summary", "Three lines at the top.", false),
                                new AskPersonTool.Option("Next steps", "Who does what, and by when.", false),
                                new AskPersonTool.Option("A table", "The figures side by side.", false)))));
    }

    private static AskPersonTool.Question audience() {
        return new AskPersonTool.Question(
                "q1",
                "Audience",
                "Who is this for?",
                false,
                List.of(
                        new AskPersonTool.Option("The whole team", "Plain language, no assumed context.", true),
                        new AskPersonTool.Option("Managers", "Shorter, with the decisions first.", false),
                        new AskPersonTool.Option("A customer", "Formal, and nothing internal.", false)));
    }

    private static QuestionService.AnswerInput pick(String questionId, String label) {
        return input(List.of(new QuestionService.AnswerItem(questionId, List.of(label), null)), null, false);
    }

    private static QuestionService.AnswerInput input(
            List<QuestionService.AnswerItem> answers, String note, boolean skipped) {
        return new QuestionService.AnswerInput(answers, note, skipped, "chat");
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setName("General Employee");
        return agent;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The created time is set by auditing on insert; a test sets it the same way. */
    static void setCreatedAt(BaseEntity entity, Instant createdAt) {
        try {
            Field field = BaseEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
