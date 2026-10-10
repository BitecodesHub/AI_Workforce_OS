// @find: model router, LLM, model providers, token estimate, count tokens, context window check, pessimistic estimate, TokenEstimate
// @what: Cheap, deliberately pessimistic token estimate used before a call.
package os.aiworkforce.llm.model;

/**
 * A cheap, deliberately pessimistic token estimate.
 *
 * <p>Every provider tokenises differently, and none of them will tell us the count before the
 * call. The router needs a number anyway, to answer one question: can this conversation fit in
 * that model's window? Getting that wrong in the optimistic direction produces a failed call
 * after the latency has already been spent, so this estimate errs high on purpose.
 *
 * <p>It is not used for billing. Billing uses the counts the provider reports in its response,
 * which are authoritative.
 */
public final class TokenEstimate {

    /*
     * English prose runs near 4 characters per token. Code, JSON and non-Latin scripts run
     * denser - closer to 2.5 - and tool-heavy conversations are mostly JSON, so the divisor is
     * set for the dense case rather than the average.
     */
    private static final double CHARS_PER_TOKEN = 2.8;
    private static final int MINIMUM = 1;

    private TokenEstimate() {}

    public static int forText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(MINIMUM, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    /**
     * Estimate for a whole request, including the framing a tool definition costs.
     *
     * <p>Tool schemas are easy to forget and expensive: six tools with detailed JSON Schemas can
     * take more of the window than the conversation does.
     */
    public static int forRequest(ChatRequest request) {
        int messages = request.messages().stream()
                .mapToInt(ChatMessage::approximateTokens)
                .sum();
        int tools = request.tools().stream()
                .mapToInt(tool -> forText(tool.name()) + forText(tool.description()) + forText(tool.parametersJson()))
                .sum();
        return messages + tools + 8;
    }
}
