// @find: audit client, record audit event, connect disconnect audit, send audit to analytics, internal token, audit log for connectors
// @what: Sends audit entries (connect, test, disconnect and similar) to the analytics service.
// @flow: Called by ConnectorService, OAuthService and IntegrationController
package os.aiworkforce.integrations.service;

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
 * Records connector changes in the audit log kept by analytics-service.
 *
 * <p>The same direct internal call the orchestrator uses: a short-lived service token from the
 * identity service, then one POST to {@code /internal/audit-events}. Connecting and disconnecting
 * are rare, so the token is not cached.
 *
 * <p>Never throws. The change being audited has already been saved; a missing audit entry is a
 * gap to notice in the logs, not a reason to fail the administrator's request. The entry never
 * carries the token itself.
 */
@Service
public class AuditClient {

    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Client for the identity service, used to obtain the short-lived service token. */
    private final WebClient identity;
    /** Client for the analytics service, which stores the audit entries. */
    private final WebClient analytics;
    /** The shared secret that authorises the token request to the identity service. Never logged. */
    private final String internalSecret;

    /**
     * Builds the two HTTP clients from the platform service addresses and reads the internal secret.
     *
     * @param builder the shared client builder, cloned so this client does not alter it
     * @param properties platform settings holding the service addresses and the internal secret
     */
    public AuditClient(WebClient.Builder builder, PlatformProperties properties) {
        this.identity = builder.clone().baseUrl(properties.services().identity()).build();
        this.analytics = builder.clone().baseUrl(properties.services().analytics()).build();
        this.internalSecret = properties.security().internalServiceSecret();
    }

    /** Body of the token request sent to the identity service. */
    private record TokenRequest(String audience, String actorId, String actorKind, String orgId, String onBehalfOf) {}

    /** The identity service's answer, carrying the token to present to analytics. */
    private record TokenResponse(String token) {}

    /** Body of the audit entry sent to the analytics service; the outcome is always recorded as succeeded. */
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

    // @find: record audit event, write audit log entry
    /**
     * Records one audit entry for a change made in a workspace.
     *
     * <p>Flow: logs the action, requests an analytics-audience service token for the actor, then posts the
     * entry to {@code /internal/audit-events}. Each call is blocking and limited to five seconds.
     * Called by {@code ConnectorService}, {@code OAuthService} and the controllers. Any runtime failure
     * is caught and logged as a warning, so this method never throws.
     *
     * @param orgId the workspace the change happened in
     * @param actor who made the change
     * @param action the action name, for example {@code integration.connect}
     * @param resourceType the kind of resource, for example {@code integration}
     * @param resourceId the identifier of the resource, here the connector key
     * @param detail extra non-secret details; null is treated as empty
     */
    public void record(
            UUID orgId, Actor actor, String action, String resourceType, String resourceId, Map<String, Object> detail) {
        log.info("Audit {} on {} {} in workspace {} by {}", action, resourceType, resourceId, orgId, actor.id());
        try {
            String token = identity.post()
                    .uri("/internal/tokens")
                    .header("X-Internal-Secret", internalSecret)
                    .bodyValue(new TokenRequest(
                            "analytics",
                            actor.id(),
                            actor.kind().name(),
                            orgId.toString(),
                            actor.humanId() == null ? "" : actor.humanId()))
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .timeout(TIMEOUT)
                    .map(TokenResponse::token)
                    .block();
            analytics.post()
                    .uri("/internal/audit-events")
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(new AuditEventRequest(
                            orgId,
                            actor.id(),
                            actor.kind().name(),
                            actor.onBehalfOf(),
                            action,
                            resourceType,
                            resourceId,
                            "succeeded",
                            detail == null ? Map.of() : detail))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            log.warn("Could not record audit event '{}' for {} {}: {}", action, resourceType, resourceId, e.getMessage());
        }
    }
}
