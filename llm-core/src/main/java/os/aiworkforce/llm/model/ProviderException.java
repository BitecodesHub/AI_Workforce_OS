package os.aiworkforce.llm.model;

import java.time.Duration;

/**
 * A classified failure from one provider.
 *
 * <p>Thrown by an adapter and caught by the router, which decides from {@link #failure()} whether
 * to retry, move to the next candidate, or stop. The raw provider message is carried for the
 * trace but never reaches an end user: vendor error text quotes prompts back, and prompts contain
 * company data.
 */
public class ProviderException extends RuntimeException {

    private final ProviderFailure failure;
    private final String provider;
    private final String model;
    private final Integer httpStatus;
    private final Duration retryAfter;
    private final transient String rawBody;

    public ProviderException(
            ProviderFailure failure,
            String provider,
            String model,
            String message,
            Integer httpStatus,
            Duration retryAfter,
            String rawBody,
            Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.provider = provider;
        this.model = model;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.rawBody = rawBody;
    }

    public static ProviderException of(ProviderFailure failure, String provider, String model, String message) {
        return new ProviderException(failure, provider, model, message, null, null, null, null);
    }

    public static ProviderException of(
            ProviderFailure failure, String provider, String model, String message, Throwable cause) {
        return new ProviderException(failure, provider, model, message, null, null, null, cause);
    }

    public ProviderFailure failure() {
        return failure;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** How long the provider asked us to wait, when it said so. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The provider's raw response, truncated, for the trace only. */
    public String rawBody() {
        return rawBody;
    }

    /** Stack traces are noise for an expected upstream outcome and cost real time to collect. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
