package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.repository.ProcessedEvents;

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
 *
 * <p>Each pass is timed ({@value OperationsMetrics#SWEEP_DURATION}) and runs with its name in the
 * logging context, as {@code sweep}, together with the workspace or run it is acting on, so the
 * lines a sweep writes can be told from a request's and one workspace's from another's.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "aiwos.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);
    private static final int BATCH = 50;
    /**
     * How long a processed event id is remembered. It only has to outlast the longest a broker
     * could redeliver the same event; a week is far beyond that, and the table stays small.
     */
    static final Duration PROCESSED_EVENT_RETENTION = Duration.ofDays(7);

    private final AgentRunner runner;
    private final ApprovalService approvals;
    private final GoalService goals;
    private final QuestionService questions;
    private final RunExecutor executor;
    /** Null only for a scheduler built without it, which then has no event records to purge. */
    private final ProcessedEvents processedEvents;
    /** Set while a goal sweep is listing and repairing, so a tick that arrives meanwhile is skipped. */
    private final AtomicBoolean advancing = new AtomicBoolean();

    private OperationsMetrics metrics;
    private RetentionService retention;

    @Autowired
    public MaintenanceScheduler(
            AgentRunner runner,
            ApprovalService approvals,
            GoalService goals,
            QuestionService questions,
            RunExecutor executor,
            ProcessedEvents processedEvents) {
        this.runner = runner;
        this.approvals = approvals;
        this.goals = goals;
        this.questions = questions;
        this.executor = executor;
        this.processedEvents = processedEvents;
    }

    /** Where passes are timed and the pending-approvals figure is kept. Null in unit tests that do not look. */
    @Autowired(required = false)
    void setOperationsMetrics(OperationsMetrics metrics) {
        this.metrics = metrics;
    }

    /** Who purges old run detail. Null in a scheduler built without it, which then has nothing to purge. */
    @Autowired(required = false)
    void setRetention(RetentionService retention) {
        this.retention = retention;
    }

    /**
     * Picks up runs whose worker stopped renewing its lease: a worker that crashed or was
     * restarted. A live worker's heartbeat keeps renewing however long its model or tool calls
     * take, so only a run nothing is driving any more is ever marked abandoned.
     */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.reap-interval:PT60S}")
    public void reapAbandonedRuns() {
        sweep("reap-abandoned", () -> {
            try {
                int reaped = runner.reapAbandoned(BATCH);
                if (reaped > 0) {
                    log.warn("Marked {} run(s) abandoned after their lease expired", reaped);
                }
            } catch (RuntimeException e) {
                // A failing sweep must not stop the scheduler from running the next one.
                log.error("The abandoned-run sweep failed", e);
            }
        });
    }

    /** Closes approvals that passed their deadline, rejecting by default. */
    @Scheduled(fixedDelayString = "${aiwos.scheduling.expiry-interval:PT120S}")
    public void expireApprovals() {
        sweep("expire-approvals", () -> {
            try {
                approvals.expireOverdue(BATCH);
            } catch (RuntimeException e) {
                log.error("The approval-expiry sweep failed", e);
            }
        });
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
        sweep("expire-questions", () -> {
            try {
                for (UUID id : questions.overdueIds(BATCH)) {
                    try {
                        questions.expireOne(id).ifPresent(ref -> {
                            try (RunLogContext ignored = RunLogContext.run(ref.orgId(), null, ref.runId(), null)) {
                                executor.submitResume(ref.orgId(), ref.runId());
                            }
                        });
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
        });
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
        sweep("resume-decided", () -> {
            Instant cutoff = Instant.now().minusSeconds(60);
            try {
                for (RunRef ref : questions.awaitingResume(cutoff, BATCH)) {
                    resume(ref);
                }
                for (RunRef ref : approvals.approvedAwaitingResume(cutoff, BATCH)) {
                    resume(ref);
                }
            } catch (RuntimeException e) {
                log.error("The resume sweep failed", e);
            }
        });
    }

    private void resume(RunRef ref) {
        try (RunLogContext ignored = RunLogContext.run(ref.orgId(), null, ref.runId(), null)) {
            executor.submitResume(ref.orgId(), ref.runId());
        }
    }

    /**
     * Forgets the ids of events handled more than a week ago, once a day, so the idempotency table
     * does not grow for ever. An event old enough to be redelivered has long since been handled.
     */
    @Scheduled(cron = "${aiwos.scheduling.processed-events-purge-cron:0 30 3 * * *}")
    public void purgeProcessedEvents() {
        if (processedEvents == null) {
            return;
        }
        sweep("purge-events", () -> {
            try {
                int removed = processedEvents.deleteProcessedBefore(Instant.now().minus(PROCESSED_EVENT_RETENTION));
                if (removed > 0) {
                    log.info(
                            "Forgot {} processed event id(s) older than {} days",
                            removed,
                            PROCESSED_EVENT_RETENTION.toDays());
                }
            } catch (RuntimeException e) {
                log.error("The processed-event purge failed", e);
            }
        });
    }

    /**
     * Removes the text of old run steps and old usage rows, once a night.
     *
     * <p>How long each workspace keeps run detail is its own setting ({@link
     * RetentionService#RUN_DETAIL_DAYS}); see {@link RetentionService} for what goes and what is
     * kept. Every instance runs this, and the service lets one through at a time.
     */
    @Scheduled(cron = "${aiwos.scheduling.retention-purge-cron:0 15 3 * * *}")
    public void purgeOldRunDetail() {
        if (retention == null) {
            return;
        }
        sweep("retention", () -> {
            try {
                retention.purge();
            } catch (RuntimeException e) {
                log.error("The retention purge failed", e);
            }
        });
    }

    /**
     * Refreshes the figures that are read from the database rather than counted as things happen,
     * so a scrape of the metrics endpoint never waits on a query.
     */
    @Scheduled(
            initialDelayString = "${aiwos.scheduling.gauge-initial-delay:PT10S}",
            fixedDelayString = "${aiwos.scheduling.gauge-interval:PT60S}")
    public void refreshGauges() {
        if (metrics == null) {
            return;
        }
        sweep("gauges", metrics::refreshPendingApprovals);
    }

    /**
     * Moves goals forward.
     *
     * <p>First repairs any task still shown as in progress although its run has ended. Then hands
     * every workspace with a task waiting to {@link RunExecutor}, which starts what is ready on
     * threads of its own. Most work no longer waits for this: a new goal, and the next task after
     * one that settles, are dispatched the moment they commit. The sweep is the backstop - a task
     * held back by the workspace's cap, a dispatch lost to a restart, a paused agent resumed.
     *
     * <p>The sweep only lists, repairs and dispatches, so it never waits for an agent: one
     * workspace's long run cannot hold up any other workspace's next step. It stays a fixed-rate
     * tick on a thread of its own, and a tick that arrives while the previous one is still
     * listing is skipped rather than started alongside it.
     */
    @Scheduled(
            initialDelayString = "${aiwos.scheduling.goal-initial-delay:PT30S}",
            fixedRateString = "${aiwos.scheduling.goal-interval:PT15S}")
    public void advanceGoals() {
        if (!advancing.compareAndSet(false, true)) {
            return;
        }
        try {
            sweep("advance-goals", this::sweepGoals);
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
            try (RunLogContext ignored = RunLogContext.workspace(orgId)) {
                try {
                    executor.submitNextTasks(orgId);
                } catch (RuntimeException e) {
                    // One workspace's failure must not stop the others from moving.
                    log.error("The goal sweep could not dispatch tasks in workspace {}", orgId, e);
                }
            }
        }
    }

    /** Runs one pass with its name in the logging context, timed when there is somewhere to record it. */
    private void sweep(String name, Runnable body) {
        try (RunLogContext ignored = RunLogContext.sweep(name)) {
            if (metrics == null) {
                body.run();
            } else {
                metrics.timed(name, body);
            }
        }
    }
}
