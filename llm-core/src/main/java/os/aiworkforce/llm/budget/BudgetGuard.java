package os.aiworkforce.llm.budget;

import java.math.BigDecimal;

/**
 * Decides whether a call may be made, and records what it cost.
 *
 * <p>Checked before every attempt, not once per run. A single run can make dozens of model calls
 * as an agent works through a task, and a budget verified only at the start is not a budget: one
 * long-running agent can spend a month's allowance between two checks.
 *
 * <p>The estimate is necessarily approximate, because the true cost is known only after the
 * provider answers. The guard therefore rejects on the estimate and reconciles on the actual, and
 * a workspace that overshoots slightly on its final call is accepted as the cost of not blocking
 * every request behind an exact accounting.
 */
public interface BudgetGuard {

    /** Whether a call of roughly this cost may proceed. */
    Decision check(String orgId, String agentId, BigDecimal estimatedCost);

    /**
     * Records what an attempt actually cost.
     *
     * <p>Called for failed attempts too. A call that timed out after the provider generated most
     * of an answer is still billed by that provider, and a budget that ignores failures drifts
     * further from the invoice with every incident.
     */
    void record(String orgId, String agentId, BigDecimal actualCost);

    /**
     * @param allowed whether the call may proceed
     * @param reason why not, for the attempt record
     * @param remaining what is left in the period, for the interface
     */
    record Decision(boolean allowed, String reason, BigDecimal remaining) {

        public static Decision allow(BigDecimal remaining) {
            return new Decision(true, null, remaining);
        }

        public static Decision deny(String reason, BigDecimal remaining) {
            return new Decision(false, reason, remaining);
        }
    }

    /** Used where no budget is configured; every call is allowed and nothing is recorded. */
    BudgetGuard UNLIMITED = new BudgetGuard() {
        @Override
        public Decision check(String orgId, String agentId, BigDecimal estimatedCost) {
            return Decision.allow(null);
        }

        @Override
        public void record(String orgId, String agentId, BigDecimal actualCost) {
            /* Nothing to record without a budget. */
        }
    };
}
