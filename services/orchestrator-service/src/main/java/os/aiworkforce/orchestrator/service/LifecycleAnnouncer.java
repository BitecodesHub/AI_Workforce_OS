package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.repository.Tasks;

/**
 * Tells the {@link GoalLifecycleListener}s that a goal was stopped or retried, or that a run asked
 * its requester a question - always after the write that did it has committed.
 *
 * <p>After commit, because a listener reacts in its own tables (chat appends a message, under a
 * lock on the conversation) and anything that goes wrong there must not undo the stop, the retry
 * or the park that already happened. Each listener runs in its own new transaction, so one that
 * fails can neither roll back another's writes nor reach the caller.
 *
 * <p>{@link #afterCommit} is also the one helper every engine class uses for work that must wait
 * for a commit: submitting a retried goal's tasks, recording an audit entry, notifying listeners.
 */
@Service
public class LifecycleAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(LifecycleAnnouncer.class);

    private final Goals goals;
    private final Tasks tasks;
    private final RunQuestions questions;
    private final List<GoalLifecycleListener> listeners;
    private final TransactionTemplate listenerTransaction;

    @Autowired
    public LifecycleAnnouncer(
            Goals goals,
            Tasks tasks,
            RunQuestions questions,
            List<GoalLifecycleListener> listeners,
            PlatformTransactionManager transactionManager) {
        this.goals = goals;
        this.tasks = tasks;
        this.questions = questions;
        this.listeners = listeners == null ? List.of() : listeners;
        if (transactionManager == null) {
            this.listenerTransaction = null;
        } else {
            this.listenerTransaction = new TransactionTemplate(transactionManager);
            this.listenerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    /**
     * A run parked on a question. Listeners hear it with the question's task and goal, or with
     * both null for a run started directly on an agent.
     */
    public void questionAsked(UUID questionId) {
        if (listeners.isEmpty()) {
            return;
        }
        RunQuestion question = questions.findById(questionId).orElse(null);
        if (question == null) {
            log.warn("Question {} was not found, so nobody was told it was asked", questionId);
            return;
        }
        Task task = question.getTaskId() == null
                ? null
                : tasks.findById(question.getTaskId()).orElse(null);
        Goal goal = question.getGoalId() == null
                ? null
                : goals.findById(question.getGoalId()).orElse(null);
        dispatch(listener -> listener.onQuestionAsked(goal, task, question), "question " + questionId);
    }

    /** A goal was cancelled, for {@code reason}. */
    public void goalCancelled(UUID goalId, String reason) {
        if (listeners.isEmpty()) {
            return;
        }
        Goal goal = goals.findById(goalId).orElse(null);
        if (goal == null) {
            return;
        }
        dispatch(listener -> listener.onGoalCancelled(goal, reason), "goal " + goalId + " being cancelled");
    }

    /** A goal was tried again, starting from {@code fromTaskId}. */
    public void goalRetried(UUID goalId, UUID fromTaskId) {
        if (listeners.isEmpty()) {
            return;
        }
        Goal goal = goals.findById(goalId).orElse(null);
        if (goal == null) {
            return;
        }
        Task from = fromTaskId == null ? null : tasks.findById(fromTaskId).orElse(null);
        dispatch(listener -> listener.onGoalRetried(goal, from), "goal " + goalId + " being retried");
    }

    /** Each listener in its own transaction, and one failing never touches another or the caller. */
    private void dispatch(Consumer<GoalLifecycleListener> call, String what) {
        for (GoalLifecycleListener listener : listeners) {
            try {
                if (listenerTransaction == null) {
                    call.accept(listener);
                } else {
                    listenerTransaction.executeWithoutResult(status -> call.accept(listener));
                }
            } catch (RuntimeException e) {
                log.error("A goal lifecycle listener failed handling {}", what, e);
            }
        }
    }

    /**
     * Runs {@code action} after the current transaction commits, or at once when there is none.
     *
     * <p>A synchronisation registered inside a {@code REQUIRES_NEW} transaction runs when that
     * inner transaction commits. The action runs once the data it reports is visible to every
     * other connection, and a failure in it is logged rather than thrown: by then the work it
     * follows has committed, and reporting an error for it would tell the caller the opposite.
     */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(action);
            }
        });
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Work scheduled to run after a commit failed", e);
        }
    }
}
