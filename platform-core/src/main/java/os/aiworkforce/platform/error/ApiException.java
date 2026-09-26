package os.aiworkforce.platform.error;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one exception type services throw on purpose.
 *
 * <p>Anything else escaping a controller is a defect and is reported as {@code internal_error}
 * with its detail kept out of the response. Keeping one type means the handler, the client and
 * the retry logic all reason about one shape.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;
    private final Duration retryAfter;
    private final boolean retryable;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null, null, null, code.retryable());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null, null, code.retryable());
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause, null, code.retryable());
    }

    public ApiException(
            ErrorCode code,
            String message,
            Map<String, Object> details,
            Throwable cause,
            Duration retryAfter,
            boolean retryable) {
        super(message == null ? code.defaultMessage() : message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.retryAfter = retryAfter;
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    public int status() {
        return code.status();
    }

    /**
     * Structured detail for the client: which field failed, which candidate was skipped, which
     * scope is missing. Never contains a secret, a stack trace or an internal host name.
     */
    public Map<String, Object> details() {
        return details;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    // ---- Builders ----------------------------------------------------------------------

    public ApiException with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return new ApiException(code, getMessage(), merged, getCause(), retryAfter, retryable);
    }

    public ApiException retryAfter(Duration duration) {
        return new ApiException(code, getMessage(), details, getCause(), duration, true);
    }

    // ---- Common failures, named at the call site ---------------------------------------

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND).with("resource", resource).with("id", String.valueOf(id));
    }

    public static ApiException validation(String field, String problem) {
        return new ApiException(ErrorCode.VALIDATION_FAILED).with("field", field).with("problem", problem);
    }

    public static ApiException permissionDenied(String permission) {
        return new ApiException(ErrorCode.PERMISSION_DENIED).with("requiredPermission", permission);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED).retryAfter(retryAfter);
    }

    public static ApiException internal(String message, Throwable cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, message, cause);
    }

    /**
     * Fills in a stack trace only when one is useful.
     *
     * <p>Expected outcomes - a 404, a denied permission, a rate limit - are raised at high volume
     * and their stack traces are noise that costs real time to collect. Server-side faults keep
     * theirs, because those are the ones somebody has to debug.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return code != null && code.status() >= 500 ? super.fillInStackTrace() : this;
    }
}
