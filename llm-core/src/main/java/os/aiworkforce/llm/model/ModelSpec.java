package os.aiworkforce.llm.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One model offered by one provider, as stored in the database.
 *
 * <p>Everything the router needs to decide whether a model is a viable candidate lives here, so
 * adding a newly released model is a row rather than a code change. That matters more than it
 * sounds: models are retired on a few weeks' notice, and a platform that hard-codes their names
 * breaks on somebody else's schedule.
 *
 * @param providerId the provider offering it
 * @param modelId the identifier sent on the wire
 * @param displayName what the console shows
 * @param contextWindowTokens total prompt plus completion budget
 * @param maxOutputTokens the most it will generate in one turn
 * @param supportsTools whether it can call tools
 * @param supportsJsonMode whether it can be constrained to valid JSON
 * @param supportsStreaming whether it streams
 * @param supportsVision whether it accepts images
 * @param inputCostPerMillion price per million prompt tokens
 * @param cachedInputCostPerMillion price per million cached prompt tokens
 * @param outputCostPerMillion price per million completion tokens
 * @param enabled whether the router may consider it
 * @param unavailableUntilEpochMs set when a provider reported the model retired or missing
 */
public record ModelSpec(
        String providerId,
        String modelId,
        String displayName,
        int contextWindowTokens,
        int maxOutputTokens,
        boolean supportsTools,
        boolean supportsJsonMode,
        boolean supportsStreaming,
        boolean supportsVision,
        BigDecimal inputCostPerMillion,
        BigDecimal cachedInputCostPerMillion,
        BigDecimal outputCostPerMillion,
        boolean enabled,
        Long unavailableUntilEpochMs) {

    public ModelSpec {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive for " + modelId);
        }
    }

    public String key() {
        return providerId + "/" + modelId;
    }

    /**
     * Whether this model can hold the prompt and still leave room to answer.
     *
     * <p>Checking the prompt alone is the mistake worth avoiding: a prompt that fits exactly
     * leaves no budget for a reply, and the call returns an empty answer that looks like a model
     * fault rather than a sizing error.
     */
    public boolean canHold(int estimatedPromptTokens, Integer requestedOutputTokens) {
        int output = requestedOutputTokens != null ? requestedOutputTokens : Math.min(maxOutputTokens, 1024);
        // Ten per cent headroom absorbs the difference between our estimate and the provider's
        // own tokeniser, which is always a little different and occasionally a lot.
        int needed = (int) Math.ceil((estimatedPromptTokens + output) * 1.10);
        return needed <= contextWindowTokens;
    }

    public boolean isCurrentlyUnavailable() {
        return unavailableUntilEpochMs != null && System.currentTimeMillis() < unavailableUntilEpochMs;
    }

    /** Marks the model unavailable for a period, after the provider said it does not exist. */
    public ModelSpec markUnavailableFor(java.time.Duration duration) {
        return new ModelSpec(
                providerId,
                modelId,
                displayName,
                contextWindowTokens,
                maxOutputTokens,
                supportsTools,
                supportsJsonMode,
                supportsStreaming,
                supportsVision,
                inputCostPerMillion,
                cachedInputCostPerMillion,
                outputCostPerMillion,
                enabled,
                System.currentTimeMillis() + duration.toMillis());
    }

    /** Estimated cost of a call before it is made, for the budget check. */
    public BigDecimal estimateCost(int promptTokens, int expectedOutputTokens) {
        return new TokenUsage(promptTokens, 0, expectedOutputTokens, 0)
                .cost(inputCostPerMillion, cachedInputCostPerMillion, outputCostPerMillion);
    }
}
