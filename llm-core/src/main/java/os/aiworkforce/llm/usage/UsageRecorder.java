// @find: model router, LLM, model providers, usage recording, record every attempt, cost tracking, failed attempts, UsageRecorder
// @what: Interface that writes down every model attempt, successful or not.
// @flow: Called by ModelRouter.
package os.aiworkforce.llm.usage;

import java.math.BigDecimal;

import os.aiworkforce.llm.model.AttemptRecord;

/**
 * Writes down every attempt, successful or not.
 *
 * <p>Recording only the successes would make the platform look cheaper and faster than it is: the
 * retries, the throttles and the failovers are exactly the activity an operator needs to see in
 * order to fix a routing policy. The analytics service reads these rows to build the provider
 * health panel and the spend report.
 */
public interface UsageRecorder {

    /**
     * Stores one attempt and its cost.
     *
     * <p>Called by {@code ModelRouter} after every attempt, including failed, throttled and skipped
     * ones. The implementation lives outside llm-core and performs a database write; it is expected
     * not to throw, because a failure to record must not fail the person's model call.
     *
     * @param orgId the workspace the call was made for
     * @param agentId the agent acting, or null where none acts
     * @param runId the run the call belongs to, or null outside a run
     * @param attempt what happened on this attempt
     * @param cost the cost attributed to this attempt, possibly zero
     */
    void record(String orgId, String agentId, String runId, AttemptRecord attempt, BigDecimal cost);

    /** Used in tests and where accounting is not wanted. */
    UsageRecorder NONE = (orgId, agentId, runId, attempt, cost) -> {
        /* Deliberately does nothing. */
    };
}
