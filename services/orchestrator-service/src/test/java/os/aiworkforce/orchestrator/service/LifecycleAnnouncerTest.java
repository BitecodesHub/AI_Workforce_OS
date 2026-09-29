package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.repository.RunQuestions;

/** Listeners hear about stops, retries and questions only after the write commits, each on its own. */
class LifecycleAnnouncerTest {

    private WorkFixture work;
    private RunQuestions questions;
    private GoalLifecycleListener first;
    private GoalLifecycleListener second;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        questions = mock(RunQuestions.class);
        first = mock(GoalLifecycleListener.class);
        second = mock(GoalLifecycleListener.class);
    }

    @AfterEach
    void clearSynchronisation() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction, nothing is called until the transaction commits")
    void listenersRunOnlyAfterCommit() {
        LifecycleAnnouncer announcer = announcer(null);
        Goal goal = work.goal("cancelled");
        TransactionSynchronizationManager.initSynchronization();

        LifecycleAnnouncer.afterCommit(() -> announcer.goalCancelled(goal.getId(), "Stopped from the chat."));

        verify(first, never()).onGoalCancelled(any(), any());
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);
        registered.forEach(TransactionSynchronization::afterCommit);

        verify(first).onGoalCancelled(goal, "Stopped from the chat.");
        verify(second).onGoalCancelled(goal, "Stopped from the chat.");
    }

    @Test
    @DisplayName("with no transaction, the work runs at once")
    void listenersRunAtOnceWithoutATransaction() {
        LifecycleAnnouncer announcer = announcer(null);
        Goal goal = work.goal("running");
        var task = work.task(goal, 0, "pending");

        LifecycleAnnouncer.afterCommit(() -> announcer.goalRetried(goal.getId(), task.getId()));

        verify(first).onGoalRetried(goal, task);
        verify(second).onGoalRetried(goal, task);
    }

    @Test
    @DisplayName("one listener failing neither stops the others nor reaches the caller")
    void oneListenerFailingDoesNotStopTheOthers() {
        LifecycleAnnouncer announcer = announcer(null);
        Goal goal = work.goal("cancelled");
        doThrow(new IllegalStateException("chat is down")).when(first).onGoalCancelled(any(), any());

        assertThatCode(() -> announcer.goalCancelled(goal.getId(), "A person cancelled this goal."))
                .doesNotThrowAnyException();

        verify(second).onGoalCancelled(goal, "A person cancelled this goal.");
    }

    @Test
    @DisplayName("an after-commit action that throws is logged, not thrown")
    void afterCommitSwallowsFailures() {
        assertThatCode(() -> LifecycleAnnouncer.afterCommit(() -> {
                    throw new IllegalStateException("audit is down");
                }))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a question from a run started directly on an agent reaches listeners with no goal or task")
    void questionAskedForADirectRunPassesNullGoalAndTask() {
        LifecycleAnnouncer announcer = announcer(null);
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setRunId(UUID.randomUUID());
        when(questions.findById(question.getId())).thenReturn(Optional.of(question));

        announcer.questionAsked(question.getId());

        verify(first).onQuestionAsked(isNull(), isNull(), eq(question));
        verify(second).onQuestionAsked(isNull(), isNull(), eq(question));
    }

    @Test
    @DisplayName("a question for a goal's task reaches listeners with that goal and task")
    void questionAskedCarriesGoalAndTask() {
        LifecycleAnnouncer announcer = announcer(null);
        Goal goal = work.goal("waiting");
        var task = work.task(goal, 0, "waiting_input");
        RunQuestion question = new RunQuestion();
        question.setId(UUID.randomUUID());
        question.setOrgId(ORG);
        question.setGoalId(goal.getId());
        question.setTaskId(task.getId());
        when(questions.findById(question.getId())).thenReturn(Optional.of(question));

        announcer.questionAsked(question.getId());

        verify(first).onQuestionAsked(goal, task, question);
    }

    @Test
    @DisplayName("each listener gets a new transaction of its own")
    void eachListenerGetsItsOwnTransaction() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        LifecycleAnnouncer announcer = announcer(transactions);
        Goal goal = work.goal("cancelled");

        announcer.goalCancelled(goal.getId(), "A person cancelled this goal.");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions, times(2)).commit(any());
        verify(first).onGoalCancelled(goal, "A person cancelled this goal.");
        verify(second).onGoalCancelled(goal, "A person cancelled this goal.");
    }

    @Test
    @DisplayName("a goal that no longer exists is announced to nobody")
    void missingGoalIsSkipped() {
        LifecycleAnnouncer announcer = announcer(null);

        announcer.goalCancelled(UUID.randomUUID(), "A person cancelled this goal.");

        verify(first, never()).onGoalCancelled(any(), any());
    }

    private LifecycleAnnouncer announcer(PlatformTransactionManager transactions) {
        return new LifecycleAnnouncer(work.goals, work.tasks, questions, List.of(first, second), transactions);
    }
}
