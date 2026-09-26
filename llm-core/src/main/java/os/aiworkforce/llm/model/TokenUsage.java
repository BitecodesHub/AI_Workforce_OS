package os.aiworkforce.llm.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one attempt consumed, as the provider reported it.
 *
 * <p>Cached and reasoning tokens are tracked separately because they are priced separately: a
 * cached prompt token can cost a tenth of a fresh one, and a reasoning model can spend far more
 * on thinking than on the answer. Folding them together would make a budget wrong in both
 * directions.
 *
 * @param promptTokens tokens in the prompt, including cached ones
 * @param cachedPromptTokens the subset served from the provider's prompt cache
 * @param completionTokens tokens in the answer
 * @param reasoningTokens tokens spent on internal reasoning, where the provider reports them
 */
public record TokenUsage(int promptTokens, int cachedPromptTokens, int completionTokens, int reasoningTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    public static TokenUsage of(int promptTokens, int completionTokens) {
        return new TokenUsage(promptTokens, 0, completionTokens, 0);
    }

    public int totalTokens() {
        return promptTokens + completionTokens + reasoningTokens;
    }

    public int freshPromptTokens() {
        return Math.max(0, promptTokens - cachedPromptTokens);
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                promptTokens + other.promptTokens,
                cachedPromptTokens + other.cachedPromptTokens,
                completionTokens + other.completionTokens,
                reasoningTokens + other.reasoningTokens);
    }

    /**
     * Cost in the platform's accounting currency, from per-million-token rates.
     *
     * <p>Computed in {@link BigDecimal}, not double. Fractions of a cent accumulated across
     * millions of calls stop being a rounding curiosity and start being a wrong invoice.
     */
    public BigDecimal cost(BigDecimal inputPerMillion, BigDecimal cachedPerMillion, BigDecimal outputPerMillion) {
        BigDecimal million = new BigDecimal("1000000");
        BigDecimal fresh = BigDecimal.valueOf(freshPromptTokens())
                .multiply(nullToZero(inputPerMillion))
                .divide(million, 10, RoundingMode.HALF_UP);
        BigDecimal cached = BigDecimal.valueOf(cachedPromptTokens)
                .multiply(cachedPerMillion == null ? nullToZero(inputPerMillion) : cachedPerMillion)
                .divide(million, 10, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(completionTokens + reasoningTokens)
                .multiply(nullToZero(outputPerMillion))
                .divide(million, 10, RoundingMode.HALF_UP);
        return fresh.add(cached).add(output).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
