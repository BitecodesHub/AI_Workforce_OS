package os.aiworkforce.orchestrator.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sweeps that keep the platform's state honest between requests.
 *
 * <p>Each of these exists because something otherwise stays wrong silently: a run whose worker
 * died shows as running forever, an approval or a question nobody answered sits pending forever,
 * quietly holding a task open, a run that was approved or answered while the service restarted
 * stays parked, and a goal whose next task nobody starts never finishes. None of them produces an
 * error anybody would see.
 *
 * <p>Batch sizes are small on purpose. A sweep that tries to fix everything at once holds a
 * transaction long enough to matter, and if it fails it fixes nothing; a small batch that runs
 * often converges just as fast and fails cheaply.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "aiwos.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);
    private static final int BATCH = 50;

    private final AgentRunner runner;
    private final ApprovalService approvals;
    private final GoalService goals;
    private final QuestionService questions;
    private final RunExecutor executor;
    /** Set while a goal sweep is running, so a tick that arrives meanwhile is skipped. */
    private final AtomicBoolean advancing = new AtomicBoolean();

    public MaintenanceScheduler(
            AgentRunner runner,
            ApprovalService approvals,
            GoalService goals,
            QuestionService questions,
            RunExecutor executor) {
        this.runner = runner;
        this.approvals = approvals;
        this.goals = goals;
        this.questions = questions;
        this.executor = executor;
    }

    /** Picks up runs whose worker stopped renewing its lease. */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.reap-interval:PT60S}")
    public void reapAbandonedRuns() {
        try {
            int reaped = runner.reapAbandoned(BATCH);
            if (reaped > 0) {
                log.warn("Marked {} run(s) abandoned after their lease expired", reaped);
            }
        } catch (RuntimeException e) {
            // A failing sweep must not stop the scheduler from running the next one.
            log.error("The abandoned-run sweep failed", e);
        }
    }

    /** Closes approvals that passed their deadline, rejecting by default. */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.expiry-interval:PT120S}")
    public void expireApprovals() {
        try {
            approvals.expireOverdue(BATCH);
        } catch (RuntimeException e) {
            log.error("The approval-expiry sweep failed", e);
        }
    }

    /**
     * Closes questions nobody answered in time, and resumes each run so it finishes with what it
     * has. Then closes pending questions whose run stopped by a path that could not withdraw them.
     *
     * <p>One question per transaction: an answer committing to one of them at the same moment can
     * only lose that one, never the rest of the batch.
     */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.question-expiry-interval:PT120S}")
    public void expireQuestions() {
        try {
            for (UUID id : questions.overdueIds(BATCH)) {
                try {
                    questions.expireOne(id).ifPresent(ref -> executor.submitResume(ref.orgId(), ref.runId()));
                } catch (RuntimeException e) {
                    log.warn("Question {} could not be expired: {}", id, e.getMessage());
                }
            }
            for (UUID id : questions.strandedPendingIds(BATCH)) {
                try {
                    questions.closeStranded(id);
                } catch (RuntimeException e) {
                    log.warn("Question {} could not be closed: {}", id, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("The question-expiry sweep failed", e);
        }
    }

    /**
     * Resumes runs whose question was answered or expired, or whose approval was granted, but
     * which are still parked - most often because the service restarted before the resume ran.
     *
     * <p>A minute's grace keeps this from racing the resume the answer or decision submitted
     * itself; if both arrive anyway, the claim in the database lets only one drive the run.
     */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.resume-interval:PT60S}")
    public void resumeDecidedRuns() {
        Instant cutoff = Instant.now().minusSeconds(60);
        try {
            for (RunRef ref : questions.awaitingResume(cutoff, BATCH)) {
                executor.submitResume(ref.orgId(), ref.runId());
            }
            for (RunRef ref : approvals.approvedAwaitingResume(cutoff, BATCH)) {
                executor.submitResume(ref.orgId(), ref.runId());
            }
        } catch (RuntimeException e) {
            log.error("The resume sweep failed", e);
        }
    }

    /**
     * Moves goals forward.
     *
     * <p>First repairs any task still shown as in progress although its run has ended. Then starts
     * at most one waiting task per workspace per tick: the next task after one that finished, a
     * task put back for another attempt, or anything beyond the few a new goal runs straight away.
     *
     * <p>Starting a task runs an agent, and that can take minutes, so this sweep is scheduled at a
     * fixed rate rather than with a fixed delay. The platform turns virtual threads on, and with
     * them Spring runs every fixed-delay task on one shared scheduler thread: an agent run here
     * would hold up the reaper and the approval-expiry sweep until it finished. A fixed-rate tick
     * runs on a thread of its own, and a tick that arrives while the previous sweep is still
     * running is skipped rather than started alongside it.
     */
    @Scheduled(
            initialDelayString = "${aiwos.scheduling.goal-initial-delay:PT30S}",
            fixedRateString = "${aiwos.scheduling.goal-interval:PT15S}")
    public void advanceGoals() {
        if (!advancing.compareAndSet(false, true)) {
            return;
        }
        try {
            sweepGoals();
        } finally {
            advancing.set(false);
        }
    }

    private void sweepGoals() {
        try {
            int repaired = goals.reconcileStrandedTasks(BATCH);
            if (repaired > 0) {
                log.info("Repaired {} task(s) whose run had already finished", repaired);
            }
        } catch (RuntimeException e) {
            log.error("The stranded-task sweep failed", e);
        }

        List<UUID> workspaces;
        try {
            workspaces = goals.workspacesWithWaitingTasks();
        } catch (RuntimeException e) {
            log.error("The goal sweep could not list workspaces with waiting tasks", e);
            return;
        }
        for (UUID orgId : workspaces) {
            try {
                goals.runNextTask(orgId);
            } catch (RuntimeException e) {
                // One workspace's failure must not stop the others from moving.
                log.error("The goal sweep could not start a task in workspace {}", orgId, e);
            }
        }
    }
}
