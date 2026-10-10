// @find: insights, analytics, what the workforce did, cost, value, hours saved, hourly rate, goals tasks runs figures, approvals, questions, failure reasons, spend per day, per agent, InsightsService, window days
// @what: Computes grouped SQL figures for what the workforce did, cost and was worth over a window of days.
// @flow: Called by InsightsController; reads orchestrator tables and runtime settings.
package os.aiworkforce.orchestrator.board;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigStore;

/**
 * What the workforce did, what it cost and what it was worth, over a window of days, read from the
 * orchestrator's own tables with grouped SQL.
 *
 * <p>Plain SQL through {@link JdbcTemplate} rather than the repositories: every figure here is an
 * aggregate over several tables, and each query names the workspace first so none can read past
 * the caller's own. Days are UTC days, spelled {@code YYYY-MM-DD}, cut in the database in an
 * explicit zone so a day's figures never move between reports.
 *
 * <p>The rules the figures keep, so none of them can mislead:
 *
 * <ul>
 *   <li>A rate counts finished work only. A run still going is neither a success nor a failure,
 *       and a task a person stopped is neither.
 *   <li>An agent with fewer than {@value #MIN_FINISHED_RUNS} finished runs has no success rate at
 *       all: three runs of three is not "100%".
 *   <li>A run that used a real model and cost nothing is <em>unpriced</em>, never $0: the catalogue
 *       has no price for its model. A run answered only by the offline sandbox is free and is
 *       counted as such. Cost per goal and per run leave unpriced work out and say how much.
 *   <li>Hours returned and what they are worth are estimates from inputs an administrator typed
 *       (minutes a person would spend on one task, per agent, and a loaded hourly rate), and are
 *       absent when those inputs are. Work answered only by the sandbox is never counted.
 * </ul>
 *
 * <p>Runs are counted on the day they started; goals and tasks on the day they finished;
 * approvals and questions on the day they were raised. The previous window is the same length
 * ending the same time of day, so a change compares like with like even though today is only
 * part-way through.
 */
@Service
public class InsightsService {

    /** The runtime setting that holds the workspace's loaded hourly staff cost, in US dollars. */
    public static final String KEY_HOURLY_RATE = "value.hourlyRate";

    /** The prefix of the per-agent settings: {@code value.minutesPerTask.<agentId>}. */
    public static final String KEY_MINUTES_PREFIX = "value.minutesPerTask.";

    /** Finished runs an agent needs before a rate over them says anything. */
    public static final int MIN_FINISHED_RUNS = 5;

    /** What every amount here is, said once so no client has to guess. */
    public static final String BASIS = "estimated USD from catalogue prices";

    /** What every figure derived from the administrator's inputs is called, wherever it is shown. */
    public static final String VALUE_LABEL = "Estimate from your inputs (current values)";

    static final List<String> TERMINAL = List.of("completed", "failed", "cancelled", "abandoned");

    private static final int FAILURE_REASONS = 5;
    private static final int REASON_CHARS = 200;

    /**
     * Whether a run was answered by the offline sandbox alone: it has a successful call on it, and
     * none of them went to a real provider. Mirrors how the trace page decides the same thing.
     * {@code r} is the runs alias.
     */
    private static final String SANDBOX_ONLY =
            """
            (exists (select 1 from llm_usage u where u.run_id = r.id and u.org_id = r.org_id
                       and u.provider_id = 'sandbox' and u.outcome = 'SUCCEEDED')
             and not exists (select 1 from llm_usage u where u.run_id = r.id and u.org_id = r.org_id
                       and u.provider_id <> 'sandbox' and u.outcome = 'SUCCEEDED'))
            """;

    /** The same for a task: every run it has. {@code t} is the tasks alias. */
    private static final String SANDBOX_ONLY_TASK =
            """
            (exists (select 1 from runs r join llm_usage u on u.run_id = r.id and u.org_id = r.org_id
                      where r.task_id = t.id and r.org_id = t.org_id
                        and u.provider_id = 'sandbox' and u.outcome = 'SUCCEEDED')
             and not exists (select 1 from runs r join llm_usage u on u.run_id = r.id and u.org_id = r.org_id
                      where r.task_id = t.id and r.org_id = t.org_id
                        and u.provider_id <> 'sandbox' and u.outcome = 'SUCCEEDED'))
            """;

    private static final String GOALS_BY_DAY_AND_SOURCE =
            """
            select to_char(date_trunc('day', completed_at at time zone 'UTC'), 'YYYY-MM-DD') as day,
                   source, status, count(*) as n
            from goals
            where org_id = ? and status in ('completed', 'failed', 'cancelled')
              and completed_at >= ? and completed_at < ?
            group by 1, 2, 3
            """;

    private static final String TASK_OUTCOMES =
            """
            select t.status, count(*) as n,
                   count(*) filter (where (select count(*) from runs r
                                           where r.task_id = t.id and r.org_id = t.org_id) = 1) as single_run
            from tasks t
            where t.org_id = ? and t.status in ('completed', 'failed')
              and t.completed_at >= ? and t.completed_at < ?
            group by t.status
            """;

    /**
     * Whether every successful call of a run went to a model the catalogue marks free (an
     * OpenRouter ":free" model, NVIDIA's free tier), with at least one real call. Such a run cost
     * nothing because the model is free, which is known - unlike a model with no price on file.
     * {@code r} is the runs alias.
     */
    static final String FREE_ONLY =
            """
            (exists (select 1 from llm_usage u where u.run_id = r.id and u.org_id = r.org_id
                       and u.provider_id <> 'sandbox' and u.outcome = 'SUCCEEDED')
             and not exists (select 1 from llm_usage u
                       left join llm_models m on m.provider_id = u.provider_id and m.model_id = u.model_id
                       where u.run_id = r.id and u.org_id = r.org_id and u.outcome = 'SUCCEEDED'
                         and u.provider_id <> 'sandbox' and coalesce(m.free, false) = false))
            """;

    private static final String RUNS_BY_AGENT_AND_STATUS =
            """
            with flagged as (
                select r.agent_id, r.status, r.total_cost,
                       (r.total_prompt_tokens + r.total_completion_tokens) as tokens,
                       %s as sandbox,
                       %s as free
                from runs r
                where r.org_id = ? and r.started_at >= ? and r.started_at < ?
            )
            select agent_id, status, count(*) as n,
                   coalesce(sum(total_cost), 0) as cost,
                   count(*) filter (where total_cost > 0) as priced,
                   count(*) filter (where total_cost = 0 and tokens > 0 and not sandbox and not free) as unpriced,
                   count(*) filter (where sandbox) as sandbox_runs,
                   count(*) filter (where total_cost = 0 and free) as free_runs
            from flagged
            group by agent_id, status
            """
                    .formatted(SANDBOX_ONLY, FREE_ONLY);

    private static final String SPEND_BY_DAY =
            """
            select to_char(date_trunc('day', occurred_at at time zone 'UTC'), 'YYYY-MM-DD') as day,
                   coalesce(sum(cost), 0) as cost
            from llm_usage
            where org_id = ? and occurred_at >= ? and occurred_at < ?
            group by 1
            """;

    /** What each completed goal cost, and whether any of its work was unpriced or sandbox-only. */
    private static final String COMPLETED_GOAL_COSTS =
            """
            with goal_runs as (
                select g.id as goal_id, r.total_cost,
                       (r.total_cost = 0 and r.total_prompt_tokens + r.total_completion_tokens > 0
                        and not %1$s and not %2$s) as unpriced,
                       %1$s as sandbox
                from goals g
                join tasks t on t.goal_id = g.id and t.org_id = g.org_id
                join runs r on r.task_id = t.id and r.org_id = g.org_id
                where g.org_id = ? and g.status = 'completed' and g.completed_at >= ? and g.completed_at < ?
            )
            select goal_id, sum(total_cost) as cost, bool_or(unpriced) as has_unpriced,
                   bool_and(sandbox) as all_sandbox
            from goal_runs
            group by goal_id
            """
                    .formatted(SANDBOX_ONLY, FREE_ONLY);

    private static final String APPROVALS_BY_AGENT_AND_STATUS =
            """
            select agent_id, status, count(*) as n
            from approvals
            where org_id = ? and requested_at >= ? and requested_at < ?
            group by agent_id, status
            """;

    /** Only decisions a person made: an expiry's decided_at is the platform's, not a person's. */
    private static final String APPROVAL_DECISION_TIMES =
            """
            select percentile_cont(0.5) within group (order by extract(epoch from (decided_at - requested_at))),
                   percentile_cont(0.9) within group (order by extract(epoch from (decided_at - requested_at)))
            from approvals
            where org_id = ? and requested_at >= ? and requested_at < ?
              and status in ('approved', 'rejected') and decided_at is not null
            """;

    private static final String QUESTIONS =
            """
            select count(*) as asked,
                   count(*) filter (where status = 'answered') as answered,
                   percentile_cont(0.5) within group (order by extract(epoch from (answered_at - created_at)))
                       filter (where status = 'answered' and answered_at is not null) as median_seconds
            from run_questions
            where org_id = ? and created_at >= ? and created_at < ?
            """;

    private static final String FAILURE_REASON_ROWS =
            """
            select left(failure_reason, %d) as reason, count(*) as n
            from runs
            where org_id = ? and started_at >= ? and started_at < ?
              and status in ('failed', 'abandoned')
              and failure_reason is not null and btrim(failure_reason) <> ''
            group by 1
            order by n desc, reason
            limit %d
            """
                    .formatted(REASON_CHARS, FAILURE_REASONS);

    /** Tasks finished in the window, by agent, leaving out work the offline sandbox answered. */
    private static final String COMPLETED_TASKS_BY_AGENT =
            """
            select t.agent_id, count(*) as n
            from tasks t
            where t.org_id = ? and t.status = 'completed' and t.agent_id is not null
              and t.completed_at >= ? and t.completed_at < ?
              and not %s
            group by t.agent_id
            """
                    .formatted(SANDBOX_ONLY_TASK);

    private static final String AGENT_ROWS =
            """
            select a.id, a.name, a.category, a.status,
                   (select max(r.started_at) from runs r where r.agent_id = a.id and r.org_id = a.org_id)
                       as last_active
            from agents a
            where a.org_id = ?
            order by a.name
            """;

    private static final String SATISFACTION_BY_AGENT =
            """
            select agent_id,
                   count(*) filter (where rating = 1) as up,
                   count(*) filter (where rating = -1) as down
            from chat_message_feedback
            where org_id = ? and agent_id is not null and updated_at >= ? and updated_at < ?
            group by agent_id
            """;

    private final JdbcTemplate jdbc;
    private final RuntimeConfigStore settings;
    private final Clock clock;

    @Autowired
    public InsightsService(JdbcTemplate jdbc, RuntimeConfigStore settings) {
        this(jdbc, settings, Clock.systemUTC());
    }

    InsightsService(JdbcTemplate jdbc, RuntimeConfigStore settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- Windows ---------------------------------------------------------------------------

    /** The windows a client may ask for. */
    public enum Span {
        D7("7d", 7),
        D30("30d", 30),
        D90("90d", 90);

        private final String key;
        private final int days;

        Span(String key, int days) {
            this.key = key;
            this.days = days;
        }

        public String key() {
            return key;
        }

        /** {@code 7d}, {@code 30d} or {@code 90d}; the 30 days Analytics opens on when none is given. */
        public static Span parse(String text) {
            if (text == null || text.isBlank()) {
                return D30;
            }
            String wanted = text.strip().toLowerCase(Locale.ROOT);
            for (Span span : values()) {
                if (span.key.equals(wanted)) {
                    return span;
                }
            }
            throw ApiException.validation("window", "Choose 7d, 30d or 90d.");
        }
    }

    /**
     * The window and the one before it. It runs from the start of the UTC day {@code days - 1}
     * days ago to now, so a 7-day window is seven day buckets, today's still filling. The previous
     * window ends exactly {@code days} days before now, so both cover the same length of time.
     */
    record Window(String key, int days, Instant from, Instant to, Instant previousFrom, Instant previousTo) {

        static Window of(Span span, Instant now) {
            Instant from = now.truncatedTo(ChronoUnit.DAYS).minus(span.days - 1L, ChronoUnit.DAYS);
            Duration length = Duration.ofDays(span.days);
            return new Window(span.key, span.days, from, now, from.minus(length), now.minus(length));
        }

        /** Every UTC day the window touches, oldest first, so a quiet day shows as a zero. */
        List<String> dayKeys() {
            List<String> days = new ArrayList<>();
            LocalDate last = LocalDate.ofInstant(to, ZoneOffset.UTC);
            for (LocalDate day = LocalDate.ofInstant(from, ZoneOffset.UTC); !day.isAfter(last); day = day.plusDays(1)) {
                days.add(day.toString());
            }
            return days;
        }
    }

    // ---- What the client receives ------------------------------------------------------------

    public record SourceCount(String source, long completed, long failed) {}

    public record DayCount(String day, long completed, long failed) {}

    /** Goals that finished in the window. A goal a person stopped is counted apart from the rest. */
    public record GoalFigures(
            long completed, long failed, long cancelled, List<SourceCount> bySource, List<DayCount> byDay) {}

    /**
     * @param successRate completed over completed and failed, 0 to 1; absent when none finished
     * @param completedWithoutRetry completed tasks that needed exactly one run
     * @param withoutRetryRate their share of the completed tasks; absent when none completed
     */
    public record TaskFigures(
            long completed,
            long failed,
            BigDecimal successRate,
            long completedWithoutRetry,
            BigDecimal withoutRetryRate) {}

    /**
     * @param total runs that started in the window, whatever became of them
     * @param finished those that have ended
     * @param active those still going or waiting
     * @param byStatus the ended ones by how they ended
     * @param unpriced ended runs that used a real model the catalogue has no price for
     * @param sandboxOnly ended runs the offline sandbox answered alone
     */
    public record RunFigures(
            long total,
            long finished,
            long active,
            Map<String, Long> byStatus,
            long unpriced,
            long sandboxOnly) {}

    public record DayCost(String day, BigDecimal cost) {}

    /**
     * @param total everything the usage table recorded in the window, failed attempts included
     * @param costPerCompletedGoal what a completed goal cost on average, over the goals whose work
     *     was priced; absent when there were none
     * @param costedGoals the completed goals that average is over
     * @param unpricedGoals completed goals left out because a run of theirs was unpriced
     * @param sandboxGoals completed goals left out because the offline sandbox did all their work
     * @param failedOrCancelledSpend what runs that failed, were cancelled or were abandoned cost
     * @param unpricedRuns ended runs whose cost is unknown, so the figures above are a floor
     */
    public record SpendFigures(
            BigDecimal total,
            List<DayCost> byDay,
            BigDecimal costPerCompletedGoal,
            long costedGoals,
            long unpricedGoals,
            long sandboxGoals,
            BigDecimal failedOrCancelledSpend,
            long unpricedRuns,
            String basis) {}

    /** Seconds from raising an approval to a person deciding it; absent when nobody has. */
    public record ApprovalFigures(
            long raised,
            long approved,
            long rejected,
            long expired,
            long cancelled,
            long pending,
            Long medianDecisionSeconds,
            Long p90DecisionSeconds) {}

    public record QuestionFigures(long asked, long answered, BigDecimal per100Runs, Long medianAnswerSeconds) {}

    public record FailureReason(String reason, long count) {}

    /**
     * The worth of the work, from the administrator's inputs; the whole object is absent until at
     * least one agent has an estimate. Every amount in it is {@link #VALUE_LABEL}.
     *
     * @param hourlyRate the loaded hourly staff cost they entered; absent when they have not
     * @param agentsEstimated agents that have minutes per task
     * @param agentsNotEstimated agents that finished tasks with no estimate, which add nothing here
     * @param hoursReturned finished tasks times the minutes a person would spend on one, in hours
     * @param humanEquivalentCost those hours at the hourly rate; absent without a rate
     * @param netValue that less what the estimated agents cost; absent without a rate
     */
    public record ValueFigures(
            String label,
            BigDecimal hourlyRate,
            long agentsEstimated,
            long agentsNotEstimated,
            long completedTasks,
            BigDecimal hoursReturned,
            BigDecimal humanEquivalentCost,
            BigDecimal netValue) {}

    /** A figure now and in the previous window; the change is absent where either side is. */
    public record Delta(BigDecimal current, BigDecimal previous, BigDecimal change, BigDecimal changePercent) {}

    public record Insights(
            String window,
            Instant from,
            Instant to,
            Instant previousFrom,
            Instant previousTo,
            String basis,
            GoalFigures goals,
            TaskFigures tasks,
            RunFigures runs,
            SpendFigures spend,
            ApprovalFigures approvals,
            QuestionFigures questions,
            List<FailureReason> failureReasons,
            ValueFigures value,
            Map<String, Delta> deltas) {}

    /**
     * One agent's outcomes over the window.
     *
     * @param runs started in the window
     * @param finishedRuns those that ended
     * @param failed failed or abandoned
     * @param enoughRuns whether there are {@value #MIN_FINISHED_RUNS} or more finished runs; when
     *     false the success rate is absent and the screen says "Not enough runs yet"
     * @param successRate completed over finished, 0 to 1
     * @param totalCost what the runs cost; absent when every priced figure is missing because the
     *     work was unpriced
     * @param avgCostPerCompleted over completed runs that have a price
     * @param unpricedRuns runs on a real model with no catalogue price
     * @param sandboxRuns runs the offline sandbox answered alone
     * @param lastActive when the agent last started a run, in any window
     * @param ratings thumbs given to its chat answers in the window
     * @param satisfactionRate thumbs up over ratings, 0 to 1; absent with none
     * @param minutesPerTask the administrator's estimate; absent when there is none
     * @param completedTasks tasks it finished in the window that count toward hours
     * @param hoursReturned those tasks at that estimate; absent with no estimate
     * @param freeRuns runs that cost nothing because every model they used is free in the catalogue
     */
    public record AgentRow(
            UUID agentId,
            String name,
            String category,
            String status,
            long runs,
            long finishedRuns,
            long completed,
            long failed,
            long cancelled,
            boolean enoughRuns,
            BigDecimal successRate,
            BigDecimal totalCost,
            BigDecimal avgCostPerCompleted,
            long unpricedRuns,
            long sandboxRuns,
            long rejectedApprovals,
            Instant lastActive,
            long ratings,
            long thumbsDown,
            BigDecimal satisfactionRate,
            Integer minutesPerTask,
            long completedTasks,
            BigDecimal hoursReturned,
            long freeRuns) {}

    public record AgentInsights(
            String window,
            Instant from,
            Instant to,
            String basis,
            String valueLabel,
            BigDecimal hourlyRate,
            List<AgentRow> agents) {}

    /** The administrator's inputs for the worth of the work. */
    public record ValueInputs(BigDecimal hourlyRate, Map<UUID, Integer> minutesPerTask) {

        public ValueInputs {
            minutesPerTask = minutesPerTask == null ? Map.of() : Map.copyOf(minutesPerTask);
        }

        public static final ValueInputs NONE = new ValueInputs(null, Map.of());
    }

    // ---- Reading ---------------------------------------------------------------------------

    // @find: workspace insights, analytics figures for a window
    @Transactional(readOnly = true)
    public Insights insights(UUID orgId, String windowKey) {
        Window window = Window.of(Span.parse(windowKey), clock.instant());
        ValueInputs inputs = valueInputs(orgId);
        Figures current = figures(orgId, window.from(), window.to(), inputs, window, true);
        Figures previous = figures(orgId, window.previousFrom(), window.previousTo(), inputs, null, false);
        return new Insights(
                window.key(),
                window.from(),
                window.to(),
                window.previousFrom(),
                window.previousTo(),
                BASIS,
                current.goals(),
                current.tasks(),
                current.runs(),
                current.spend(),
                current.approvals(),
                current.questions(),
                current.failureReasons(),
                current.value(),
                deltas(current, previous));
    }

    // @find: agent insights, per-agent rows for a window
    @Transactional(readOnly = true)
    public AgentInsights agents(UUID orgId, String windowKey) {
        Window window = Window.of(Span.parse(windowKey), clock.instant());
        ValueInputs inputs = valueInputs(orgId);
        Timestamp from = ts(window.from());
        Timestamp to = ts(window.to());

        Map<UUID, List<RunGroup>> runsByAgent = new HashMap<>();
        for (RunGroup group : runGroups(orgId, from, to)) {
            runsByAgent.computeIfAbsent(group.agentId(), key -> new ArrayList<>()).add(group);
        }
        Map<UUID, Long> rejected = new HashMap<>();
        for (ApprovalGroup group : approvalGroups(orgId, from, to)) {
            if ("rejected".equals(group.status()) && group.agentId() != null) {
                rejected.merge(group.agentId(), group.n(), Long::sum);
            }
        }
        Map<UUID, long[]> satisfaction = new HashMap<>();
        jdbc.query(
                SATISFACTION_BY_AGENT,
                rs -> {
                    satisfaction.put(rs.getObject("agent_id", UUID.class), new long[] {rs.getLong("up"), rs.getLong("down")});
                },
                orgId,
                from,
                to);
        Map<UUID, Long> tasks = completedTasksByAgent(orgId, from, to);

        List<AgentRow> rows = jdbc.query(
                AGENT_ROWS,
                (rs, index) -> {
                    UUID agentId = rs.getObject("id", UUID.class);
                    Timestamp lastActive = rs.getTimestamp("last_active");
                    return agentRow(
                            agentId,
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getString("status"),
                            runsByAgent.getOrDefault(agentId, List.of()),
                            rejected.getOrDefault(agentId, 0L),
                            lastActive == null ? null : lastActive.toInstant(),
                            satisfaction.get(agentId),
                            inputs.minutesPerTask().get(agentId),
                            tasks.getOrDefault(agentId, 0L));
                },
                orgId);
        return new AgentInsights(window.key(), window.from(), window.to(), BASIS, VALUE_LABEL, inputs.hourlyRate(), rows);
    }

    /** The administrator's inputs, read from the workspace's runtime settings; malformed ones are ignored. */
    // @find: value inputs, hourly staff rate, minutes per task
    public ValueInputs valueInputs(UUID orgId) {
        BigDecimal rate = null;
        Map<UUID, Integer> minutes = new HashMap<>();
        for (RuntimeConfigStore.StoredValue stored : settings.readAll(orgId.toString())) {
            String key = stored.key();
            if (key == null || !key.startsWith("value.")) {
                continue;
            }
            try {
                if (KEY_HOURLY_RATE.equals(key)) {
                    BigDecimal parsed = new BigDecimal(stored.value().trim());
                    rate = parsed.signum() > 0 ? parsed : null;
                } else if (key.startsWith(KEY_MINUTES_PREFIX)) {
                    UUID agentId = UUID.fromString(key.substring(KEY_MINUTES_PREFIX.length()));
                    int parsed = Integer.parseInt(stored.value().trim());
                    if (parsed > 0) {
                        minutes.put(agentId, parsed);
                    }
                }
            } catch (RuntimeException e) {
                // A value somebody wrote by hand that is not a number or an id: no input is better
                // than a wrong one, so it is left out.
            }
        }
        return new ValueInputs(rate, minutes);
    }

    // ---- One window ------------------------------------------------------------------------

    private record Figures(
            GoalFigures goals,
            TaskFigures tasks,
            RunFigures runs,
            SpendFigures spend,
            ApprovalFigures approvals,
            QuestionFigures questions,
            List<FailureReason> failureReasons,
            ValueFigures value) {}

    /** One agent's runs in one status: how many, what they cost, and how many were priced, unpriced or sandbox. */
    record RunGroup(
            UUID agentId, String status, long n, BigDecimal cost, long priced, long unpriced, long sandbox, long free) {

        RunGroup(UUID agentId, String status, long n, BigDecimal cost, long priced, long unpriced, long sandbox) {
            this(agentId, status, n, cost, priced, unpriced, sandbox, 0);
        }
    }

    private record ApprovalGroup(UUID agentId, String status, long n) {}

    private Figures figures(UUID orgId, Instant fromInstant, Instant toInstant, ValueInputs inputs, Window days, boolean detail) {
        Timestamp from = ts(fromInstant);
        Timestamp to = ts(toInstant);
        List<RunGroup> runGroups = runGroups(orgId, from, to);
        RunFigures runs = runFigures(runGroups);
        return new Figures(
                goalFigures(orgId, from, to, days),
                taskFigures(orgId, from, to),
                runs,
                spendFigures(orgId, from, to, runGroups, runs, days),
                approvalFigures(orgId, from, to),
                questionFigures(orgId, from, to, runs.total()),
                detail ? failureReasons(orgId, from, to) : List.of(),
                valueFigures(inputs, completedTasksByAgent(orgId, from, to), costByAgent(runGroups)));
    }

    private GoalFigures goalFigures(UUID orgId, Timestamp from, Timestamp to, Window days) {
        long completed = 0;
        long failed = 0;
        long cancelled = 0;
        Map<String, long[]> bySource = new TreeMap<>();
        Map<String, long[]> byDay = new TreeMap<>();
        if (days != null) {
            days.dayKeys().forEach(day -> byDay.put(day, new long[2]));
        }
        for (Object[] row : jdbc.query(
                GOALS_BY_DAY_AND_SOURCE,
                (rs, index) -> new Object[] {rs.getString("day"), rs.getString("source"), rs.getString("status"), rs.getLong("n")},
                orgId,
                from,
                to)) {
            String day = (String) row[0];
            String source = (String) row[1];
            String status = (String) row[2];
            long n = (Long) row[3];
            switch (status) {
                case "completed" -> {
                    completed += n;
                    bySource.computeIfAbsent(source, key -> new long[2])[0] += n;
                    if (days != null) {
                        byDay.computeIfAbsent(day, key -> new long[2])[0] += n;
                    }
                }
                case "failed" -> {
                    failed += n;
                    bySource.computeIfAbsent(source, key -> new long[2])[1] += n;
                    if (days != null) {
                        byDay.computeIfAbsent(day, key -> new long[2])[1] += n;
                    }
                }
                default -> cancelled += n;
            }
        }
        List<SourceCount> sources = new ArrayList<>();
        bySource.forEach((source, counts) -> sources.add(new SourceCount(source, counts[0], counts[1])));
        List<DayCount> perDay = new ArrayList<>();
        byDay.forEach((day, counts) -> perDay.add(new DayCount(day, counts[0], counts[1])));
        return new GoalFigures(completed, failed, cancelled, sources, perDay);
    }

    private TaskFigures taskFigures(UUID orgId, Timestamp from, Timestamp to) {
        long completed = 0;
        long failed = 0;
        long singleRun = 0;
        for (long[] row : jdbc.query(
                TASK_OUTCOMES,
                (rs, index) -> new long[] {"completed".equals(rs.getString("status")) ? 1 : 0, rs.getLong("n"), rs.getLong("single_run")},
                orgId,
                from,
                to)) {
            if (row[0] == 1) {
                completed += row[1];
                singleRun += row[2];
            } else {
                failed += row[1];
            }
        }
        return new TaskFigures(completed, failed, ratio(completed, completed + failed), singleRun, ratio(singleRun, completed));
    }

    private List<RunGroup> runGroups(UUID orgId, Timestamp from, Timestamp to) {
        return jdbc.query(
                RUNS_BY_AGENT_AND_STATUS,
                (rs, index) -> new RunGroup(
                        rs.getObject("agent_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("n"),
                        rs.getBigDecimal("cost"),
                        rs.getLong("priced"),
                        rs.getLong("unpriced"),
                        rs.getLong("sandbox_runs"),
                        rs.getLong("free_runs")),
                orgId,
                from,
                to);
    }

    private static RunFigures runFigures(List<RunGroup> groups) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        TERMINAL.forEach(status -> byStatus.put(status, 0L));
        long total = 0;
        long finished = 0;
        long unpriced = 0;
        long sandbox = 0;
        for (RunGroup group : groups) {
            total += group.n();
            if (TERMINAL.contains(group.status())) {
                byStatus.merge(group.status(), group.n(), Long::sum);
                finished += group.n();
                unpriced += group.unpriced();
                sandbox += group.sandbox();
            }
        }
        return new RunFigures(total, finished, total - finished, byStatus, unpriced, sandbox);
    }

    private SpendFigures spendFigures(
            UUID orgId, Timestamp from, Timestamp to, List<RunGroup> runGroups, RunFigures runs, Window days) {
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byDay = new TreeMap<>();
        if (days != null) {
            days.dayKeys().forEach(day -> byDay.put(day, BigDecimal.ZERO));
        }
        for (Object[] row : jdbc.query(
                SPEND_BY_DAY, (rs, index) -> new Object[] {rs.getString("day"), rs.getBigDecimal("cost")}, orgId, from, to)) {
            BigDecimal cost = (BigDecimal) row[1];
            total = total.add(cost);
            if (days != null) {
                byDay.merge((String) row[0], cost, BigDecimal::add);
            }
        }
        List<DayCost> perDay = new ArrayList<>();
        byDay.forEach((day, cost) -> perDay.add(new DayCost(day, cost)));

        BigDecimal wasted = BigDecimal.ZERO;
        for (RunGroup group : runGroups) {
            if ("failed".equals(group.status()) || "cancelled".equals(group.status()) || "abandoned".equals(group.status())) {
                wasted = wasted.add(group.cost());
            }
        }

        long costed = 0;
        long unpricedGoals = 0;
        long sandboxGoals = 0;
        BigDecimal costedTotal = BigDecimal.ZERO;
        for (Object[] goal : jdbc.query(
                COMPLETED_GOAL_COSTS,
                (rs, index) -> new Object[] {rs.getBigDecimal("cost"), rs.getBoolean("has_unpriced"), rs.getBoolean("all_sandbox")},
                orgId,
                from,
                to)) {
            if ((Boolean) goal[1]) {
                unpricedGoals++;
            } else if ((Boolean) goal[2]) {
                sandboxGoals++;
            } else {
                costed++;
                costedTotal = costedTotal.add((BigDecimal) goal[0]);
            }
        }
        BigDecimal perGoal = costed == 0 ? null : costedTotal.divide(BigDecimal.valueOf(costed), 6, RoundingMode.HALF_UP);
        return new SpendFigures(total, perDay, perGoal, costed, unpricedGoals, sandboxGoals, wasted, runs.unpriced(), BASIS);
    }

    private List<ApprovalGroup> approvalGroups(UUID orgId, Timestamp from, Timestamp to) {
        return jdbc.query(
                APPROVALS_BY_AGENT_AND_STATUS,
                (rs, index) -> new ApprovalGroup(rs.getObject("agent_id", UUID.class), rs.getString("status"), rs.getLong("n")),
                orgId,
                from,
                to);
    }

    private ApprovalFigures approvalFigures(UUID orgId, Timestamp from, Timestamp to) {
        Map<String, Long> byStatus = new HashMap<>();
        long raised = 0;
        for (ApprovalGroup group : approvalGroups(orgId, from, to)) {
            byStatus.merge(group.status(), group.n(), Long::sum);
            raised += group.n();
        }
        Long[] seconds = jdbc.query(
                        APPROVAL_DECISION_TIMES,
                        (rs, index) -> new Long[] {seconds(rs.getBigDecimal(1)), seconds(rs.getBigDecimal(2))},
                        orgId,
                        from,
                        to)
                .stream()
                .findFirst()
                .orElse(new Long[] {null, null});
        return new ApprovalFigures(
                raised,
                byStatus.getOrDefault("approved", 0L),
                byStatus.getOrDefault("rejected", 0L),
                byStatus.getOrDefault("expired", 0L),
                byStatus.getOrDefault("cancelled", 0L),
                byStatus.getOrDefault("pending", 0L),
                seconds[0],
                seconds[1]);
    }

    private QuestionFigures questionFigures(UUID orgId, Timestamp from, Timestamp to, long runsStarted) {
        return jdbc.query(
                        QUESTIONS,
                        (rs, index) -> {
                            long asked = rs.getLong("asked");
                            BigDecimal per100 = runsStarted == 0
                                    ? null
                                    : BigDecimal.valueOf(asked * 100L)
                                            .divide(BigDecimal.valueOf(runsStarted), 1, RoundingMode.HALF_UP);
                            return new QuestionFigures(asked, rs.getLong("answered"), per100, seconds(rs.getBigDecimal("median_seconds")));
                        },
                        orgId,
                        from,
                        to)
                .getFirst();
    }

    private List<FailureReason> failureReasons(UUID orgId, Timestamp from, Timestamp to) {
        return jdbc.query(
                FAILURE_REASON_ROWS, (rs, index) -> new FailureReason(rs.getString("reason"), rs.getLong("n")), orgId, from, to);
    }

    private Map<UUID, Long> completedTasksByAgent(UUID orgId, Timestamp from, Timestamp to) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query(
                COMPLETED_TASKS_BY_AGENT,
                rs -> {
                    counts.put(rs.getObject("agent_id", UUID.class), rs.getLong("n"));
                },
                orgId,
                from,
                to);
        return counts;
    }

    private static Map<UUID, BigDecimal> costByAgent(List<RunGroup> groups) {
        Map<UUID, BigDecimal> costs = new HashMap<>();
        for (RunGroup group : groups) {
            costs.merge(group.agentId(), group.cost(), BigDecimal::add);
        }
        return costs;
    }

    // ---- Composing -------------------------------------------------------------------------

    /**
     * The worth of the work, or null when no agent has an estimate. With minutes but no hourly rate
     * the hours are still known and what they are worth is not, so those two stay absent.
     */
    static ValueFigures valueFigures(
            ValueInputs inputs, Map<UUID, Long> completedTasks, Map<UUID, BigDecimal> costByAgent) {
        if (inputs == null || inputs.minutesPerTask().isEmpty()) {
            return null;
        }
        BigDecimal minutes = BigDecimal.ZERO;
        BigDecimal estimatedCost = BigDecimal.ZERO;
        long counted = 0;
        for (Map.Entry<UUID, Integer> estimate : inputs.minutesPerTask().entrySet()) {
            long tasks = completedTasks.getOrDefault(estimate.getKey(), 0L);
            counted += tasks;
            minutes = minutes.add(BigDecimal.valueOf(tasks).multiply(BigDecimal.valueOf(estimate.getValue())));
            estimatedCost = estimatedCost.add(costByAgent.getOrDefault(estimate.getKey(), BigDecimal.ZERO));
        }
        long notEstimated = completedTasks.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && !inputs.minutesPerTask().containsKey(entry.getKey()))
                .count();
        BigDecimal hours = minutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal humanCost = inputs.hourlyRate() == null ? null : hours.multiply(inputs.hourlyRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = humanCost == null ? null : humanCost.subtract(estimatedCost.setScale(2, RoundingMode.HALF_UP));
        return new ValueFigures(
                VALUE_LABEL,
                inputs.hourlyRate(),
                inputs.minutesPerTask().size(),
                notEstimated,
                counted,
                hours,
                humanCost,
                net);
    }

    static AgentRow agentRow(
            UUID agentId,
            String name,
            String category,
            String status,
            List<RunGroup> groups,
            long rejectedApprovals,
            Instant lastActive,
            long[] ratings,
            Integer minutesPerTask,
            long completedTasks) {
        long runs = 0;
        long finished = 0;
        long completed = 0;
        long failed = 0;
        long cancelled = 0;
        long unpriced = 0;
        long sandbox = 0;
        long free = 0;
        long completedPriced = 0;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal completedCost = BigDecimal.ZERO;
        for (RunGroup group : groups) {
            runs += group.n();
            cost = cost.add(group.cost());
            unpriced += group.unpriced();
            sandbox += group.sandbox();
            free += group.free();
            if (!TERMINAL.contains(group.status())) {
                continue;
            }
            finished += group.n();
            switch (group.status()) {
                case "completed" -> {
                    completed += group.n();
                    completedPriced += group.priced();
                    completedCost = completedCost.add(group.cost());
                }
                case "cancelled" -> cancelled += group.n();
                default -> failed += group.n();
            }
        }
        boolean enough = finished >= MIN_FINISHED_RUNS;
        // Nothing priced and something unpriced: the cost is not zero, it is not known.
        BigDecimal totalCost = cost.signum() == 0 && unpriced > 0 ? null : cost;
        BigDecimal average = completedPriced == 0
                ? null
                : completedCost.divide(BigDecimal.valueOf(completedPriced), 6, RoundingMode.HALF_UP);
        long up = ratings == null ? 0 : ratings[0];
        long down = ratings == null ? 0 : ratings[1];
        BigDecimal hours = minutesPerTask == null
                ? null
                : BigDecimal.valueOf(completedTasks)
                        .multiply(BigDecimal.valueOf(minutesPerTask))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return new AgentRow(
                agentId,
                name,
                category,
                status,
                runs,
                finished,
                completed,
                failed,
                cancelled,
                enough,
                enough ? ratio(completed, finished) : null,
                totalCost,
                average,
                unpriced,
                sandbox,
                rejectedApprovals,
                lastActive,
                up + down,
                down,
                ratio(up, up + down),
                minutesPerTask,
                completedTasks,
                hours,
                free);
    }

    private static Map<String, Delta> deltas(Figures current, Figures previous) {
        Map<String, Delta> deltas = new LinkedHashMap<>();
        deltas.put("goalsCompleted", delta(current.goals().completed(), previous.goals().completed()));
        deltas.put("goalsFailed", delta(current.goals().failed(), previous.goals().failed()));
        deltas.put("taskSuccessRate", delta(current.tasks().successRate(), previous.tasks().successRate()));
        deltas.put("spend", delta(current.spend().total(), previous.spend().total()));
        deltas.put(
                "costPerCompletedGoal",
                delta(current.spend().costPerCompletedGoal(), previous.spend().costPerCompletedGoal()));
        deltas.put(
                "medianApprovalSeconds",
                delta(toDecimal(current.approvals().medianDecisionSeconds()), toDecimal(previous.approvals().medianDecisionSeconds())));
        deltas.put(
                "hoursReturned",
                delta(
                        current.value() == null ? null : current.value().hoursReturned(),
                        previous.value() == null ? null : previous.value().hoursReturned()));
        return deltas;
    }

    private static Delta delta(long current, long previous) {
        return delta(BigDecimal.valueOf(current), BigDecimal.valueOf(previous));
    }

    /**
     * The change, and that as a percentage of the previous figure. Either is absent where it has
     * no meaning: with no figure on one side, and a percentage of nothing.
     */
    static Delta delta(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) {
            return new Delta(current, previous, null, null);
        }
        BigDecimal change = current.subtract(previous);
        BigDecimal percent = previous.signum() == 0
                ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(previous.abs(), 1, RoundingMode.HALF_UP);
        return new Delta(current, previous, change, percent);
    }

    /** {@code part} over {@code whole} to four places, or null when there is no whole to be a part of. */
    static BigDecimal ratio(long part, long whole) {
        return whole <= 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    private static Long seconds(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static BigDecimal toDecimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
