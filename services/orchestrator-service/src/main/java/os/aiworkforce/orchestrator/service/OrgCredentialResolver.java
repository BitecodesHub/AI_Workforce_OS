package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * Fetches a provider credential from the organisation service.
 *
 * <p>The orchestrator deliberately does not hold the encryption key. Credentials are stored and
 * decrypted by the one service that owns them, and arrive here over an internal call, so a
 * disclosure in the orchestrator exposes at most the keys of runs currently in flight rather
 * than every key the platform holds.
 *
 * <p>Nothing is cached in a field. A credential is resolved immediately before the request is
 * signed and is never attached to an attempt record or a log line.
 *
 * <p>The service token is minted for the workspace being resolved, not for whatever the calling
 * context happens to name: the organisation service only reveals a credential to a token whose
 * workspace matches the one the request asks about, and a scheduled run has no workspace in its
 * context at all.
 *
 * <p>"There is no key" and "the store did not answer" are different answers, and
 * {@link #lookup} keeps them apart. Only a 404 or an empty value means nothing is stored. A
 * network error, a 5xx, a timeout or a failure to get a token from the identity service means
 * the answer is unknown, so the call is tried once more after a short jittered wait, behind a
 * circuit breaker for the organisation service so a service that is down is not hammered by
 * every candidate of every run, and then reported as unavailable. Reporting a blip as a missing
 * key sent administrators to re-enter keys that were never wrong.
 */
@Service
public class OrgCredentialResolver implements CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(OrgCredentialResolver.class);

    /** The dependency name the breaker and the back-off are configured under. */
    static final String DEPENDENCY = "service.organisation";

    /** One try and one retry: a longer wait would hold a run, and the router tells its caller to try again. */
    private static final int ATTEMPTS = 2;

    private final WebClient client;
    private final InternalTokenProvider tokens;
    private final ResiliencePresets resilience;

    public OrgCredentialResolver(
            WebClient.Builder builder,
            PlatformProperties properties,
            InternalTokenProvider tokens,
            ResiliencePresets resilience) {
        this.client = builder.baseUrl(properties.services().organisation()).build();
        this.tokens = tokens;
        this.resilience = resilience;
    }

    @Override
    public Optional<String> resolve(String orgId, String credentialRef) {
        return lookup(orgId, credentialRef) instanceof Found found ? Optional.of(found.value()) : Optional.empty();
    }

    @Override
    public Lookup lookup(String orgId, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return NotFound.INSTANCE;
        }
        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(orgId);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Nothing can be stored for a workspace that is not one.
            return NotFound.INSTANCE;
        }

        CircuitBreaker breaker = resilience.circuitBreaker(DEPENDENCY);
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return CircuitBreaker.decorateSupplier(breaker, () -> fetch(workspaceId, credentialRef))
                        .get();
            } catch (CallNotPermittedException e) {
                // The breaker is open: the store has been failing, so this is not tried again here.
                log.warn(
                        "Credential {} was not fetched for workspace {}: calls to the credential store are paused",
                        credentialRef,
                        orgId);
                return new Unavailable("calls to the credential store are paused after repeated failures");
            } catch (RuntimeException e) {
                failure = e;
                if (attempt == ATTEMPTS || !isTransient(e)) {
                    break;
                }
                try {
                    Thread.sleep(resilience.backoff(DEPENDENCY, attempt).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String reason = describe(failure);
        log.warn(
                "Credential {} could not be fetched for workspace {}; treating the store as unavailable: {}",
                credentialRef,
                orgId,
                reason);
        return new Unavailable(reason);
    }

    /** One call to the store. Returns {@link NotFound} for a 404, and throws for anything unexpected. */
    private Lookup fetch(UUID workspaceId, String credentialRef) {
        try {
            String value = client.get()
                    .uri("/internal/credentials/{ref}", credentialRef)
                    .header("X-Workspace-Id", workspaceId.toString())
                    .header("Authorization", "Bearer " + tokens.forService("organisation", workspaceId))
                    .retrieve()
                    .bodyToMono(CredentialResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    // mapNotNull, not map: the store answers {} for a reference with nothing
                    // stored, and map() turns that null into a NullPointerException, which the
                    // breaker counted as an outage. Enough of those (the voice status polls the
                    // unset ElevenLabs key) opened it, and every model call then failed too.
                    .mapNotNull(CredentialResponse::value)
                    .block();
            return value == null || value.isBlank() ? NotFound.INSTANCE : new Found(value);
        } catch (WebClientResponseException.NotFound e) {
            // The store answered, and has nothing under this reference. Returned rather than
            // thrown, so the breaker does not count a healthy answer as a failure.
            return NotFound.INSTANCE;
        }
    }

    /**
     * Whether a second try could do better: the store or the identity service being briefly
     * down, slow or busy. A refusal (a 4xx other than "slow down") says the request itself is
     * wrong, and asking again would be refused again.
     */
    private static boolean isTransient(RuntimeException failure) {
        if (failure instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            return status >= 500 || status == 408 || status == 425 || status == 429;
        }
        if (failure instanceof ApiException api) {
            return api.retryable();
        }
        // A connection that was refused or reset, a timeout, a token that could not be fetched.
        return true;
    }

    /** What went wrong, for the log: the kind of failure and any status, never a body or a secret. */
    private static String describe(RuntimeException failure) {
        if (failure == null) {
            return "no answer";
        }
        if (failure instanceof WebClientResponseException response) {
            return "HTTP " + response.getStatusCode().value() + " from the credential store";
        }
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getClass().getSimpleName();
    }

    private record CredentialResponse(String value) {}
}
