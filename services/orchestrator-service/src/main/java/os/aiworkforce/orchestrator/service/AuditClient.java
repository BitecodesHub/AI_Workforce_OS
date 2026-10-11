// @find: audit client, audit log, record audit event, audit trail, analytics-service audit, internal HTTP audit call, who did what, AuditClient.record
// @what: Sends audit entries for agent and approval actions to the analytics service over a direct internal HTTP call.
// @flow: Called by services and controllers after a change; calls analytics-service via InternalTokenProvider
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;

/**
 * Emits an entry to the audit projection in analytics-service.
 *
 * <p>Sent over a direct internal HTTP call, not through Kafka: this platform's event topics exist
 * in the schema, but {@code SPRING_KAFKA_LISTENER_AUTO_STARTUP=false} in this development
 * environment means nothing consumes an event published that way locally. A synchronous call to
 * {@code /internal/audit-events} is what actually lands a row today.
 *
 * <p>Every call is fire-and-forget-safe: a failure to reach analytics-service - it being down,
 * slow, or refusing the request - is logged and swallowed, never rethrown. The action this call
 * is auditing has already happened (a run finished, an approval was decided); a missing audit
 * entry is a gap to notice and fill later, but it must never undo or fail the action itself. This
 * mirrors {@link OrgCredentialResolver#resolve}, which treats a resolution failure the same way.
 */
@Service
public class AuditClient {

    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);

    private final WebClient client;
    private final InternalTokenProvider tokens;

    /**
     * Builds a client for the analytics service.
     *
     * @param builder Spring's shared WebClient builder
     * @param properties platform configuration: analytics service URL
     * @param tokens mints the service token for each call
     */
    public AuditClient(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().analytics()).build();
        this.tokens = tokens;
    }

    // The wire shape of one audit entry as analytics-service's /internal/audit-events expects it.
    private record AuditEventRequest(
            UUID orgId,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail) {}

    // @find: record audit event, write audit entry
    /**
     * Records one entry. Never throws: see the class-level note on why a delivery failure here
     * must not propagate to the caller.
     *
     * <p>Posts the entry with a 5 second timeout and a service token minted from the current
     * request context, while the entry itself names the {@code actor} passed in. A null detail is
     * sent as an empty object. Side effect: one row is written by analytics-service. On any failure
     * (including a null actor) a warning is logged and the method returns normally.
     *
     * @param orgId the workspace the action happened in
     * @param actor who did it, including the person behind a non-human actor
     * @param action what happened, for example {@code "run.completed"}
     * @param resourceType the kind of thing acted on
     * @param resourceId the id of the thing acted on
     * @param outcome how it ended
     * @param detail extra facts for the entry; may be null
     */
    public void record(
            UUID orgId,
            Actor actor,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail) {
        try {
            client.post()
                    .uri("/internal/audit-events")
                    .header("Authorization", "Bearer " + tokens.forService("analytics"))
                    .bodyValue(new AuditEventRequest(
                            orgId,
                            actor.id(),
                            actor.kind().name(),
                            actor.onBehalfOf(),
                            action,
                            resourceType,
                            resourceId,
                            outcome,
                            detail == null ? Map.of() : detail))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record audit event '{}' for {} {}: {}",
                    action,
                    resourceType,
                    resourceId,
                    e.getMessage());
        }
    }
}
