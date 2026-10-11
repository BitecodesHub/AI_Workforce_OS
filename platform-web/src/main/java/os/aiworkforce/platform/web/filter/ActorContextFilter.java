// @find: actor context filter, request id, X-Request-Id, trace id, request context setup and teardown, first filter
// @what: First filter in the chain; sets request and trace identifiers and clears the context afterwards.
// @flow: Writes RequestContext; JwtActorConverter adds the actor later
package os.aiworkforce.platform.web.filter;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import os.aiworkforce.platform.context.RequestContext;

/**
 * Establishes the request context and tears it down again.
 *
 * <p>Runs before security so that an authentication failure is still logged against a request
 * identifier. The actor is attached later, by the authentication converter, once the token has
 * been verified.
 *
 * <p>The teardown in {@code finally} is the important part: the servlet container reuses threads,
 * and a context left behind would attach one request's actor to the next request that lands on
 * the same thread.
 *
 * <p>The trace id is read from the span Spring's server observation has already opened (it runs
 * just ahead of this filter), so the {@code traceId} on an error body is the one the trace
 * carries rather than always empty. When tracing is switched off there is no span and the field
 * stays empty.
 *
 * <p>Named for the actor rather than the request deliberately: Spring's own
 * {@code WebMvcAutoConfiguration} registers a bean called {@code requestContextFilter}, and a
 * class named to match it collides on bean name and stops the application starting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActorContextFilter extends OncePerRequestFilter {

    /** Header that carries the correlation id in a request and is echoed in the response. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Header a client sends so that a repeated write can be recognised and not applied twice. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Longest correlation or idempotency value accepted. */
    private static final int MAX_HEADER_LENGTH = 128;

    /** Absent when tracing is switched off; the filter then simply has no trace id to record. */
    private final ObjectProvider<Tracer> tracer;

    /** Creates the filter. The tracer is optional, so the filter works when tracing is disabled. */
    public ActorContextFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * Runs once per request.
     *
     * <p>Flow: takes the request id from the header when it passes {@code sanitise}, otherwise generates one;
     * records the idempotency key and trace id when present; fills the logging context with request id,
     * method and path; echoes the request id in the response header; then continues the chain. In all cases
     * it clears the logging and request contexts afterwards.
     *
     * <p>Side effects: thread-local state only; nothing is stored. Failures of the chain propagate unchanged.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String requestId = sanitise(request.getHeader(REQUEST_ID_HEADER));
            RequestContext.setRequestId(requestId != null ? requestId : RequestContext.newRequestId());

            String idempotencyKey = sanitise(request.getHeader(IDEMPOTENCY_KEY_HEADER));
            if (idempotencyKey != null) {
                RequestContext.setIdempotencyKey(idempotencyKey);
            }

            currentTraceId().ifPresent(RequestContext::setTraceId);

            MDC.put("requestId", RequestContext.requestId());
            MDC.put("method", request.getMethod());
            MDC.put("path", request.getRequestURI());

            // Echoed early so the caller can correlate even a response the handler never produced.
            response.setHeader(REQUEST_ID_HEADER, RequestContext.requestId());

            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            RequestContext.clear();
        }
    }

    /**
     * Returns the id of the active trace, or empty when tracing is off, no span is open or the tracer
     * fails.
     */
    private Optional<String> currentTraceId() {
        try {
            Tracer active = tracer.getIfAvailable();
            Span span = active == null ? null : active.currentSpan();
            String id = span == null ? null : span.context().traceId();
            return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (RuntimeException e) {
            // A tracing fault must never fail the request it was meant to describe.
            return Optional.empty();
        }
    }

    /**
     * Accepts an inbound correlation identifier only when it is safe to log.
     *
     * <p>A header is caller-controlled. Copying one unchecked into a log line invites forged line
     * breaks and unbounded entries, so anything oversized or containing control characters is
     * discarded and replaced with a fresh identifier.
     */
    private static String sanitise(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        return value;
    }
}
