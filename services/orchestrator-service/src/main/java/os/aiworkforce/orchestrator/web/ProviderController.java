package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Model providers, their models and their health.
 *
 * <p>This is what makes fallover visible. A run that answered on the third candidate is
 * inexplicable unless somebody can see that the first is paused and the second has no credential,
 * and that is exactly what this endpoint reports.
 */
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers")
public class ProviderController {

    private final Providers providers;
    private final Models models;
    private final ModelRouter router;

    public ProviderController(
            Providers providers,
            Models models,
            ModelRouter router) {
        this.providers = providers;
        this.models = models;
        this.router = router;
    }

    public record ProviderView(
            String id,
            String displayName,
            String kind,
            boolean enabled,
            /** What the console passes to {@code PUT /api/credentials/{ref}} to configure this provider. Null for the sandbox, which needs no credential. */
            String credentialRef,
            String credentialStatus,
            Instant credentialCheckedAt,
            String circuitState,
            List<String> regions,
            int modelCount) {}

    public record ModelView(
            String providerId,
            String modelId,
            String displayName,
            int contextWindow,
            int maxOutputTokens,
            boolean supportsTools,
            boolean supportsJsonMode,
            boolean supportsStreaming,
            BigDecimal inputCostPerMillion,
            BigDecimal outputCostPerMillion,
            boolean enabled,
            Instant unavailableUntil,
            String unavailableReason) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Configured providers and their current health")
    public List<ProviderView> list() {
        UUID orgId = orgId();
        Map<String, String> health = router.providerHealth(orgId.toString());
        return providers.findVisibleTo(orgId).stream()
                .map(provider -> new ProviderView(
                        provider.getId(),
                        provider.getDisplayName(),
                        provider.getKind(),
                        provider.isEnabled(),
                        provider.getCredentialRef(),
                        // The credential itself is never returned, only whether it works.
                        provider.getCredentialStatus(),
                        provider.getCredentialCheckedAt(),
                        health.getOrDefault(provider.getId(), "UNKNOWN"),
                        provider.getRegions(),
                        models.findByProviderIdAndEnabledTrue(provider.getId()).size()))
                .toList();
    }

    @GetMapping("/models")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Every model available to this workspace")
    public List<ModelView> models() {
        return models.findAllEnabled().stream().map(ProviderController::toView).toList();
    }

    @PostMapping("/{providerId}/enable")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Turn a provider on")
    public ProviderView enable(@PathVariable String providerId) {
        return setEnabled(providerId, true);
    }

    @PostMapping("/{providerId}/disable")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Turn a provider off, so the router stops considering it")
    public ProviderView disable(@PathVariable String providerId) {
        return setEnabled(providerId, false);
    }

    private ProviderView setEnabled(String providerId, boolean enabled) {
        LlmProviderEntity provider = providers.findById(providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        provider.setEnabled(enabled);
        providers.save(provider);
        return new ProviderView(
                provider.getId(), provider.getDisplayName(), provider.getKind(), provider.isEnabled(),
                provider.getCredentialRef(), provider.getCredentialStatus(), provider.getCredentialCheckedAt(),
                "UNKNOWN", provider.getRegions(), models.findByProviderIdAndEnabledTrue(providerId).size());
    }

    private static ModelView toView(LlmModelEntity model) {
        return new ModelView(
                model.getProviderId(), model.getModelId(), model.getDisplayName(),
                model.getContextWindow(), model.getMaxOutputTokens(), model.isSupportsTools(),
                model.isSupportsJsonMode(), model.isSupportsStreaming(),
                model.getInputCostPerMillion(), model.getOutputCostPerMillion(),
                model.isEnabled(), model.getUnavailableUntil(), model.getUnavailableReason());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
