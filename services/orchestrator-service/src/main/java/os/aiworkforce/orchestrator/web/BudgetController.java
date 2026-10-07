package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.repository.Budgets;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaBudgetGuard;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * What a workspace may spend on language models, and what it has spent so far.
 *
 * <p>Reading it needs {@code budget:read} and changing it needs {@code budget:manage}, which only
 * administrators and managers hold: a cap is a purchasing decision, and one a person without that
 * authority could lift would not be a cap. Every change is audited with the values before and after.
 *
 * <p>Spend is read from the usage table every time, never from a counter, so what is shown here is
 * what the guard enforces. All amounts are estimated US dollars from the model catalogue's prices,
 * not what a provider's invoice says, and every figure here says so.
 */
@RestController
@RequestMapping("/api/orchestrator/budget")
@Tag(name = "Budget")
public class BudgetController {

    /** What every amount in this API is, said once so no client has to guess. */
    public static final String BASIS = "estimated USD from catalogue prices";

    /** Well under what the column can hold, and far above any real cap. */
    static final BigDecimal MAX_CAP = new BigDecimal("1000000000");

    /** Early in the month a day's spend says little, so a projection is never made from less than a day. */
    private static final BigDecimal MIN_ELAPSED_DAYS = BigDecimal.ONE;

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private final Budgets budgets;
    private final JpaBudgetGuard guard;
    private final AuditClient audit;
    private final Clock clock;

    @Autowired
    public BudgetController(Budgets budgets, JpaBudgetGuard guard, AuditClient audit) {
        this(budgets, guard, audit, Clock.systemUTC());
    }

    BudgetController(Budgets budgets, JpaBudgetGuard guard, AuditClient audit, Clock clock) {
        this.budgets = budgets;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param monthlyCap what the workspace may spend in a calendar month (UTC); absent for no cap
     * @param perRunCap what one run may spend; absent for no cap
     * @param perAgentDailyCap what one agent may spend in a day (UTC); absent for no cap
     * @param onExhausted {@code stop} or {@code sandbox}: what happens when a cap is reached
     * @param spentThisMonth what has been spent since {@code periodStart}
     * @param remaining what is left of the monthly cap, never below zero; absent with no monthly cap
     * @param projectedMonthEnd what the month would come to at the pace so far
     * @param periodStart the first instant of the month the figures are for
     * @param basis what the amounts are: estimates from catalogue prices
     */
    public record BudgetView(
            BigDecimal monthlyCap,
            BigDecimal perRunCap,
            BigDecimal perAgentDailyCap,
            String onExhausted,
            BigDecimal spentThisMonth,
            BigDecimal remaining,
            BigDecimal projectedMonthEnd,
            Instant periodStart,
            String basis) {}

    /**
     * The whole budget, replaced. A cap left out is removed, so a client sends every cap it wants
     * to keep; {@code onExhausted} left out keeps what the workspace has.
     */
    public record BudgetRequest(
            @DecimalMin("0") @DecimalMax("1000000000") BigDecimal monthlyCap,
            @DecimalMin("0") @DecimalMax("1000000000") BigDecimal perRunCap,
            @DecimalMin("0") @DecimalMax("1000000000") BigDecimal perAgentDailyCap,
            @Pattern(regexp = "stop|sandbox") String onExhausted) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.BUDGET_READ)
    @Transactional(readOnly = true)
    @Operation(summary = "The workspace's spending caps, what has been spent this month, and where it is heading")
    public BudgetView get() {
        UUID orgId = orgId();
        Budget budget = budgets.findForOrg(orgId).orElseGet(Budget::new);
        return view(budget, guard.freshMonthToDate(orgId));
    }

    @PutMapping
    @RequiresPermission(Permission.Codes.BUDGET_MANAGE)
    @Transactional
    @Operation(summary = "Set the workspace's spending caps")
    public BudgetView update(@Valid @RequestBody BudgetRequest request) {
        BigDecimal monthly = cap("monthlyCap", "The monthly cap", request.monthlyCap());
        BigDecimal perRun = cap("perRunCap", "The cap per run", request.perRunCap());
        BigDecimal perAgentDaily = cap("perAgentDailyCap", "The daily cap per agent", request.perAgentDailyCap());
        String onExhausted = request.onExhausted();
        if (onExhausted != null && !Budget.ON_EXHAUSTED_STOP.equals(onExhausted)
                && !Budget.ON_EXHAUSTED_SANDBOX.equals(onExhausted)) {
            throw ApiException.validation("onExhausted", "Choose stop or sandbox.");
        }

        UUID orgId = orgId();
        // A first cap creates the row; two administrators saving at once both find it afterwards.
        budgets.insertIfAbsent(orgId);
        Budget budget = budgets.findForOrg(orgId).orElseThrow(() -> ApiException.notFound("budget", orgId));

        Map<String, Object> before = snapshot(budget);
        budget.setMonthlyCap(monthly);
        budget.setPerRunCap(perRun);
        budget.setPerAgentDailyCap(perAgentDaily);
        if (onExhausted != null) {
            budget.setOnExhausted(onExhausted);
        }
        budgets.save(budget);
        Map<String, Object> after = snapshot(budget);

        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("old", before);
        detail.put("new", after);
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "budget.update", "budget", orgId.toString(), "succeeded", detail));

        return view(budget, guard.freshMonthToDate(orgId));
    }

    /** The caps as the audit trail records them, as plain text so an exact value is never rounded in transit. */
    private static Map<String, Object> snapshot(Budget budget) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("monthlyCap", text(budget.getMonthlyCap()));
        values.put("perRunCap", text(budget.getPerRunCap()));
        values.put("perAgentDailyCap", text(budget.getPerAgentDailyCap()));
        values.put("onExhausted", budget.getOnExhausted());
        return values;
    }

    private static String text(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    /**
     * A cap that is absent, or not negative and not past what the column can hold, to the four
     * decimals it is stored in. Checked here as well as by the request's own constraints, so the
     * rule holds for every caller of this method.
     */
    private static BigDecimal cap(String field, String name, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiException.validation(field, name + " cannot be negative.");
        }
        if (value.compareTo(MAX_CAP) > 0) {
            throw ApiException.validation(field, name + " is higher than the largest cap that can be set.");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BudgetView view(Budget budget, BigDecimal spent) {
        Instant now = clock.instant();
        Instant periodStart = JpaBudgetGuard.monthStart(now);
        BigDecimal remaining = budget.getMonthlyCap() == null
                ? null
                : BigDecimal.ZERO.max(budget.getMonthlyCap().subtract(spent));
        return new BudgetView(
                budget.getMonthlyCap(),
                budget.getPerRunCap(),
                budget.getPerAgentDailyCap(),
                budget.getOnExhausted(),
                spent,
                remaining,
                projectMonthEnd(spent, periodStart, now),
                periodStart,
                BASIS);
    }

    /**
     * Spend so far, divided by the days elapsed and multiplied by the days in the month. Never
     * less than what has been spent, and never made from less than one day of history.
     */
    static BigDecimal projectMonthEnd(BigDecimal spent, Instant periodStart, Instant now) {
        BigDecimal elapsedDays = BigDecimal.valueOf(Duration.between(periodStart, now).toSeconds())
                .divide(SECONDS_PER_DAY, 6, RoundingMode.HALF_UP)
                .max(MIN_ELAPSED_DAYS);
        int daysInMonth = periodStart.atZone(ZoneOffset.UTC).toLocalDate().lengthOfMonth();
        BigDecimal projected = spent.multiply(BigDecimal.valueOf(daysInMonth))
                .divide(elapsedDays, 4, RoundingMode.HALF_UP);
        return projected.max(spent.setScale(4, RoundingMode.HALF_UP));
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
