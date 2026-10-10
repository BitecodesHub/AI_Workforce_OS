// @find: provider registry, model providers, models list, OpenRouter, Groq, Bedrock, workspace providers, model unavailable, credential invalid, model fallback, adding a provider is a row, enabled models
// @what: Serves the model router its providers and models from the database, per workspace, and records unavailable models and invalid credentials.
// @flow: Called by ModelRouter and ProviderController
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.WorkspaceModelAvailability;
import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.orchestrator.repository.WorkspaceModelAvailabilities;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;

/**
 * Serves the router its providers and models from the database.
 *
 * <p>This is where "adding a provider is a row" becomes true. The router asks for a descriptor,
 * gets one built from a table, and never knows that OpenRouter and Groq are the same adapter with
 * different base URLs.
 *
 * <p>Every answer is one workspace's view. The catalogue rows are shared by every workspace, so
 * a workspace's own state is kept beside them and merged in here:
 *
 * <ul>
 *   <li><b>Enabled</b> is "the platform offers it" AND the workspace's own choice, which is the
 *       provider's workspace default until the workspace makes one. A workspace can switch on or
 *       off anything the platform offers; it can never switch on what the platform has withdrawn,
 *       and it never changes what another workspace sees.
 *   <li><b>Credential status</b> is the workspace's own, defaulting to {@code unknown}: keys are
 *       stored per workspace, so one workspace's refused key says nothing about another's.
 *   <li><b>Model availability</b> is always "available". Notes that set a model aside after a
 *       failure are no longer written or read; see {@link #markModelUnavailable}.
 *   <li><b>Visibility</b>: a provider another workspace added for itself, and its models, are
 *       invisible here, not merely unusable.
 * </ul>
 *
 */
@Service
public class JpaProviderRegistry implements ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(JpaProviderRegistry.class);

    private final Providers providers;
    private final Models models;
    private final WorkspaceProviderSettings settings;
    private final WorkspaceModelAvailabilities availability;

    public JpaProviderRegistry(
            Providers providers,
            Models models,
            WorkspaceProviderSettings settings,
            WorkspaceModelAvailabilities availability) {
        this.providers = providers;
        this.models = models;
        this.settings = settings;
        this.availability = availability;
    }

    /** One provider as one workspace sees it: the catalogue row with that workspace's state merged in. */
    public record WorkspaceProvider(
            LlmProviderEntity entity, boolean enabled, String credentialStatus, Instant credentialCheckedAt) {

        /**
         * False when the platform has the provider off, so no workspace can turn it on. A provider a
         * workspace added for itself is always offered to it: its own enabled column is that
         * workspace's on/off switch, not the platform's.
         */
        public boolean platformEnabled() {
            return !entity.isPlatformWide() || entity.isEnabled();
        }
    }

    /** One model as one workspace sees it: the later of the platform's and the workspace's notes. */
    public record WorkspaceModel(LlmModelEntity entity, Instant unavailableUntil, String unavailableReason) {}

    // ---- Workspace views, for the console and the router alike ----------------------------

    // @find: list workspace providers
    @Transactional(readOnly = true)
    public List<WorkspaceProvider> workspaceProviders(UUID orgId) {
        Map<String, WorkspaceProviderSetting> own = orgId == null
                ? Map.of()
                : settings.findByOrgId(orgId).stream()
                        .collect(Collectors.toMap(WorkspaceProviderSetting::getProviderId, Function.identity()));
        return providers.findVisibleTo(orgId).stream()
                .map(entity -> merge(entity, own.get(entity.getId())))
                .toList();
    }

    // @find: get workspace provider
    @Transactional(readOnly = true)
    public Optional<WorkspaceProvider> workspaceProvider(UUID orgId, String providerId) {
        return providers
                .findById(providerId)
                // A provider scoped to another workspace must be invisible, not merely unusable.
                .filter(entity -> entity.isVisibleTo(orgId))
                .map(entity -> merge(entity, ownSetting(orgId, providerId)));
    }

    // @find: list workspace models
    /** Every enabled model of a provider this workspace can see, with its availability merged in. */
    @Transactional(readOnly = true)
    public List<WorkspaceModel> workspaceModels(UUID orgId) {
        Set<String> visible = providers.findVisibleTo(orgId).stream()
                .map(LlmProviderEntity::getId)
                .collect(Collectors.toSet());
        Map<String, WorkspaceModelAvailability> own = orgId == null
                ? Map.of()
                : availability.findByOrgId(orgId).stream()
                        .collect(Collectors.toMap(
                                row -> row.getProviderId() + "/" + row.getModelId(), Function.identity()));
        // The curated catalogue, plus any model a provider's own list added that this workspace's
        // routing names, so a chosen model keeps its name and limits everywhere it is shown.
        List<LlmModelEntity> rows = new ArrayList<>(models.findAllEnabled());
        if (orgId != null) {
            List<LlmModelEntity> chosen = models.findDiscoveredInPolicies(orgId);
            if (chosen != null) {
                rows.addAll(chosen);
            }
        }
        return rows.stream()
                .filter(model -> visible.contains(model.getProviderId()))
                .map(model -> merge(model, own.get(model.getProviderId() + "/" + model.getModelId())))
                .toList();
    }

    /**
     * A workspace's effective switch.
     *
     * <p>Off whenever the row itself is off: on a platform-wide row that means the platform has
     * withdrawn the provider, on a workspace's own row that the workspace has it off. Otherwise the
     * workspace's own choice, when it has made one, and the provider's workspace default when it
     * has not.
     */
    static boolean effectiveEnabled(LlmProviderEntity entity, WorkspaceProviderSetting own) {
        if (!entity.isEnabled()) {
            return false;
        }
        if (own != null && own.getEnabled() != null) {
            return own.getEnabled();
        }
        // A workspace's own row carries its switch in enabled itself, so it has no separate default.
        return !entity.isPlatformWide() || entity.isWorkspaceDefaultEnabled();
    }

    // ---- ProviderRegistry ------------------------------------------------------------------

    // @find: providers for router
    @Override
    @Transactional(readOnly = true)
    public List<ProviderDescriptor> providers(String orgId) {
        return workspaceProviders(parse(orgId)).stream().map(this::toDescriptor).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderDescriptor> provider(String orgId, String providerId) {
        return workspaceProvider(parse(orgId), providerId).map(this::toDescriptor);
    }

    // @find: look up model spec
    @Override
    @Transactional(readOnly = true)
    public Optional<ModelSpec> model(String orgId, String providerId, String modelId) {
        UUID org = parse(orgId);
        if (providers.findById(providerId).filter(entity -> entity.isVisibleTo(org)).isEmpty()) {
            // Another workspace's provider: its models are as invisible as it is.
            return Optional.empty();
        }
        return models.findById(new LlmModelEntity.Key(providerId, modelId))
                .map(model -> merge(
                        model,
                        org == null
                                ? null
                                : availability
                                        .findById(new WorkspaceModelAvailability.Key(org, providerId, modelId))
                                        .orElse(null)))
                .map(this::toSpec);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelSpec> models(String orgId) {
        return workspaceModels(parse(orgId)).stream().map(this::toSpec).toList();
    }

    // @find: mark model unavailable, fallback to next model
    /**
     * Does nothing any more, on purpose. A model used to be set aside for minutes or hours after
     * a 402, a 404 or an exhausted quota, and runs then failed with "no model available" long
     * after somebody had topped the account up or enabled the model. Every call now asks every
     * model again; the router keeps at most a few seconds' in-memory cool-off for overload.
     */
    @Override
    public void markModelUnavailable(
            String orgId,
            String providerId,
            String modelId,
            ProviderFailure cause,
            Duration duration,
            String reason) {
        log.debug("Not setting {}/{} aside for workspace {} ({}): models are always tried", providerId, modelId, orgId, cause);
    }

    // @find: forget unavailable note, reset model status
    /**
     * Clears any note left from before models were always tried, for one model or a whole
     * provider, in this workspace. Called by "Test now".
     */
    @Transactional
    public void forgetNotes(UUID orgId, String providerId, String modelId) {
        availability.forget(orgId, providerId, modelId);
    }

    // @find: mark credential invalid, bad API key
    /** Records the refusal against the workspace whose key it was; no other workspace sees it. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCredentialInvalid(String orgId, String providerId, String reason) {
        UUID org = parse(orgId);
        if (org == null) {
            log.error("Credential for provider {} was rejected outside any workspace: {}", providerId, reason);
            return;
        }
        providers.findById(providerId).filter(entity -> entity.isVisibleTo(org)).ifPresent(provider -> {
            settings.markCredentialRejected(org, providerId, Instant.now());
            log.error("Credential for provider {} was rejected for workspace {}: {}", providerId, orgId, reason);
        });
    }

    // ---- Merging ---------------------------------------------------------------------------

    private WorkspaceProviderSetting ownSetting(UUID orgId, String providerId) {
        return orgId == null
                ? null
                : settings.findById(new WorkspaceProviderSetting.Key(orgId, providerId))
                        .orElse(null);
    }

    private static WorkspaceProvider merge(LlmProviderEntity entity, WorkspaceProviderSetting own) {
        String status = own == null || own.getCredentialStatus() == null
                ? WorkspaceProviderSetting.UNKNOWN
                : own.getCredentialStatus();
        return new WorkspaceProvider(
                entity,
                effectiveEnabled(entity, own),
                status,
                own == null ? null : own.getCredentialCheckedAt());
    }

    /** Notes are no longer honoured: a model is always offered, whatever an old note said. */
    private static WorkspaceModel merge(LlmModelEntity model, WorkspaceModelAvailability own) {
        return new WorkspaceModel(model, null, null);
    }

    private ProviderDescriptor toDescriptor(WorkspaceProvider provider) {
        LlmProviderEntity entity = provider.entity();
        return new ProviderDescriptor(
                entity.getId(),
                entity.getDisplayName(),
                ProviderDescriptor.Kind.valueOf(entity.getKind()),
                entity.getBaseUrl(),
                entity.getCredentialRef(),
                provider.enabled(),
                entity.getDefaultHeaders(),
                entity.getRegions(),
                entity.getRequestsPerMinute(),
                entity.getMaxConcurrent(),
                entity.getPriority());
    }

    private ModelSpec toSpec(WorkspaceModel model) {
        LlmModelEntity entity = model.entity();
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
                model.unavailableUntil() == null ? null : model.unavailableUntil().toEpochMilli());
    }

    private static UUID parse(String orgId) {
        return orgId == null ? null : UUID.fromString(orgId);
    }
}
