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

    private final WebClient identity;
    private final WebClient analytics;
    private final String internalSecret;

    public AuditClient(WebClient.Builder builder, PlatformProperties properties) {
        this.identity = builder.clone().baseUrl(properties.services().identity()).build();
        this.analytics = builder.clone().baseUrl(properties.services().analytics()).build();
        this.internalSecret = properties.security().internalServiceSecret();
    }

    private record TokenRequest(String audience, String actorId, String actorKind, String orgId, String onBehalfOf) {}

    private record TokenResponse(String token) {}

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
