// @find: bedrock region, find region, AWS Bedrock, which region has model, POST /api/providers/{providerId}/bedrock-regions, region finder
// @what: Finds which AWS regions can serve a Bedrock model for the given credentials.
// @flow: Called by the provider setup dialog; uses BedrockRegionFinder
package os.aiworkforce.orchestrator.web;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.llm.bedrock.BedrockCredentials;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.orchestrator.catalog.BedrockRegionFinder;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * "Find my region" for Amazon Bedrock, behind the region field of "Connect your AI".
 *
 * <p>The pasted credential is checked field by field, then every Bedrock region is asked whether
 * it accepts it (BedrockRegionFinder). Nothing is stored, and neither the credential nor anything
 * AWS said is logged or returned: only region ids, counts and plain sentences. It is rate-limited
 * per workspace, like the key check, because a form that tries credentials on request is also a
 * way to guess at them.
 */
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers")
public class BedrockRegionController {

    /** Region searches one workspace may make in {@link #WINDOW}. */
    static final int LIMIT = 3;

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final JpaProviderRegistry registry;
    private final BedrockRegionFinder finder;
    private final AuditClient audit;
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();

    /** Where the rate limit reads the time; replaced by a test. */
    LongSupplier clock = System::currentTimeMillis;

    public BedrockRegionController(JpaProviderRegistry registry, BedrockRegionFinder finder, AuditClient audit) {
        this.registry = registry;
        this.finder = finder;
        this.audit = audit;
    }

    public record RegionSearchRequest(@NotBlank @Size(max = 8_000) String value) {}

    @PostMapping("/{providerId}/bedrock-regions")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(
            summary = "Find which AWS regions accept a Bedrock credential, before it is stored",
            description = "Nothing is stored and the credential is never returned or logged.")
    // @find: find Bedrock regions, POST /api/providers/{providerId}/bedrock-regions
    public BedrockRegionFinder.Finding find(
            @PathVariable String providerId, @Valid @RequestBody RegionSearchRequest request) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        WorkspaceProvider provider = registry.workspaceProvider(orgId, providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        String name = provider.entity().getDisplayName();
        if (ProviderDescriptor.Kind.valueOf(provider.entity().getKind()) != ProviderDescriptor.Kind.BEDROCK) {
            throw ApiException.validation("providerId", "Only Amazon Bedrock has regions to find.");
        }
        if (!provider.platformEnabled()) {
            throw new ApiException(
                    ErrorCode.PROVIDER_NOT_CONFIGURED, name + " is not available yet. Contact support to have it offered.");
        }
        BedrockCredentials credentials;
        try {
            credentials = BedrockCredentials.parse(request.value());
        } catch (BedrockCredentials.Invalid invalid) {
            throw ApiException.validation(invalid.field(), invalid.getMessage());
        }
        takeSlot(orgId);

        BedrockRegionFinder.Finding finding = finder.find(credentials);
        // What was found, never the credential and never anything AWS said.
        audit.record(
                orgId,
                RequestContext.actor().orElse(Actor.SYSTEM),
                "provider.find_region",
                "provider",
                providerId,
                finding.best() == null ? "failed" : "succeeded",
                finding.best() == null
                        ? Map.<String, Object>of("provider", name)
                        : Map.<String, Object>of("provider", name, "region", finding.best()));
        return finding;
    }

    private void takeSlot(UUID orgId) {
        long now = clock.getAsLong();
        long window = WINDOW.toMillis();
        Deque<Long> calls = recent.computeIfAbsent(orgId, id -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && now - calls.peekFirst() >= window) {
                calls.pollFirst();
            }
            if (calls.size() >= LIMIT) {
                long waitMillis = window - (now - calls.peekFirst());
                throw new ApiException(
                                ErrorCode.RATE_LIMITED,
                                "Regions can be searched " + LIMIT + " times a minute in a workspace. Wait a moment, then try again.")
                        .retryAfter(Duration.ofSeconds(Math.max(1, (waitMillis + 999) / 1000)));
            }
            calls.addLast(now);
        }
    }
}
