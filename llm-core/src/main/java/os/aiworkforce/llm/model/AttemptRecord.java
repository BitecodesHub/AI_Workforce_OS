package os.aiworkforce.llm.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One attempt at one candidate, successful or not.
 *
 * <p>Recording the failures is the whole point. When a run answers three seconds late on a
 * secondary provider, the person reading the trace needs to see that the primary was rate limited
 * and the second had no credential - otherwise the platform looks slow for no reason and nobody
 * knows which vendor to chase.
 *
 * @param provider the provider tried
 * @param model the model tried
 * @param outcome how the attempt ended
 * @param skipReason why a candidate was not even tried, when {@code outcome} is {@code SKIPPED}
 * @param failure the classified failure, when the attempt was made and failed
 * @param message a sentence for the trace
 * @param startedAt when the attempt began
 * @param duration how long it took, including time spent waiting on a retry
 * @param usage what it consumed; a failed attempt can still cost tokens
 * @param httpStatus the provider's status code, where there was one
 */
public record AttemptRecord(
        String provider,
        String model,
        Outcome outcome,
        SkipReason skipReason,
        ProviderFailure failure,
        String message,
        Instant startedAt,
        Duration duration,
        TokenUsage usage,
        Integer httpStatus) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        /** Disqualified before the call, so it cost nothing. */
        SKIPPED
    }

    /**
     * Why a candidate was never tried.
     *
     * <p>Separate from {@link ProviderFailure} on purpose: a skip is a decision this platform
     * made, a failure is something the provider did. Conflating them would make a misconfigured
     * budget look like a vendor outage.
     */
    public enum SkipReason {
        PROVIDER_DISABLED,
        MODEL_DISABLED,
        CREDENTIAL_MISSING,
        CIRCUIT_OPEN,
        TOOLS_UNSUPPORTED,
        JSON_MODE_UNSUPPORTED,
        STREAMING_UNSUPPORTED,
        CONTEXT_TOO_SMALL,
        BUDGET_EXHAUSTED,
        RATE_LIMIT_COOLDOWN,
        REGION_UNAVAILABLE,
        MODEL_MARKED_UNAVAILABLE
    }

    public static AttemptRecord skipped(String provider, String model, SkipReason reason, String message) {
        return new AttemptRecord(
                provider,
                model,
                Outcome.SKIPPED,
                reason,
                null,
                message,
                Instant.now(),
                Duration.ZERO,
                TokenUsage.NONE,
                null);
    }

    public static AttemptRecord failed(
            String provider,
            String model,
            ProviderFailure failure,
            String message,
            Instant startedAt,
            Duration duration,
            Integer httpStatus,
            TokenUsage usage) {
        return new AttemptRecord(
                provider,
                model,
                Outcome.FAILED,
                null,
                failure,
                message,
                startedAt,
                duration,
                usage == null ? TokenUsage.NONE : usage,
                httpStatus);
    }

    public static AttemptRecord succeeded(
            String provider, String model, Instant startedAt, Duration duration, TokenUsage usage) {
        return new AttemptRecord(provider, model, Outcome.SUCCEEDED, null, null, null, startedAt, duration, usage, 200);
    }

    /** A short line for the run trace in the interface. */
    public String summary() {
        return switch (outcome) {
            case SUCCEEDED -> provider + "/" + model + " answered in " + duration.toMillis() + " ms";
            case SKIPPED -> provider + "/" + model + " skipped: " + humanise(skipReason.name());
            case FAILED -> provider + "/" + model + " failed: " + humanise(failure.name());
        };
    }

    private static String humanise(String constant) {
        return constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
