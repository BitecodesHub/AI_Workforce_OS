package os.aiworkforce.llm.budget;

import java.math.BigDecimal;

import os.aiworkforce.llm.router.ModelRouter.CallContext;

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
 *
 * <p>The whole call context is passed, not only the workspace: a cap per run needs the run, and a
 * cap per agent needs the agent. Either may be absent - the chat planner and the embedding path
 * act for no agent - and a guard treats an absent one as "that cap does not apply to this call".
 */
public interface BudgetGuard {

    /** Whether a call of roughly this cost may proceed. */
    Decision check(CallContext context, BigDecimal estimatedCost);

    /**
     * Records what an attempt actually cost.
     *
     * <p>Called for failed attempts too. A call that timed out after the provider generated most
     * of an answer is still billed by that provider, and a budget that ignores failures drifts
     * further from the invoice with every incident.
     */
    void record(CallContext context, BigDecimal actualCost);

    /**
     * @param allowed whether the call may proceed
     * @param reason why not, for the attempt record and the person who sees the run fail
     * @param remaining what is left in the period, for the interface
     * @param degradeToSandbox when refused, whether the workspace chose to fall back to the
     *     offline model at its cap rather than stop. Never a cheaper paid model: answering with a
     *     weaker model nobody chose changes the quality of the work without anybody being told.
     */
    record Decision(boolean allowed, String reason, BigDecimal remaining, boolean degradeToSandbox) {

        public Decision(boolean allowed, String reason, BigDecimal remaining) {
            this(allowed, reason, remaining, false);
        }

        public static Decision allow(BigDecimal remaining) {
            return new Decision(true, null, remaining, false);
        }

        /** Refused, and the call stops: the default at a cap. */
        public static Decision deny(String reason, BigDecimal remaining) {
            return new Decision(false, reason, remaining, false);
        }

        /** Refused, and the workspace chose the offline sandbox model for this case. */
        public static Decision denyToSandbox(String reason, BigDecimal remaining) {
            return new Decision(false, reason, remaining, true);
        }
    }

    /** Used where no budget is configured; every call is allowed and nothing is recorded. */
    BudgetGuard UNLIMITED = new BudgetGuard() {
        @Override
        public Decision check(CallContext context, BigDecimal estimatedCost) {
            return Decision.allow(null);
        }

        @Override
        public void record(CallContext context, BigDecimal actualCost) {
            /* Nothing to record without a budget. */
        }
    };
}
