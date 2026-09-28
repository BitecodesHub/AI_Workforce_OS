package os.aiworkforce.llm.model;

/**
 * Every way a call to a model provider can go wrong, and what to do about it.
 *
 * <p>Seven providers report the same underlying problem in seven shapes: a 429 here, a
 * {@code RESOURCE_EXHAUSTED} there, a {@code ThrottlingException} somewhere else. Each adapter
 * translates into this enum once, and every decision after that - retry, fail over, stop - is
 * made against these constants rather than against a vendor's status codes.
 *
 * <p>Two questions are answered per constant, and they are genuinely different:
 *
 * <ul>
 *   <li>{@link #retrySameCandidate()} - could the identical call to the identical model succeed
 *       shortly? True for a throttle or a blip.
 *   <li>{@link #tryNextCandidate()} - could a different model answer this? True for almost
 *       everything, but emphatically false for a content filter: asking another vendor the same
 *       blocked question is shopping for a provider with weaker safety, and it is both wasteful
 *       and wrong.
 * </ul>
 */
public enum ProviderFailure {

    /** Throttled. Honour {@code Retry-After} when present, then move on. */
    RATE_LIMITED(true, true, false),

    /** Provider-side fault: 500, 502, 503. Usually brief. */
    SERVER_ERROR(true, true, false),

    /** No response inside the deadline. The call may still be running at the provider. */
    TIMEOUT(true, true, false),

    /** Connection refused, reset, or DNS failure. The request never arrived. */
    NETWORK_ERROR(true, true, false),

    /** The provider is in a maintenance or overloaded state it has told us about. */
    OVERLOADED(true, true, false),

    /**
     * The credential was rejected.
     *
     * <p>Never retried: a key that is wrong now will be wrong in a second. The credential is
     * marked invalid, the breaker opens, and an operator is told - otherwise every request keeps
     * paying the latency of a call that cannot succeed.
     */
    AUTHENTICATION_FAILED(false, true, true),

    /** The credential is valid but not entitled to this model or this region. */
    AUTHORISATION_FAILED(false, true, true),

    /** The model name is unknown, retired, or not enabled on this account. */
    MODEL_NOT_FOUND(false, true, true),

    /** The prompt exceeds the model's window, despite the estimate. Compact, or go larger. */
    CONTEXT_LENGTH_EXCEEDED(false, true, false),

    /**
     * The provider's safety system refused.
     *
     * <p>Deliberately not failed over. The same prompt at another vendor is the same prompt, and
     * routing around a refusal is not a resilience strategy - it is evasion, and it hides a
     * genuine signal from the person who asked.
     */
    CONTENT_FILTERED(false, false, false),

    /** The request itself was malformed: a bad parameter, an unsupported combination. */
    INVALID_REQUEST(false, false, false),

    /** The model returned tool arguments that are not valid JSON. Repairable once. */
    MALFORMED_TOOL_CALL(true, true, false),

    /** The response could not be parsed at all. Usually a truncated body. */
    MALFORMED_RESPONSE(true, true, false),

    /** The stream ended early. Partial output is kept; the retry is non-streaming. */
    STREAM_INTERRUPTED(true, true, false),

    /** The account is out of credit, or a hard spend cap was hit at the provider. */
    QUOTA_EXHAUSTED(false, true, true),

    /**
     * The remaining credit does not cover this model's request (OpenRouter's 402).
     *
     * <p>Unlike {@link #QUOTA_EXHAUSTED} this is about one model, not the whole account: a cheaper
     * model on the same key may still answer, so the credential stays usable and only this model
     * is set aside for a while.
     */
    INSUFFICIENT_CREDIT(false, true, true),

    /** This Bedrock region is unavailable; other regions for the same model may work. */
    REGION_UNAVAILABLE(false, true, false),

    /** Anything an adapter could not classify. Treated cautiously: try elsewhere, do not repeat. */
    UNKNOWN(false, true, false);

    private final boolean retrySameCandidate;
    private final boolean tryNextCandidate;
    private final boolean operatorActionRequired;

    ProviderFailure(boolean retrySameCandidate, boolean tryNextCandidate, boolean operatorActionRequired) {
        this.retrySameCandidate = retrySameCandidate;
        this.tryNextCandidate = tryNextCandidate;
        this.operatorActionRequired = operatorActionRequired;
    }

    /** Whether repeating the identical call to the identical model could succeed shortly. */
    public boolean retrySameCandidate() {
        return retrySameCandidate;
    }

    /** Whether a different model in the chain should be tried. */
    public boolean tryNextCandidate() {
        return tryNextCandidate;
    }

    /**
     * Whether this needs a person, not a retry.
     *
     * <p>These raise an administrator notice and open the breaker, because they will not heal on
     * their own: an expired key, a retired model and an exhausted account all stay broken until
     * somebody changes something.
     */
    public boolean operatorActionRequired() {
        return operatorActionRequired;
    }

    /**
     * Whether the failure should count against the circuit breaker.
     *
     * <p>A refusal by the safety system and a malformed request are the platform's doing, not the
     * provider's. Counting them would let one badly-formed agent prompt open a breaker and take a
     * healthy provider out of rotation for everyone else in the workspace.
     */
    public boolean countsAgainstCircuit() {
        return this != CONTENT_FILTERED && this != INVALID_REQUEST && this != CONTEXT_LENGTH_EXCEEDED;
    }

    /** Maps to the platform's own error code when every candidate has been exhausted. */
    public os.aiworkforce.platform.error.ErrorCode toErrorCode() {
        return switch (this) {
            case RATE_LIMITED -> os.aiworkforce.platform.error.ErrorCode.RATE_LIMITED;
            case AUTHENTICATION_FAILED, AUTHORISATION_FAILED ->
                    os.aiworkforce.platform.error.ErrorCode.PROVIDER_CREDENTIAL_INVALID;
            case MODEL_NOT_FOUND -> os.aiworkforce.platform.error.ErrorCode.MODEL_NOT_FOUND;
            case CONTEXT_LENGTH_EXCEEDED -> os.aiworkforce.platform.error.ErrorCode.CONTEXT_LENGTH_EXCEEDED;
            case CONTENT_FILTERED -> os.aiworkforce.platform.error.ErrorCode.CONTENT_FILTERED;
            case MALFORMED_TOOL_CALL -> os.aiworkforce.platform.error.ErrorCode.TOOL_CALL_MALFORMED;
            case QUOTA_EXHAUSTED -> os.aiworkforce.platform.error.ErrorCode.BUDGET_EXCEEDED;
            case TIMEOUT -> os.aiworkforce.platform.error.ErrorCode.UPSTREAM_TIMEOUT;
            case INVALID_REQUEST -> os.aiworkforce.platform.error.ErrorCode.VALIDATION_FAILED;
            default -> os.aiworkforce.platform.error.ErrorCode.UPSTREAM_ERROR;
        };
    }
}
