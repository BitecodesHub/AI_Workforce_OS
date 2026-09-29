package os.aiworkforce.platform.web.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
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
 * <p>Named for the actor rather than the request deliberately: Spring's own
 * {@code WebMvcAutoConfiguration} registers a bean called {@code requestContextFilter}, and a
 * class named to match it collides on bean name and stops the application starting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActorContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final int MAX_HEADER_LENGTH = 128;

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
