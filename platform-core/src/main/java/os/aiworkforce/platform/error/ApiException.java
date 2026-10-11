// @find: api exception, deliberate error, throw error, error code, http status, problem response
// @what: The one exception type services throw on purpose, carrying an ErrorCode.
// @flow: Thrown by services; converted by GlobalExceptionHandler
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

    /**
     * Raises a failure with the code's default message and retry flag.
     */
    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null, null, null, code.retryable());
    }

    /**
     * Raises a failure with a specific message, which must be safe to show a person.
     */
    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null, null, code.retryable());
    }

    /**
     * Raises a failure and keeps the underlying cause for the server log.
     *
     * <p>The cause is never included in the response body.
     */
    public ApiException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause, null, code.retryable());
    }

    /**
     * The full constructor, used by the shorter ones and by the copying builders below.
     *
     * <p>A {@code null} message falls back to the code's default, and {@code null} details become an
     * empty map. The details are copied, so the exception is immutable once built.
     *
     * @param code the deliberate failure code
     * @param message sentence safe to show a person
     * @param details structured client detail, or {@code null}
     * @param cause underlying exception, or {@code null}
     * @param retryAfter suggested wait, or {@code null}
     * @param retryable whether repeating the identical request could succeed
     */
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

    /**
     * The machine-readable failure code.
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * The HTTP status taken from the code.
     */
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

    /**
     * How long a client should wait before repeating the request, or {@code null} when unknown.
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Whether repeating the identical request could succeed.
     *
     * <p>Defaults from the code; {@link #retryAfter(Duration)} forces it to true.
     */
    public boolean retryable() {
        return retryable;
    }

    // ---- Builders ----------------------------------------------------------------------

    /**
     * Returns a copy with one more detail entry.
     *
     * <p>Exceptions are immutable, so the original is unchanged. The value must be safe to send to
     * the client.
     */
    public ApiException with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return new ApiException(code, getMessage(), merged, getCause(), retryAfter, retryable);
    }

    /**
     * Returns a copy that carries a wait hint and is marked retryable.
     */
    public ApiException retryAfter(Duration duration) {
        return new ApiException(code, getMessage(), details, getCause(), duration, true);
    }

    // ---- Common failures, named at the call site ---------------------------------------

    /**
     * A 404 naming the resource kind and identifier.
     *
     * <p>The message is the generic one, so it does not reveal whether the item exists in another
     * workspace.
     */
    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND).with("resource", resource).with("id", String.valueOf(id));
    }

    /**
     * A 422 naming the offending field and a short problem statement.
     */
    public static ApiException validation(String field, String problem) {
        return new ApiException(ErrorCode.VALIDATION_FAILED)
                .with("field", field)
                .with("problem", problem);
    }

    /**
     * A 403 that names the permission code the caller lacks.
     */
    public static ApiException permissionDenied(String permission) {
        return new ApiException(ErrorCode.PERMISSION_DENIED).with("requiredPermission", permission);
    }

    /**
     * A 409 with a caller-supplied message.
     */
    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    /**
     * A 429 that tells the client how long to wait.
     */
    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED).retryAfter(retryAfter);
    }

    /**
     * A 500 that keeps the cause for the log and hides it from the response.
     */
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
