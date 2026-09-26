package os.aiworkforce.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The error body every service returns, shaped after RFC 7807.
 *
 * <p>One shape across eight services means the web client renders any failure without a special
 * case, and a caller can decide whether to retry from {@code retryable} rather than by matching
 * on status codes.
 *
 * @param type stable documentation link for the code
 * @param title short human-readable summary of the code
 * @param status HTTP status, repeated in the body so logs and captures are self-describing
 * @param code the machine-readable {@link ErrorCode}
 * @param detail one sentence safe to show a person
 * @param errors structured, field-level or candidate-level detail
 * @param retryable whether repeating the identical request could succeed
 * @param retryAfterSeconds how long to wait before repeating it
 * @param instance the path that produced the failure
 * @param requestId correlates the response with the service logs
 * @param traceId correlates the response with the distributed trace
 * @param timestamp when the failure was produced
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemResponse(
        String type,
        String title,
        int status,
        String code,
        String detail,
        Map<String, Object> errors,
        boolean retryable,
        Long retryAfterSeconds,
        String instance,
        String requestId,
        String traceId,
        Instant timestamp) {

    private static final String TYPE_PREFIX = "https://errors.aiworkforce.os/";

    public static ProblemResponse of(
            ApiException exception, String instance, String requestId, String traceId) {
        ErrorCode code = exception.code();
        return new ProblemResponse(
                TYPE_PREFIX + code.wire(),
                code.wire().replace('_', ' '),
                code.status(),
                code.wire(),
                exception.getMessage(),
                exception.details().isEmpty() ? null : exception.details(),
                exception.retryable(),
                exception.retryAfter() == null ? null : exception.retryAfter().toSeconds(),
                instance,
                requestId,
                traceId,
                Instant.now());
    }
}
