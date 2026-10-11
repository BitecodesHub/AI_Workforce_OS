// @find: http audit sender, post audit event, POST /internal/audit-events, analytics service client
// @what: Sends audit events over HTTP to analytics-service's internal audit endpoint.
// @flow: Calls InternalAuditController.append; used by AuditOutboxRelay
package os.aiworkforce.platform.web.audit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;

/** Posts an event to analytics-service's internal audit endpoint. */
public class HttpAuditSender implements AuditSender {

    /** Target type used to read an event's detail text back into a JSON object. */
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final WebClient client;
    private final AuditTokenSource tokens;
    private final ObjectMapper json;
    private final Duration timeout;

    /**
     * Creates a sender.
     *
     * @param client a client whose base URL is analytics-service
     * @param tokens supplies the bearer token for each request
     * @param json reads the stored detail text
     * @param timeout the longest one delivery may take
     */
    public HttpAuditSender(
            WebClient client, AuditTokenSource tokens, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.tokens = tokens;
        this.json = json;
        this.timeout = timeout;
    }

    /**
     * Posts the event to {@code POST /internal/audit-events}.
     *
     * <p>Builds the request body from the event, adds a bearer token from the {@link AuditTokenSource}
     * and blocks the calling thread until analytics-service answers or the timeout passes. Called by
     * {@code AuditOutboxRelay} on its scheduler thread, never on a request thread.
     *
     * <p>Failure: a non-success status, a timeout or a connection error is thrown to the caller, which
     * records the failed attempt and retries later with the same event id.
     */
    @Override
    public void send(AuditOutbox.Event event) {
        Map<String, Object> body = new LinkedHashMap<>();
        // The id is the idempotency key: it was fixed when the event was recorded, so every attempt
        // carries the same one and analytics-service appends the event once.
        body.put("eventId", event.id());
        body.put("orgId", event.orgId());
        body.put("actorId", event.actorId());
        body.put("actorKind", event.actorKind());
        body.put("onBehalfOf", event.onBehalfOf());
        body.put("action", event.action());
        body.put("resourceType", event.resourceType());
        body.put("resourceId", event.resourceId());
        body.put("outcome", event.outcome());
        body.put("detail", detailOf(event));
        body.put("requestId", event.requestId());
        body.put("occurredAt", event.occurredAt());

        client.post()
                .uri("/internal/audit-events")
                .header("Authorization", "Bearer " + tokens.analyticsToken())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .block();
    }

    /**
     * Turns the stored detail text into a JSON object for the request body.
     *
     * <p>A missing detail becomes an empty object. Text that cannot be parsed becomes a marker object
     * rather than an exception, so a damaged row is still delivered.
     */
    private Map<String, Object> detailOf(AuditOutbox.Event event) {
        try {
            return event.detailJson() == null ? Map.of() : json.readValue(event.detailJson(), JSON_OBJECT);
        } catch (JsonProcessingException e) {
            // Written by AuditClient as JSON, so this is a damaged row; deliver the fact that it
            // happened rather than hold the event for ever.
            return Map.of("detailUnreadable", true);
        }
    }
}
