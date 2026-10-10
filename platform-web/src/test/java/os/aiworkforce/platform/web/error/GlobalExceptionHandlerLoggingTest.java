// @find: tests for exception handler logging, request reference in log, 5xx error reference, log line matches error screen
// @what: Checks the reference shown on an error screen can be found in the log.
package os.aiworkforce.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.error.ProblemResponse;

/**
 * The reference a person reads from an error screen has to be findable in the log.
 *
 * <p>The unhandled-failure branch already printed it; the branch for an error the code raised on
 * purpose (a 5xx {@link ApiException}) did not, so the reference on its error screen matched no
 * log line unless the log happened to print the logging context.
 */
class GlobalExceptionHandlerLoggingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        RequestContext.setRequestId("ref-5xx-42");
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        RequestContext.clear();
    }

    @Test
    @DisplayName("a server error raised on purpose is logged with the request id the caller is shown")
    void serverErrorCarriesTheRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/goals");
        request.setRequestURI("/api/goals");

        ResponseEntity<ProblemResponse> response =
                handler.handleApi(new ApiException(ErrorCode.INTERNAL_ERROR, "database is down"), request);

        assertThat(response.getBody().requestId()).isEqualTo("ref-5xx-42");
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("requestId=ref-5xx-42")
                        .contains("POST /api/goals"));
    }

    @Test
    @DisplayName("an unexpected failure keeps logging the request id")
    void unexpectedFailureCarriesTheRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs");
        request.setRequestURI("/api/runs");

        handler.handleUnexpected(new IllegalStateException("boom"), request);

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event ->
                        assertThat(event.getFormattedMessage()).contains("requestId=ref-5xx-42"));
    }

    @Test
    @DisplayName("a client error is not logged as an error")
    void clientErrorIsNotAnError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs/x");
        request.setRequestURI("/api/runs/x");

        handler.handleApi(new ApiException(ErrorCode.NOT_FOUND), request);

        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }
}
