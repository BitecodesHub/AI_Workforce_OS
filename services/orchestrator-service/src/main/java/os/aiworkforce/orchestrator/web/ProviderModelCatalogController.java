// @find: model catalogue, available models for provider, embedding models, GET /api/providers/{providerId}/models, GET /api/providers/embedding-models, model picker
// @what: Lists the models a provider offers, including embedding models.
// @flow: Uses ModelCatalogService
package os.aiworkforce.orchestrator.web;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.catalog.ModelCatalogService;
import os.aiworkforce.orchestrator.catalog.ModelCatalogService.CatalogueView;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The models a provider offers, for the model picker in a routing policy, and the embedding models
 * a workspace can use. Called by the Models settings page and the knowledge base settings.
 *
 * <p>Separate from {@code GET /api/providers/models}, which stays the short, curated list the
 * rest of the console reads: this one is every tool-capable model the provider itself lists,
 * fetched with the workspace's key and cached for a few hours. Both endpoints delegate to
 * {@link ModelCatalogService}.
 */
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers")
public class ProviderModelCatalogController {

    private final ModelCatalogService catalogue;

    /** Receives the catalogue service that talks to the providers and caches their lists. */
    public ProviderModelCatalogController(ModelCatalogService catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Lists every embedding model the workspace can use.
     *
     * <p>Route {@code GET /api/providers/embedding-models}; requires PROVIDER_READ. The service asks
     * each switched-on provider that has a stored key (outbound calls, cached for six hours unless
     * {@code refresh} is true).
     */
    @GetMapping("/embedding-models")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(
            summary = "Every embedding model this workspace can use, free ones first",
            description = "Read from the own model list of each switched-on provider with a stored key, cached"
                    + " for six hours; refresh=true asks again. Used to choose how the knowledge base searches"
                    + " by meaning.")
    // @find: embedding models, GET /api/providers/embedding-models
    public ModelCatalogService.EmbeddingCatalogue embeddingModels(
            @RequestParam(defaultValue = "false") boolean refresh) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        return catalogue.embeddingModels(orgId, refresh);
    }

    /**
     * Lists the models one provider offers.
     *
     * <p>Route {@code GET /api/providers/{providerId}/models}; requires PROVIDER_READ. The service
     * reads the provider's own list with the workspace's key (cached; {@code refresh} asks again,
     * {@code all} includes models that cannot call tools). When the provider cannot be asked, the saved
     * models are returned with a message instead of an error.
     */
    @GetMapping("/{providerId}/models")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(
            summary = "Every model a provider offers that can call tools, free ones first",
            description = "Read from the provider's own model list with this workspace's key and cached for six"
                    + " hours; refresh=true asks again. When the provider cannot be asked, the saved models are"
                    + " returned with source=saved and a message saying so. all=true includes models that cannot"
                    + " call tools, for diagnosis.")
    // @find: provider model catalogue, GET /api/providers/{providerId}/models
    public CatalogueView models(
            @PathVariable String providerId,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "false") boolean all) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        return catalogue.list(orgId, providerId, refresh, all);
    }
}
