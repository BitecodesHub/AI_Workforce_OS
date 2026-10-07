package os.aiworkforce.identity.service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;

/**
 * Tells the orchestrator when somebody leaves a workspace, so the work they set up stops running.
 *
 * <p>A schedule runs as the person who created it. Once that person is removed, every later run
 * would still start in their name - an agent acting for somebody who no longer works there. The
 * orchestrator pauses those schedules when told; it is told here.
 *
 * <p>Best effort, after the removal has committed. The removal itself is the decision that
 * matters, and it must not wait on, or fail because of, a service that only tidies up after it.
 * A failed call is logged at WARN for an operator to follow up.
 */
@Component
public class OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final TokenService tokens;

    public OrchestratorClient(WebClient.Builder builder, PlatformProperties properties, TokenService tokens) {
        this.client = builder.clone().baseUrl(properties.services().orchestrator()).build();
        this.tokens = tokens;
    }

    /**
     * Pauses the departed member's schedules once the surrounding transaction commits, or at once
     * when there is no transaction. A rolled-back removal tells the orchestrator nothing.
     *
     * @param removedBy the person who removed them, carried in the service token for the audit trail
     */
    public void memberLeftAfterCommit(UUID orgId, UUID userId, String removedBy) {
        Runnable notify = () -> memberLeft(orgId, userId, removedBy)
                .subscribe(
                        paused -> log.info(
                                "Paused {} schedule(s) owned by user {} in workspace {}", paused, userId, orgId),
                        failure -> log.warn(
                                "Could not pause the schedules of user {}, removed from workspace {}: {}",
                                userId,
                                orgId,
                                failure.toString()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
        } else {
            notify.run();
        }
    }

    /**
     * The call itself: how many schedules were paused, or an error.
     *
     * <p>Package-private so a test can wait for the answer rather than for a log line.
     */
    Mono<Integer> memberLeft(UUID orgId, UUID userId, String removedBy) {
        return Mono.fromCallable(() -> serviceToken(orgId, removedBy))
                .flatMap(token -> client.post()
                        .uri("/internal/schedules/owner-removed")
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(Map.of("orgId", orgId, "userId", userId))
                        .retrieve()
                        .bodyToMono(JSON_OBJECT))
                .timeout(TIMEOUT)
                .map(body -> body.get("paused") instanceof Number number ? number.intValue() : 0)
                .defaultIfEmpty(0);
    }

    /**
     * A service token naming the person who made the change. Minted locally, since identity is the
     * service that signs tokens; it carries no permissions, only who is calling and for whom.
     */
    private String serviceToken(UUID orgId, String onBehalfOf) {
        Actor service = new Actor(
                "identity",
                Actor.Kind.SYSTEM,
                orgId.toString(),
                null,
                Set.of(),
                0L,
                onBehalfOf,
                null,
                null,
                Map.of());
        return tokens.issueInternalToken("orchestrator", service).token();
    }
}
