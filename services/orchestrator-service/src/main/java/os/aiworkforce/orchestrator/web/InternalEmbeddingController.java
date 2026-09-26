package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Embeddings, for the knowledge base.
 *
 * <p>The orchestrator owns model routing, so it owns embeddings too. The alternative - letting
 * the knowledge service reach providers directly - would mean two services holding provider
 * configuration, two credential paths, and two places to change when a vendor is swapped. It also
 * means the knowledge service would need the orchestrator's tables, which is exactly the coupling
 * the service boundaries exist to prevent.
 *
 * <p>Internal only, and refused to a person's token: this endpoint spends a workspace's money.
 */
@RestController
@RequestMapping("/internal/embeddings")
@Tag(name = "Internal")
public class InternalEmbeddingController {

    /** Kept modest: most providers reject a larger batch, and a rejected batch wastes the lot. */
    private static final int MAX_BATCH = 128;

    private final List<ChatProvider> providers;
    private final ProviderRegistry registry;
    private final CredentialResolver credentials;

    public InternalEmbeddingController(
            List<ChatProvider> providers, ProviderRegistry registry, CredentialResolver credentials) {
        this.providers = providers;
        this.registry = registry;
        this.credentials = credentials;
    }

    public record EmbedRequest(
            @NotBlank String providerId,
            @NotBlank String modelId,
            @NotEmpty @Size(max = MAX_BATCH) List<String> texts) {}

    /**
     * @param vectors one per input, in the same order
     * @param dimension the width, so the caller can refuse a collection built at another size
     */
    public record EmbedResponse(List<float[]> vectors, int dimension) {}

    @PostMapping
    @Operation(summary = "Internal: embed text through the workspace's configured provider")
    public EmbedResponse embed(
            @Valid @RequestBody EmbedRequest request, @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }

        String orgId = workspaceId.toString();
        ProviderDescriptor provider = registry.provider(orgId, request.providerId())
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED)
                        .with("provider", request.providerId()));
        ModelSpec model = registry.model(orgId, request.providerId(), request.modelId())
                .orElseThrow(() -> new ApiException(ErrorCode.MODEL_NOT_FOUND)
                        .with("model", request.modelId()));

        ChatProvider adapter = providers.stream()
                .filter(candidate -> candidate.kind() == provider.kind())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED));

        String credential = provider.requiresCredential()
                ? credentials.resolve(orgId, provider.credentialRef()).orElse(null)
                : null;
        if (provider.requiresCredential() && credential == null) {
            throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED)
                    .with("provider", request.providerId())
                    .with("hint", "Store a credential for this provider before ingesting.");
        }

        List<float[]> vectors = adapter.embed(provider, model, request.texts(), credential)
                .timeout(Duration.ofSeconds(60))
                .block();

        if (vectors == null || vectors.size() != request.texts().size()) {
            // A provider returning a different number of vectors than texts would misalign every
            // citation after it, which is far worse than failing here.
            throw new ApiException(
                    ErrorCode.UPSTREAM_ERROR,
                    "The embedding provider returned an unexpected number of vectors.");
        }
        return new EmbedResponse(vectors, vectors.isEmpty() ? 0 : vectors.get(0).length);
    }
}
