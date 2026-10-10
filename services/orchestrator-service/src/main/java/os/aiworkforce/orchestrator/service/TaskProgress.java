// @find: task progress, goal progress, run finished, run parked, run resumed, start failed, close goal, task status, goal status sync, retry attempts, settle task
// @what: Keeps a task and its goal in step with the run doing the work, on finish, park, resume, failure, rejection and cancel.
// @flow: Called by AgentRunner and ApprovalService; announces via LifecycleAnnouncer
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Keeps a task, and the goal above it, in step with the run doing the work.
 *
 * <p>A run changes state in many places: the loop finishing, a run parking for an approval and
 * resuming after it, an approver rejecting, an approval expiring, a person cancelling, the reaper
 * giving up on a dead worker. Before this class existed only the first of those touched the task,
 * so a task that paused for an approval showed "waiting" forever, its goal showed "running"
 * forever, and its duration kept counting. Every one of those places now reports here, and the
 * rules for what a run's outcome means for its task live in one spot.
 *
 * <p>Depends only on repositories - the tasks and goals it changes, and the run's own trace and
 * approvals it reads to decide whether a failed attempt may be repeated - so any service can call
 * it without a cycle. Every method is safe to call twice: a task that has already moved on is
 * left alone.
 *
 * <p>When a task settles, a {@link TaskSettledEvent} goes out after the commit, so the workspace's
 * next task - the following step of a chain, a retry, or work held back by the workspace's cap -
 * starts at once rather than on the goal sweep's next tick.
 *
 * <p>Every run that ends is also counted here, whether or not it belongs to a task, because this is
 * the one place every ending reports to: {@value #RUNS_FINISHED} by outcome, {@value #RUN_DURATION},
 * and {@value #RUNS_ABANDONED} for the ones a dead worker left behind. The only tag is the
 * outcome, so the number of series never grows with the work.
 */
@Service
public class TaskProgress {

    private static final Logger log = LoggerFactory.getLogger(TaskProgress.class);

    /** Counts runs that ended, tagged {@code status}: completed, failed, abandoned or cancelled. */
    public static final String RUNS_FINISHED = "aiwos.runs.finished";

    /** How long runs took from start to end, tagged {@code status}. A run parked for a person counts that wait. */
    public static final String RUN_DURATION = "aiwos.run.duration";

    /** Counts runs the reaper ended because their worker stopped renewing the lease. */
    public static final String RUNS_ABANDONED = "aiwos.runs.abandoned";

    /** The outcomes a run can end with, which are the only values the {@code status} tag takes. */
    private static final Set<String> FINISHED_STATUSES = Set.of("completed", "failed", "abandoned", "cancelled");

    static final String EARLIER_TASK_UNFINISHED = "An earlier task in this goal did not finish.";
    private static final String DEFAULT_FAILURE = "The agent did not complete this task.";
    private static final String DEFAULT_ABANDONED = "The worker running this task stopped responding.";

    /**
     * The code a run records on its last {@code error} step when it ends having used up its step
     * limit, having been cut off by its output limit, or having repeated the same action. The
     * same instruction would meet the same wall, so none of them is ever retried automatically.
     */
    public static final String STEP_LIMIT = "step_limit";

    public static final String OUTPUT_LIMIT = "output_limit";
    public static final String LOOP_DETECTED = "loop_detected";

    /**
     * Failures a retry cannot fix: the three above, a workspace that has spent its model budget,
     * and a policy that refuses the work. A person has to change something first.
     */
    static final Set<String> NEVER_RETRIED = Set.of(
            STEP_LIMIT,
            OUTPUT_LIMIT,
            LOOP_DETECTED,
            ErrorCode.BUDGET_EXCEEDED.wire(),
            ErrorCode.POLICY_VIOLATION.wire());

    /** What a task says when it was not retried because its failed run had already acted; {@code %s} is the tools. */
    static final String CHANGED_BEFORE_STOPPING =
            "This step had already made changes (%s) before it stopped, so it was not retried automatically."
                    + " Check the trace, then use Try again if it should continue.";

    /** How many tool names the "already made changes" reason lists before saying "and more". */
    private static final int LISTED_TOOLS = 5;

    private final Tasks tasks;
    private final Goals goals;
    private final List<GoalLifecycleListener> listeners;
    /** The run's own trace, read to see what a failed attempt already did. Null in unit tests that do not look. */
    private final RunSteps steps;
    /** The run's approvals; an approved one means something was allowed to go out. Null as above. */
    private final Approvals approvals;
    /** Each listener's own transaction, after the write commits. Null in unit tests. */
    private TransactionTemplate listenerTransaction;
    /** Where a settled task is announced. Null in unit tests that do not listen. */
    private ApplicationEventPublisher events;
    /** Where finished runs are counted. Null in unit tests that do not look. */
    private MeterRegistry meters;

    public TaskProgress(Tasks tasks, Goals goals, List<GoalLifecycleListener> listeners) {
        this(tasks, goals, listeners, null, null);
    }

    @Autowired
    public TaskProgress(
            Tasks tasks,
            Goals goals,
            List<GoalLifecycleListener> listeners,
            RunSteps steps,
            Approvals approvals) {
        this.tasks = tasks;
        this.goals = goals;
        this.listeners = listeners == null ? List.of() : listeners;
        this.steps = steps;
        this.approvals = approvals;
    }

    @Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.listenerTransaction = new TransactionTemplate(transactionManager);
        this.listenerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Registers every counter at zero as soon as there is a registry. A counter created by its first
     * increment has no earlier sample, so Prometheus's {@code increase()} would count that first
     * abandoned run as nothing and an alert on it would stay quiet exactly once.
     */
    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meters) {
        this.meters = meters;
        if (meters != null) {
            FINISHED_STATUSES.forEach(status -> meters.counter(RUNS_FINISHED, "status", status));
            meters.counter(RUNS_ABANDONED);
        }
    }

    // @find: run finished, task done or failed
    /**
     * Applies a run's terminal outcome to its task.
     *
     * <p>Only a task that is still running or waiting for a person changes. A task that has
     * finished, or that was already put back to wait for another attempt, belongs to a later
     * decision, and a late report about an earlier run must not undo it.
     *
     * @param run the run that ended; a run started directly rather than for a task is ignored
     * @param status {@code completed}, {@code failed}, {@code abandoned} or {@code cancelled}
     * @param answer the agent's final text, for a completed run; for a failed one, whatever the
     *     agent had written when it stopped - kept on the task, where the screens show it as an
     *     incomplete answer
     * @param reason why the run did not complete, shown on the task
     */
    @Transactional
    public void onRunFinished(Run run, String status, String answer, String reason) {
        recordFinished(run, status);
        Task task = activeTaskOf(run);
        if (task == null) {
            return;
        }
        Instant now = Instant.now();
        switch (status) {
            case "completed" -> {
                task.setStatus("completed");
                task.setResult(answer);
                task.setFailureReason(null);
                task.setCompletedAt(now);
            }
            case "failed" -> applyFailure(task, run, reason == null ? DEFAULT_FAILURE : reason, answer, now);
            case "abandoned" -> {
                // Never retried. The dead worker may have completed a side effect before it
                // stopped, and repeating the task automatically is how one email becomes two.
                task.setStatus("failed");
                task.setFailureReason(reason == null ? DEFAULT_ABANDONED : reason);
                task.setCompletedAt(now);
            }
            case "cancelled" -> {
                task.setStatus("cancelled");
                task.setFailureReason(reason);
                task.setCompletedAt(now);
            }
            default -> {
                log.warn(
                        "Run {} reported {}, which is not a finished state; task {} left as it was",
                        run.getId(),
                        status,
                        task.getId());
                return;
            }
        }
        tasks.save(task);
        notifyTaskFinished(task);
        afterTaskChanged(task);
        announceSettled(task);
    }

    // @find: run parked waiting for approval or answer
    /**
     * The run stopped to wait for a person, so its task is waiting too, for the same thing: an
     * approval ({@code waiting_approval}) or an answer ({@code waiting_input}).
     */
    @Transactional
    public void onRunParked(Run run) {
        String parked = run == null ? null : run.getStatus();
        if (!"waiting_approval".equals(parked) && !"waiting_input".equals(parked)) {
            return;
        }
        Task task = activeTaskOf(run);
        if (task == null || parked.equals(task.getStatus())) {
            return;
        }
        task.setStatus(parked);
        tasks.save(task);
    }

    // @find: run resumed
    /** The approval was granted, or the question answered, and the run picked up again. */
    @Transactional
    public void onRunResumed(Run run) {
        Task task = activeTaskOf(run);
        if (task == null || "running".equals(task.getStatus())) {
            return;
        }
        task.setStatus("running");
        tasks.save(task);
    }

    // @find: task start failed
    /**
     * A task whose run could not even begin: the agent is paused, has been removed, or has no
     * configuration. The attempt has already been counted, so the retry rule still bounds it.
     */
    @Transactional
    public void onStartFailed(Task task, String reason) {
        applyFailure(task, null, reason == null ? DEFAULT_FAILURE : reason, null, Instant.now());
        tasks.save(task);
        notifyTaskFinished(task);
        afterTaskChanged(task);
        announceSettled(task);
    }

    // @find: close goal when all tasks settled
    /**
     * Closes a goal once every task under it has finished.
     *
     * <p>Failed outranks cancelled, which outranks completed: a goal with one failed task did not
     * achieve what was asked, however the rest went. A goal that has already finished keeps its
     * status, so a cancelled goal is never reopened as failed by a run ending late.
     */
    @Transactional
    public void closeGoalIfFinished(UUID goalId) {
        Goal goal = goals.findById(goalId).orElse(null);
        if (goal == null || goal.isFinished()) {
            return;
        }
        List<Task> all = tasks.findByGoalIdOrderByPosition(goalId);
        if (all.isEmpty() || !all.stream().allMatch(Task::isTerminal)) {
            return;
        }
        boolean anyFailed = all.stream().anyMatch(task -> "failed".equals(task.getStatus()));
        boolean anyCancelled = all.stream().anyMatch(task -> "cancelled".equals(task.getStatus()));
        goal.setStatus(anyFailed ? "failed" : anyCancelled ? "cancelled" : "completed");
        goal.setCompletedAt(Instant.now());
        goals.save(goal);
        log.info("Goal {} finished as {}", goalId, goal.getStatus());
        notifyGoalFinished(goal);
    }

    // ---- Metrics ------------------------------------------------------------------------------

    /**
     * Counts a run that ended, once the write that ended it has committed: a finish that rolls
     * back did not happen, and must not appear in the figures.
     */
    private void recordFinished(Run run, String status) {
        if (meters == null || run == null || !FINISHED_STATUSES.contains(status)) {
            return;
        }
        Duration took = durationOf(run);
        LifecycleAnnouncer.afterCommit(() -> {
            meters.counter(RUNS_FINISHED, "status", status).increment();
            if (took != null) {
                Timer.builder(RUN_DURATION)
                        .description("How long runs took from start to finish")
                        .tag("status", status)
                        .serviceLevelObjectives(
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(15),
                                Duration.ofSeconds(30),
                                Duration.ofMinutes(1),
                                Duration.ofMinutes(2),
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(15),
                                Duration.ofHours(1))
                        .register(meters)
                        .record(took);
            }
            if ("abandoned".equals(status)) {
                meters.counter(RUNS_ABANDONED).increment();
            }
        });
    }

    /** From start to end, or null when the run does not say when it started. */
    private static Duration durationOf(Run run) {
        if (run.getStartedAt() == null) {
            return null;
        }
        Instant end = run.getCompletedAt() == null ? Instant.now() : run.getCompletedAt();
        Duration took = Duration.between(run.getStartedAt(), end);
        return took.isNegative() ? Duration.ZERO : took;
    }

    // ---- Lifecycle listeners ------------------------------------------------------------------

    /**
     * Every listener is called once the task's change commits, and one throwing must not stop the
     * task from being recorded.
     *
     * <p>After commit rather than inline: a listener that joined this write (chat's, which takes a
     * lock on its conversation) could mark the run's finish rollback-only by failing, even though
     * the failure is caught here, and a task that completed would then be reaped and reported as
     * failed. The status is captured now, because the task can change again before the listener
     * runs.
     */
    private void notifyTaskFinished(Task task) {
        if (listeners.isEmpty()) {
            return;
        }
        Goal goal = goals.findById(task.getGoalId()).orElse(null);
        if (goal == null) {
            return;
        }
        String status = task.getStatus();
        LifecycleAnnouncer.afterCommit(() -> dispatch(
                listener -> listener.onTaskFinished(goal, task, status),
                "task " + task.getId() + " reaching " + status));
    }

    private void notifyGoalFinished(Goal goal) {
        if (listeners.isEmpty()) {
            return;
        }
        String status = goal.getStatus();
        LifecycleAnnouncer.afterCommit(() -> dispatch(
                listener -> listener.onGoalFinished(goal), "goal " + goal.getId() + " finishing as " + status));
    }

    /**
     * Tells the executor, once this change commits, that the workspace may have work ready to
     * start: the next step after a completed one, the same task back for another attempt, or a
     * task that was waiting for a running one to free its place under the workspace's cap.
     *
     * <p>Published through {@link LifecycleAnnouncer#afterCommit}, so the dispatch reads the task
     * as it now stands, and a listener that fails cannot undo the change it follows.
     */
    private void announceSettled(Task task) {
        if (events == null || task.getOrgId() == null) {
            return;
        }
        UUID orgId = task.getOrgId();
        LifecycleAnnouncer.afterCommit(() -> events.publishEvent(new TaskSettledEvent(orgId)));
    }

    /** Each listener in its own REQUIRES_NEW transaction, and one failing never touches another. */
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

    // ---- Rules ------------------------------------------------------------------------------

    /**
     * Decides whether a failed task gets another attempt, and records why not when it does not.
     *
     * <p>Another attempt starts from the instruction again, with an empty trace, so it would send
     * what the first one already sent. While attempts remain, it is therefore refused when:
     *
     * <ul>
     *   <li>the failure is one a retry cannot fix - the run says so on its last error step (see
     *       {@link #NEVER_RETRIED}) - and the task keeps that failure's own reason;
     *   <li>the failed run had already changed something outside the platform, or had an approval
     *       granted, and the task says which tools, so a person checks before deciding.
     * </ul>
     *
     * A run that made no changes is put back to wait, which is what a model that answered with
     * nothing, or a provider that was briefly down, needs. A person's Try again is never
     * refused: that is the decision this rule leaves to them.
     *
     * @param run the run that failed, or null when none could start
     * @param answer what the agent had written when it stopped; kept on a task that will not be
     *     retried, never on one that will, so a later attempt starts from nothing
     */
    private void applyFailure(Task task, Run run, String reason, String answer, Instant now) {
        String recorded = reason;
        boolean retry = task.canRetry();
        if (retry && run != null) {
            Inspection inspection = inspect(run);
            if (inspection.neverRetried()) {
                retry = false;
            } else if (!inspection.changed().isEmpty()) {
                retry = false;
                recorded = CHANGED_BEFORE_STOPPING.formatted(listed(inspection.changed()));
            }
        }
        task.setFailureReason(recorded);
        if (retry) {
            // Back to waiting so the goal sweep picks it up again, with the attempt already
            // counted so it cannot loop forever.
            task.setStatus("pending");
            return;
        }
        task.setStatus("failed");
        task.setCompletedAt(now);
        if (answer != null && !answer.isBlank()) {
            task.setResult(answer);
        }
    }

    /** What a failed run's trace and approvals say about repeating its work. */
    private record Inspection(boolean neverRetried, Set<String> changed) {}

    private Inspection inspect(Run run) {
        List<RunStep> trace = steps == null ? List.of() : steps.findByRunIdOrderByPosition(run.getId());
        String code = null;
        Set<String> changed = new LinkedHashSet<>();
        Map<String, String> answers = new HashMap<>();
        for (RunStep step : trace) {
            if ("error".equals(step.getKind())) {
                code = text(step.getDetail(), "code");
            } else if (isChange(step)) {
                changed.add(text(step.getDetail(), "tool") == null ? "a tool" : text(step.getDetail(), "tool"));
            }
            if ("tool_call".equals(step.getKind()) && text(step.getDetail(), "toolCallId") != null) {
                answers.put(text(step.getDetail(), "toolCallId"), text(step.getDetail(), "status"));
            }
        }
        if (approvals != null) {
            for (Approval approval : approvals.findByRunIdAndStatus(run.getId(), "approved")) {
                if (!neverWentOut(approval, answers)) {
                    changed.add(approval.getTool() == null ? "an approved action" : approval.getTool());
                }
            }
        }
        return new Inspection(code != null && NEVER_RETRIED.contains(code), changed);
    }

    /**
     * Whether an approved action is known not to have gone out: the call it was granted for was
     * answered as failed or blocked, for example because the connection store could not be reached.
     * An approval whose call was never answered, or whose answer is unclear, still counts.
     */
    private static boolean neverWentOut(Approval approval, Map<String, String> answers) {
        String answer = approval.getToolCallId() == null ? null : answers.get(approval.getToolCallId());
        return "FAILED".equals(answer) || "BLOCKED".equals(answer);
    }

    /**
     * Whether a trace step is a call that changed something outside the platform, or may have: a
     * tool call that ended {@code SUCCEEDED} or {@code INDETERMINATE} and was not a plain read.
     * A step recorded before the tool's side effect was kept says nothing either way, so it is
     * not counted; the approval check covers the calls that matter most.
     */
    static boolean isChange(RunStep step) {
        if (step == null || !"tool_call".equals(step.getKind())) {
            return false;
        }
        String status = text(step.getDetail(), "status");
        if (!"SUCCEEDED".equals(status) && !"INDETERMINATE".equals(status)) {
            return false;
        }
        String effect = text(step.getDetail(), "sideEffect");
        return effect != null && !"READ".equalsIgnoreCase(effect);
    }

    private static String text(Map<String, Object> detail, String key) {
        return detail != null && detail.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static String listed(Set<String> toolNames) {
        List<String> names = new ArrayList<>(toolNames);
        if (names.size() <= LISTED_TOOLS) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, LISTED_TOOLS)) + " and " + (names.size() - LISTED_TOOLS) + " more";
    }

    private void afterTaskChanged(Task task) {
        if ("failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())) {
            skipLaterTasks(task);
        }
        closeGoalIfFinished(task.getGoalId());
    }

    /**
     * Tasks run in order, so a task after one that failed or was cancelled can never start.
     * Leaving it "waiting to start" would keep the goal open with nothing left to happen.
     */
    private void skipLaterTasks(Task ended) {
        Instant now = Instant.now();
        for (Task later : tasks.findByGoalIdOrderByPosition(ended.getGoalId())) {
            boolean waiting = "pending".equals(later.getStatus()) || "ready".equals(later.getStatus());
            if (later.getPosition() > ended.getPosition() && waiting) {
                later.setStatus("skipped");
                later.setFailureReason(EARLIER_TASK_UNFINISHED);
                later.setCompletedAt(now);
                tasks.save(later);
            }
        }
    }

    /** The run's task, when it has one that is still in progress. */
    private Task activeTaskOf(Run run) {
        if (run == null || run.getTaskId() == null) {
            return null;
        }
        return tasks.findById(run.getTaskId())
                .filter(task -> "running".equals(task.getStatus())
                        || "waiting_approval".equals(task.getStatus())
                        || "waiting_input".equals(task.getStatus()))
                .orElse(null);
    }
}
