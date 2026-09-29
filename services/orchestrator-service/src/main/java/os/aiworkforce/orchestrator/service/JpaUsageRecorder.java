package os.aiworkforce.orchestrator.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.usage.UsageRecorder;
import os.aiworkforce.orchestrator.domain.LlmUsageRecord;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.orchestrator.repository.Usage;

/**
 * Writes down every attempt the router made.
 *
 * <p>Including the skips. "OpenRouter was never tried because it has no credential" is the answer
 * to most questions about why a run was slow or why an answer came from an unexpected model, and
 * it exists nowhere else.
 *
 * <p>Written in a separate transaction for the same reason the budget is: a run that fails must
 * still leave a record of what it spent and what it tried.
 *
 * <p>A successful attempt also clears a rejection recorded against that provider's key. The
 * router writes a rejection back the moment a provider refuses a key, and this is the one place
 * that sees every answer, so it is where "the key works again" is written back too. Without it a
 * key replaced after a rejection read as refused on the Model Routing page for good.
 */
@Service
public class JpaUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaUsageRecorder.class);

    private final Usage usage;
    private final Providers providers;

    public JpaUsageRecorder(Usage usage, Providers providers) {
        this.usage = usage;
        this.providers = providers;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orgId, String agentId, String runId, AttemptRecord attempt, BigDecimal cost) {
        LlmUsageRecord record = new LlmUsageRecord();
        record.setOrgId(UUID.fromString(orgId));
        record.setAgentId(agentId == null ? null : UUID.fromString(agentId));
        record.setRunId(runId == null ? null : UUID.fromString(runId));
        record.setProviderId(attempt.provider());
        record.setModelId(attempt.model());
        record.setOutcome(attempt.outcome().name());
        record.setFailure(attempt.failure() == null ? null : attempt.failure().name());
        record.setSkipReason(
                attempt.skipReason() == null ? null : attempt.skipReason().name());
        record.setPromptTokens(attempt.usage().promptTokens());
        record.setCachedTokens(attempt.usage().cachedPromptTokens());
        record.setCompletionTokens(attempt.usage().completionTokens());
        record.setCost(cost);
        record.setDurationMs(attempt.duration().toMillis());
        usage.save(record);

        if (attempt.outcome() == AttemptRecord.Outcome.SUCCEEDED
                && providers.clearRejectedCredential(attempt.provider(), Instant.now()) > 0) {
            log.info(
                    "Credential for provider {} was accepted again; its earlier rejection is cleared",
                    attempt.provider());
        }
    }
}
