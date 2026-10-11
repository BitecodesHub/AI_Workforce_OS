// @find: token refresh, OAuth refresh, connector token, reconnect required, integrations service, refresh access token, expired token, mark reconnect
// @what: Asks the integrations service for a fresh OAuth access token or flags a connection as needing reconnection.
// @flow: Used by live connector adapters during tool calls; calls integrations-service
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import os.aiworkforce.mcp.spi.TokenRefresher;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Lets an OAuth connector ask the integrations service for a new access token when the provider
 * rejects the one it was given, and tell it when a connection needs to be connected again.
 *
 * <p>The refresh token never leaves the integrations service. Minting the service token blocks,
 * and this runs on the HTTP client's own thread, so it is moved to an elastic thread first; the
 * request context does not follow it there, so the call is made as the platform itself for the
 * workspace named, which is all the internal endpoints ask for.
 */
@Component
public class IntegrationsTokenRefresher implements TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsTokenRefresher.class);
    // Longer than other internal calls because a refresh makes the integrations service call the provider.
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient client;
    private final InternalTokenProvider tokens;

    /**
     * Builds a client for the integrations service.
     *
     * @param builder Spring's shared WebClient builder
     * @param properties platform configuration: integrations service URL
     * @param tokens mints the service token for the workspace named in each call
     */
    public IntegrationsTokenRefresher(
            WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().integrations()).build();
        this.tokens = tokens;
    }

    // @find: refresh OAuth token, rejected credential
    /**
     * Asks the integrations service to renew the access token the provider rejected.
     *
     * <p>Posts the rejected token to the connection's refresh endpoint so the service can tell
     * whether another caller already renewed it. Side effect: the integrations service may call
     * the provider and store a new token. The result is empty, not an error, when the answer is
     * anything but connected with a value, so the caller treats it as "no new token". Transport
     * failures and timeouts (20 seconds) are logged and passed on as an error signal. Called by
     * live connector adapters mid tool call.
     *
     * @param orgId the workspace id as text
     * @param server the connector name
     * @param rejectedCredential the token the provider refused; null is sent as empty
     * @return the new access token, or empty when none could be obtained
     */
    @Override
    public Mono<String> refresh(String orgId, String server, String rejectedCredential) {
        return authorised(orgId)
                .flatMap(token -> client.post()
                        .uri("/internal/connections/{server}/refresh", server)
                        .header("X-Workspace-Id", orgId)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(Map.of("rejected", rejectedCredential == null ? "" : rejectedCredential))
                        .retrieve()
                        .bodyToMono(Answer.class)
                        .timeout(TIMEOUT))
                .filter(answer -> "connected".equals(answer.state()) && answer.value() != null)
                .map(Answer::value)
                .doOnError(error -> log.warn(
                        "Could not renew the {} sign-in in workspace {}: {}", server, orgId, error.getClass().getSimpleName()));
    }

    // @find: mark connection reconnect required
    /**
     * Tells the integrations service that this connection can no longer be renewed, so it shows as
     * needing a reconnect and later lookups answer reconnect_required.
     *
     * <p>Side effect: the connection's state is changed in the integrations service. Failures are
     * logged and passed on as an error signal; the 20 second timeout applies.
     *
     * @param orgId the workspace id as text
     * @param server the connector name
     * @param reason why the connection is unusable, in words for the administrator; null is sent as empty
     * @return completes when the service has recorded it
     */
    @Override
    public Mono<Void> markReconnectRequired(String orgId, String server, String reason) {
        return authorised(orgId)
                .flatMap(token -> client.post()
                        .uri("/internal/connections/{server}/reconnect-required", server)
                        .header("X-Workspace-Id", orgId)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(Map.of("reason", reason == null ? "" : reason))
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(TIMEOUT)
                        .then())
                .doOnError(error -> log.warn(
                        "Could not mark {} as needing a reconnect in workspace {}: {}",
                        server,
                        orgId,
                        error.getClass().getSimpleName()));
    }

    // Mints the service token on a boundedElastic thread, because minting may block and the caller is on the HTTP client's thread. Errors (including a malformed workspace id) arrive as an error signal.
    private Mono<String> authorised(String orgId) {
        return Mono.fromCallable(() -> tokens.forService("integrations", UUID.fromString(orgId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // The integrations service's reply: state is "connected" when value holds a fresh token; message explains other states.
    private record Answer(String value, String state, String message) {}
}
