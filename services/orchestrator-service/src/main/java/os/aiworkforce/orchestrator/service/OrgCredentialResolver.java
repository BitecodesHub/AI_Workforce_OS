package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.platform.config.PlatformProperties;

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
 */
@Service
public class OrgCredentialResolver implements CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(OrgCredentialResolver.class);

    private final WebClient client;
    private final InternalTokenProvider tokens;

    public OrgCredentialResolver(
            WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().organisation()).build();
        this.tokens = tokens;
    }

    @Override
    public Optional<String> resolve(String orgId, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = client.get()
                    .uri("/internal/credentials/{ref}", credentialRef)
                    .header("X-Workspace-Id", orgId)
                    .header("Authorization", "Bearer " + tokens.forService("organisation"))
                    .retrieve()
                    .bodyToMono(CredentialResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .map(CredentialResponse::value)
                    .block();
            return Optional.ofNullable(value).filter(v -> !v.isBlank());
        } catch (RuntimeException e) {
            // An empty result is a skip, not a failure: the router moves to the next candidate and
            // records "no credential" rather than failing the whole run over one provider.
            log.debug("Credential {} could not be resolved for org {}: {}", credentialRef, orgId, e.getMessage());
            return Optional.empty();
        }
    }

    private record CredentialResponse(String value) {}
}
