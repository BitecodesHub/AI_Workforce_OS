package os.aiworkforce.orchestrator.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.repository.Budgets;

/**
 * Enforces a workspace's spending cap.
 *
 * <p>Checked before every attempt rather than once per run. A single agent working through a task
 * can make dozens of model calls, and a budget verified only at the start is not a budget: one
 * long run can spend a month's allowance between two checks.
 *
 * <p>The estimate is necessarily approximate - the true cost is known only after the provider
 * answers - so the guard rejects on the estimate and reconciles on the actual. A workspace can
 * overshoot slightly on its final call, which is accepted deliberately: the alternative is
 * blocking every request behind an exact accounting that cannot exist.
 */
@Service
public class JpaBudgetGuard implements BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(JpaBudgetGuard.class);

    private final Budgets budgets;

    public JpaBudgetGuard(Budgets budgets) {
        this.budgets = budgets;
    }

    @Override
    @Transactional(readOnly = true)
    public Decision check(String orgId, String agentId, BigDecimal estimatedCost) {
        Optional<Budget> maybeBudget = budgets.findForOrg(UUID.fromString(orgId));
        if (maybeBudget.isEmpty()) {
            // No budget configured means no cap. A workspace that has not set one should not be
            // stopped by a limit it never chose.
            return Decision.allow(null);
        }

        Budget budget = maybeBudget.get();
        BigDecimal remaining = budget.remaining();
        if (remaining == null) {
            return Decision.allow(null);
        }
        if (remaining.compareTo(estimatedCost) < 0) {
            log.info("Workspace {} is at its monthly cap: {} remaining, {} needed", orgId, remaining, estimatedCost);
            return Decision.deny(
                    "The workspace has reached its monthly model budget. Raise the cap in Settings to continue.",
                    remaining);
        }
        return Decision.allow(remaining);
    }

    /**
     * Records what an attempt actually cost.
     *
     * <p>Runs in its own transaction so a failed run still records what it spent. A provider bills
     * for a call that timed out after generating most of an answer, and rolling that back with the
     * run would make the platform's figures drift from the invoice.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orgId, String agentId, BigDecimal actualCost) {
        if (actualCost == null || actualCost.signum() <= 0) {
            return;
        }
        budgets.findForOrg(UUID.fromString(orgId)).ifPresent(budget -> {
            budget.spend(actualCost);
            budgets.save(budget);
        });
    }

    /** Creates a budget row for a workspace that has none, so a cap can be set in the console. */
    @Transactional
    public Budget ensureFor(UUID orgId) {
        return budgets.findForOrg(orgId).orElseGet(() -> {
            Budget budget = new Budget();
            budget.setId(orgId);
            budget.setPeriodStartedAt(Instant.now());
            return budgets.save(budget);
        });
    }
}
