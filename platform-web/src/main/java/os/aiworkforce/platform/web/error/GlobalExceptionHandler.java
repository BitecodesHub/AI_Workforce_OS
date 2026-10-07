package os.aiworkforce.platform.web.error;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.error.ProblemResponse;

/**
 * Turns every failure into one response shape.
 *
 * <p>Two rules govern what reaches the caller. First, a 5xx never carries its cause: an exception
 * message can hold a connection string, a row's contents or an internal host name, so the client
 * receives the code and the request identifier and the detail stays in the log. Second, a 4xx
 * carries enough structure for the interface to point at the field that is wrong, because an
 * error a person cannot act on wastes their time.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemResponse> handleApi(ApiException e, HttpServletRequest request) {
        logDeliberate(e, request);
        return respond(e, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleBeanValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, Object> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "is not valid" : error.getDefaultMessage(),
                        (first, second) -> first,
                        LinkedHashMap::new));
        e.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> fields.put(error.getObjectName(), error.getDefaultMessage()));
        return respond(
                new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.defaultMessage(),
                        fields,
                        null,
                        null,
                        false),
                request);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ProblemResponse> handleConstraint(
            jakarta.validation.ConstraintViolationException e, HttpServletRequest request) {
        Map<String, Object> fields = e.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        jakarta.validation.ConstraintViolation::getMessage,
                        (first, second) -> first,
                        LinkedHashMap::new));
        return respond(new ApiException(ErrorCode.VALIDATION_FAILED, null, fields, null, null, false), request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemResponse> handleMalformed(Exception e, HttpServletRequest request) {
        // The parser's own message can quote the request body, so it is logged, not returned.
        log.debug("Malformed request to {}: {}", request.getRequestURI(), e.getMessage());
        return respond(new ApiException(ErrorCode.MALFORMED_REQUEST), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemResponse> handleMediaType(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemResponse> handleMethod(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.METHOD_NOT_ALLOWED), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemResponse> handleTooLarge(
            MaxUploadSizeExceededException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.PAYLOAD_TOO_LARGE), request);
    }

    /**
     * A path with no handler is a 404, not a server fault.
     *
     * <p>Spring 6 raises {@code NoResourceFoundException} for an unmatched path rather than
     * {@code NoHandlerFoundException}, and without this branch it falls through to the catch-all
     * below and is reported as an internal error. That turns "you asked for a route that does not
     * exist" into "the platform is broken", which sends somebody debugging the wrong thing.
     */
    @ExceptionHandler({
        NoHandlerFoundException.class,
        org.springframework.web.servlet.resource.NoResourceFoundException.class
    })
    public ResponseEntity<ProblemResponse> handleNoHandler(Exception e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.NOT_FOUND), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemResponse> handleAuthentication(AuthenticationException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.NOT_AUTHENTICATED), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemResponse> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.PERMISSION_DENIED), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemResponse> handleOptimisticLock(
            OptimisticLockingFailureException e, HttpServletRequest request) {
        return respond(new ApiException(ErrorCode.VERSION_CONFLICT), request);
    }

    /**
     * A unique constraint is the database telling us two requests raced.
     *
     * <p>It is reported as a conflict rather than a server fault, because the caller can act on
     * it: reload and retry. The constraint name stays in the log, since it names internal schema.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemResponse> handleIntegrity(
            DataIntegrityViolationException e, HttpServletRequest request) {
        log.warn("Constraint violation on {}: {}", request.getRequestURI(), rootMessage(e));
        return respond(new ApiException(ErrorCode.ALREADY_EXISTS), request);
    }

    /**
     * Anything not handled above is a defect.
     *
     * <p>It is logged at error with its full stack and the request identifier, and the caller gets
     * that identifier and nothing else. Support can find the incident from the identifier without
     * the response ever describing the platform's internals.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemResponse> handleUnexpected(Throwable e, HttpServletRequest request) {
        log.error(
                "Unhandled failure on {} {} (requestId={})",
                request.getMethod(),
                request.getRequestURI(),
                RequestContext.requestId(),
                e);
        return respond(new ApiException(ErrorCode.INTERNAL_ERROR), request);
    }

    private void logDeliberate(ApiException e, HttpServletRequest request) {
        if (e.status() >= 500) {
            // The request identifier is the reference a person reads from the error screen, so it is
            // in the message itself and not only in the log's MDC fields.
            log.error(
                    "{} on {} {} (requestId={})",
                    e.code().wire(),
                    request.getMethod(),
                    request.getRequestURI(),
                    RequestContext.requestId(),
                    e);
        } else if (e.status() == 403 || e.status() == 401) {
            // Security decisions are logged at info even when routine: an access-denied pattern
            // is the signal an operator looks for after an incident.
            log.info(
                    "{} on {} {} for actor {}",
                    e.code().wire(),
                    request.getMethod(),
                    request.getRequestURI(),
                    RequestContext.actor().map(a -> a.id()).orElse("anonymous"));
        } else {
            log.debug("{} on {} {}", e.code().wire(), request.getMethod(), request.getRequestURI());
        }
    }

    private ResponseEntity<ProblemResponse> respond(ApiException e, HttpServletRequest request) {
        ProblemResponse body = ProblemResponse.of(
                e,
                request.getRequestURI(),
                RequestContext.requestId(),
                RequestContext.traceId().orElse(null));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status())
                .header("Content-Type", "application/problem+json")
                .header("X-Request-Id", RequestContext.requestId());
        Duration retryAfter = e.retryAfter();
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfter.toSeconds())));
        }
        return response.body(body);
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
