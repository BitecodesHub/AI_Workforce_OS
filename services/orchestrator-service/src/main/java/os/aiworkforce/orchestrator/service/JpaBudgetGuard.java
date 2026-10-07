package os.aiworkforce.orchestrator.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.router.ModelRouter.CallContext;
import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.repository.Budgets;
import os.aiworkforce.orchestrator.repository.Usage;

/**
 * Enforces a workspace's spending caps.
 *
 * <p>Checked before every attempt rather than once per run. A single agent working through a task
 * can make dozens of model calls, and a budget verified only at the start is not a budget: one
 * long run can spend a month's allowance between two checks.
 *
 * <p>Three caps, each refusing on its own, most specific first: what this run has spent, what
 * this agent has spent today, and what the workspace has spent this month. Spend is never kept
 * as a counter. It is summed from {@code llm_usage}, which already records every attempt with
 * its cost, so there is no second figure to drift from it, no row that two runs paying at once
 * can fight over, and a cap set part-way through a month counts what was already spent.
 *
 * <p>The month's sum is cached per workspace for a few seconds, so a long run does not add up the
 * whole month at every step. A paid call adds its cost to the cached figure, so the cache is
 * short of the truth only by what other processes spend inside that window. The per-run and
 * per-agent sums are not cached: they are cheap, bounded by one run or one agent's day, and are
 * only computed when that cap is set.
 *
 * <p>The estimate is necessarily approximate - the true cost is known only after the provider
 * answers - so the guard rejects on the estimate and reconciles on the actual. A workspace can
 * overshoot slightly on its final call, which is accepted deliberately: the alternative is
 * blocking every request behind an exact accounting that cannot exist.
 *
 * <p>Every refusal tells an administrator where to raise the cap. A refusal also carries the
 * workspace's choice of what to do at a cap: stop, or answer with the offline model.
 */
@Service
public class JpaBudgetGuard implements BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(JpaBudgetGuard.class);

    /** How long a workspace's month-to-date sum is reused. */
    static final Duration MONTH_SPEND_TTL = Duration.ofSeconds(5);

    /** More than the workspaces active inside one cache lifetime; past it, stale entries are dropped. */
    static final int MAX_CACHED_WORKSPACES = 2_000;

    private static final String RAISE_THE_CAP = " Ask an administrator to raise the cap in Analytics, Budget.";

    private record CachedSpend(BigDecimal spent, Instant loadedAt) {

        boolean isFresh(Instant now) {
            return now.isBefore(loadedAt.plus(MONTH_SPEND_TTL));
        }
    }

    private final Budgets budgets;
    private final Usage usage;
    private final Clock clock;
    private final Map<UUID, CachedSpend> monthSpend = new ConcurrentHashMap<>();

    @Autowired
    public JpaBudgetGuard(Budgets budgets, Usage usage) {
        this(budgets, usage, Clock.systemUTC());
    }

    JpaBudgetGuard(Budgets budgets, Usage usage, Clock clock) {
        this.budgets = budgets;
        this.usage = usage;
        this.clock = clock;
    }

    /** The first instant of the calendar month (UTC) that {@code now} falls in. */
    public static Instant monthStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The first instant of the day (UTC) that {@code now} falls in. */
    public static Instant dayStart(Instant now) {
        return now.truncatedTo(ChronoUnit.DAYS);
    }

    @Override
    @Transactional(readOnly = true)
    public Decision check(CallContext context, BigDecimal estimatedCost) {
        UUID orgId = UUID.fromString(context.orgId());
        Optional<Budget> maybeBudget = budgets.findForOrg(orgId);
        if (maybeBudget.isEmpty() || !maybeBudget.get().hasAnyCap()) {
            // No budget configured means no cap. A workspace that has not set one should not be
            // stopped by a limit it never chose.
            return Decision.allow(null);
        }
        Budget budget = maybeBudget.get();
        BigDecimal estimate = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        Instant now = clock.instant();

        // The most specific cap first, so the message names what actually stopped the call.
        if (budget.getPerRunCap() != null && context.runId() != null) {
            BigDecimal spent = usage.costForRun(orgId, UUID.fromString(context.runId()));
            if (spent.add(estimate).compareTo(budget.getPerRunCap()) > 0) {
                log.info(
                        "Run {} reached its spending limit of {}: {} spent, {} needed",
                        context.runId(),
                        budget.getPerRunCap(),
                        spent,
                        estimate);
                return refuse(
                        budget,
                        "This run reached its spending limit of " + money(budget.getPerRunCap()) + ".",
                        BigDecimal.ZERO.max(budget.getPerRunCap().subtract(spent)));
            }
        }

        if (budget.getPerAgentDailyCap() != null && context.agentId() != null) {
            BigDecimal spent =
                    usage.spendSinceByAgent(orgId, UUID.fromString(context.agentId()), dayStart(now));
            if (spent.add(estimate).compareTo(budget.getPerAgentDailyCap()) > 0) {
                log.info(
                        "Agent {} reached its daily spending limit of {}: {} spent today, {} needed",
                        context.agentId(),
                        budget.getPerAgentDailyCap(),
                        spent,
                        estimate);
                return refuse(
                        budget,
                        "This agent reached its daily spending limit of " + money(budget.getPerAgentDailyCap()) + ".",
                        BigDecimal.ZERO.max(budget.getPerAgentDailyCap().subtract(spent)));
            }
        }

        if (budget.getMonthlyCap() == null) {
            return Decision.allow(null);
        }
        BigDecimal remaining = BigDecimal.ZERO.max(budget.getMonthlyCap().subtract(monthToDate(orgId, now)));
        if (remaining.compareTo(estimate) < 0) {
            log.info("Workspace {} is at its monthly cap: {} remaining, {} needed", orgId, remaining, estimate);
            return refuse(
                    budget,
                    "The workspace has reached its monthly model budget of " + money(budget.getMonthlyCap()) + ".",
                    remaining);
        }
        return Decision.allow(remaining);
    }

    /**
     * Notes what an attempt actually cost, in the cached month-to-date figure.
     *
     * <p>Nothing is written: the attempt's own row in {@code llm_usage}, which the router writes
     * in its own transaction before this is called, is the record of it. Adding the cost to a
     * cached sum keeps that sum in step for the seconds it is reused.
     */
    @Override
    public void record(CallContext context, BigDecimal actualCost) {
        if (actualCost == null || actualCost.signum() <= 0 || context.orgId() == null) {
            return;
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(context.orgId());
        } catch (IllegalArgumentException e) {
            return;
        }
        monthSpend.computeIfPresent(
                orgId, (key, cached) -> new CachedSpend(cached.spent().add(actualCost), cached.loadedAt()));
    }

    /**
     * What the workspace has spent since the start of the month, as of now: always read from the
     * usage table, never from the cache, for what an administrator is shown.
     */
    public BigDecimal freshMonthToDate(UUID orgId) {
        Instant now = clock.instant();
        BigDecimal spent = usage.spendSince(orgId, monthStart(now));
        remember(orgId, spent, now);
        return spent;
    }

    private BigDecimal monthToDate(UUID orgId, Instant now) {
        CachedSpend cached = monthSpend.get(orgId);
        if (cached != null && cached.isFresh(now)) {
            return cached.spent();
        }
        BigDecimal spent = usage.spendSince(orgId, monthStart(now));
        remember(orgId, spent, now);
        return spent;
    }

    private void remember(UUID orgId, BigDecimal spent, Instant now) {
        if (monthSpend.size() >= MAX_CACHED_WORKSPACES) {
            monthSpend.values().removeIf(entry -> !entry.isFresh(now));
        }
        if (monthSpend.size() < MAX_CACHED_WORKSPACES) {
            monthSpend.put(orgId, new CachedSpend(spent, now));
        }
    }

    private static Decision refuse(Budget budget, String what, BigDecimal remaining) {
        String reason = what + RAISE_THE_CAP;
        return budget.degradesToSandbox()
                ? Decision.denyToSandbox(reason, remaining)
                : Decision.deny(reason, remaining);
    }

    /** A cap as dollars: at least two decimals, and as many more as it needs to be exact. */
    static String money(BigDecimal amount) {
        BigDecimal shown = amount.stripTrailingZeros();
        if (shown.scale() < 2) {
            shown = shown.setScale(2, java.math.RoundingMode.UNNECESSARY);
        }
        return "$" + shown.toPlainString();
    }
}
