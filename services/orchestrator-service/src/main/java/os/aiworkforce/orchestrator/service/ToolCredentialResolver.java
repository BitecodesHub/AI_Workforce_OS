package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Fetches the credential for one tool server from the integrations service.
 *
 * <p>Separate from the model-provider resolver because the two have different owners and
 * different lifetimes: a provider key is set once by an administrator, while a tool credential is
 * an OAuth token that refreshes on its own schedule and can be revoked by the person who granted
 * it. Returning empty is normal - a sandbox server needs no credential at all.
 */
@Service
public class ToolCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolCredentialResolver.class);

    private final WebClient client;
    private final InternalTokenProvider tokens;

    public ToolCredentialResolver(
            WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().integrations()).build();
        this.tokens = tokens;
    }

    public Optional<String> resolve(UUID orgId, String server) {
        try {
            String value = client.get()
                    .uri("/internal/connections/{server}/credential", server)
                    .header("X-Workspace-Id", orgId.toString())
                    .header("Authorization", "Bearer " + tokens.forService("integrations"))
                    .retrieve()
                    .bodyToMono(CredentialResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .map(CredentialResponse::value)
                    .block();
            return Optional.ofNullable(value).filter(v -> !v.isBlank());
        } catch (RuntimeException e) {
            log.debug("No credential for server {} in workspace {}: {}", server, orgId, e.getMessage());
            return Optional.empty();
        }
    }

    private record CredentialResponse(String value) {}
}
