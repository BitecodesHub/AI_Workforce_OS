package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;

/**
 * Serves the router its providers and models from the database.
 *
 * <p>This is where "adding a provider is a row" becomes true. The router asks for a descriptor,
 * gets one built from a table, and never knows that OpenRouter and Groq are the same adapter with
 * different base URLs.
 */
@Service
public class JpaProviderRegistry implements ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(JpaProviderRegistry.class);

    private final Providers providers;
    private final Models models;

    public JpaProviderRegistry(Providers providers, Models models) {
        this.providers = providers;
        this.models = models;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderDescriptor> providers(String orgId) {
        return providers.findVisibleTo(parse(orgId)).stream()
                .map(this::toDescriptor)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderDescriptor> provider(String orgId, String providerId) {
        return providers
                .findById(providerId)
                // A provider scoped to another workspace must be invisible, not merely unusable.
                .filter(entity -> entity.getOrgId() == null || entity.getOrgId().equals(parse(orgId)))
                .map(this::toDescriptor);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelSpec> model(String orgId, String providerId, String modelId) {
        return models.findById(new LlmModelEntity.Key(providerId, modelId)).map(this::toSpec);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelSpec> models(String orgId) {
        return models.findAllEnabled().stream().map(this::toSpec).toList();
    }

    /**
     * Records that a provider says a model does not exist.
     *
     * <p>Runs in its own transaction. The caller is on a failure path whose transaction will very
     * likely roll back, and this note is exactly the thing that must survive that rollback -
     * otherwise every request repeats the same doomed round trip.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markModelUnavailable(
            String orgId, String providerId, String modelId, Duration duration, String reason) {
        models.findById(new LlmModelEntity.Key(providerId, modelId)).ifPresent(model -> {
            model.markUnavailable(duration, reason);
            models.save(model);
            log.warn("Model {}/{} marked unavailable for {}: {}", providerId, modelId, duration, reason);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCredentialInvalid(String orgId, String providerId, String reason) {
        providers.findById(providerId).ifPresent(provider -> {
            provider.markCredentialRejected();
            providers.save(provider);
            log.error("Credential for provider {} was rejected: {}", providerId, reason);
        });
    }

    private ProviderDescriptor toDescriptor(LlmProviderEntity entity) {
        return new ProviderDescriptor(
                entity.getId(),
                entity.getDisplayName(),
                ProviderDescriptor.Kind.valueOf(entity.getKind()),
                entity.getBaseUrl(),
                entity.getCredentialRef(),
                entity.isEnabled(),
                entity.getDefaultHeaders(),
                entity.getRegions(),
                entity.getRequestsPerMinute(),
                entity.getMaxConcurrent(),
                entity.getPriority());
    }

    private ModelSpec toSpec(LlmModelEntity entity) {
        return new ModelSpec(
                entity.getProviderId(),
                entity.getModelId(),
                entity.getDisplayName(),
                entity.getContextWindow(),
                entity.getMaxOutputTokens(),
                entity.isSupportsTools(),
                entity.isSupportsJsonMode(),
                entity.isSupportsStreaming(),
                entity.isSupportsVision(),
                entity.getInputCostPerMillion(),
                entity.getCachedCostPerMillion(),
                entity.getOutputCostPerMillion(),
                entity.isEnabled(),
                entity.getUnavailableUntil() == null
                        ? null
                        : entity.getUnavailableUntil().toEpochMilli());
    }

    private static UUID parse(String orgId) {
        return orgId == null ? null : UUID.fromString(orgId);
    }
}
