// @find: tests for actor context filter, request id echoed, trace id in error body, context cleared
// @what: Checks the first filter records the request id and trace id and clears the context.
package os.aiworkforce.platform.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import os.aiworkforce.platform.context.RequestContext;

/**
 * What the first filter in the chain records for the work that follows it: the request's own
 * reference, echoed back, and the trace the request belongs to.
 *
 * <p>The trace id used to be left empty on every error body, so the identifier a support person
 * asked for named nothing in the tracing system.
 */
class ActorContextFilterTest {

    @AfterEach
    void clean() {
        MDC.clear();
        RequestContext.clear();
    }

    private static ObjectProvider<Tracer> tracerWith(String traceId) {
        Tracer tracer = mock(Tracer.class);
        if (traceId != null) {
            Span span = mock(Span.class);
            TraceContext context = mock(TraceContext.class);
            when(context.traceId()).thenReturn(traceId);
            when(span.context()).thenReturn(context);
            when(tracer.currentSpan()).thenReturn(span);
        }
        return provider(tracer);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> provider(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return provider;
    }

    /** What the filter put in place while the rest of the chain ran. */
    private record Seen(String requestId, Optional<String> traceId, String mdcRequestId, String echoed) {}

    private static Seen runThrough(ActorContextFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Seen> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(new Seen(
                RequestContext.requestId(),
                RequestContext.traceId(),
                MDC.get("requestId"),
                response.getHeader(ActorContextFilter.REQUEST_ID_HEADER)));
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    @Test
    @DisplayName("the trace id of the current span is what the request context and an error body carry")
    void recordsTheTraceId() throws Exception {
        ActorContextFilter filter = new ActorContextFilter(tracerWith("4bf92f3577b34da6a3ce929d0e0e4736"));

        Seen seen = runThrough(filter, new MockHttpServletRequest("GET", "/api/runs"));

        assertThat(seen.traceId()).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("with no span, as when tracing is off, there is simply no trace id and the request goes on")
    void noSpanNoTraceId() throws Exception {
        Seen withoutSpan = runThrough(new ActorContextFilter(tracerWith(null)), new MockHttpServletRequest());
        assertThat(withoutSpan.traceId()).isEmpty();

        Seen withoutTracer = runThrough(new ActorContextFilter(provider(null)), new MockHttpServletRequest());
        assertThat(withoutTracer.traceId()).isEmpty();
    }

    @Test
    @DisplayName("a tracer that fails does not fail the request")
    void aBrokenTracerIsHarmless() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenThrow(new IllegalStateException("exporter down"));

        Seen seen = runThrough(new ActorContextFilter(provider(tracer)), new MockHttpServletRequest());

        assertThat(seen.traceId()).isEmpty();
        assertThat(seen.requestId()).isNotBlank();
    }

    @Test
    @DisplayName("the request id is in the logging context, echoed to the caller, and gone when the request ends")
    void requestIdIsLoggedEchoedAndCleared() throws Exception {
        ActorContextFilter filter = new ActorContextFilter(tracerWith(null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/goals");
        request.addHeader(ActorContextFilter.REQUEST_ID_HEADER, "ref-123");

        Seen seen = runThrough(filter, request);

        assertThat(seen.requestId()).isEqualTo("ref-123");
        assertThat(seen.mdcRequestId()).isEqualTo("ref-123");
        assertThat(seen.echoed()).isEqualTo("ref-123");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("a request id that is not safe to log is replaced rather than copied into the log")
    void unsafeRequestIdIsReplaced() throws Exception {
        ActorContextFilter filter = new ActorContextFilter(tracerWith(null));
        List<String> unsafe = new ArrayList<>(List.of("a b", "x".repeat(200), "ref\u0000"));
        for (String header : unsafe) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ActorContextFilter.REQUEST_ID_HEADER, header);

            Seen seen = runThrough(filter, request);

            assertThat(seen.requestId()).isNotEqualTo(header).matches("[0-9a-f]{32}");
        }
    }
}
