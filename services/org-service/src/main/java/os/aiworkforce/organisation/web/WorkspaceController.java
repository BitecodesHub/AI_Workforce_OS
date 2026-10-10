// @find: workspaces, create workspace, update workspace settings, rename workspace, timezone, slug, POST /api/workspaces, GET /api/workspaces/{id}, PATCH /api/workspaces/{id}/settings, Settings page, new workspace, become owner
// @what: REST endpoints to create a workspace (and make the caller its owner), read one, and update its name and timezone.
// @flow: Calls identity-service /internal/memberships/bootstrap-owner after saving; audits updates.
package os.aiworkforce.organisation.web;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.organisation.domain.Organisation;
import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.organisation.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creating a workspace, reading it, and changing its name or timezone.
 *
 * <p>Creating is deliberately not behind {@code @RequiresPermission}: the person calling it holds
 * no permissions yet, because a workspace - and the membership that grants any permission at all -
 * does not exist until this endpoint creates it. The only requirement is a valid, authenticated
 * token, which a freshly registered account already has (signed in with no organisation selected,
 * because it belongs to none). Changing settings is the opposite case: it needs
 * {@code workspace:update} in the very workspace the path names, so a token for one workspace can
 * never rename or re-time another.
 *
 * <p>Creating the organisation and granting its creator the owner role are two writes in two
 * services. Rather than pretend that is one transaction, the failure mode is chosen deliberately:
 * if granting ownership fails after the organisation was created, the organisation is deleted
 * rather than left ownerless and unreachable. An organisation nobody can administer is worse than
 * one that never existed.
 */
@RestController
@RequestMapping("/api/workspaces")
@Tag(name = "Workspaces")
public class WorkspaceController {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.platform.web.audit.AuditClient audit;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    /** Names collapse to this shape for the address; kept short so the result stays readable. */
    private static final Pattern NON_SLUG_CHARACTERS = Pattern.compile("[^a-z0-9]+");

    private final Organisations organisations;
    private final WebClient identityClient;
    private final InternalTokenProvider tokens;

    public WorkspaceController(
            Organisations organisations,
            WebClient.Builder builder,
            PlatformProperties properties,
            InternalTokenProvider tokens) {
        this.organisations = organisations;
        this.identityClient = builder.baseUrl(properties.services().identity()).build();
        this.tokens = tokens;
    }

    public record CreateWorkspaceRequest(@NotBlank @Size(max = 120) String name, @Size(max = 60) String timezone) {}

    public record UpdateSettingsRequest(@Size(max = 120) String name, @Size(max = 60) String timezone) {}

    public record WorkspaceView(UUID id, String name, String slug, String timezone, String status) {}

    // @find: create workspace, new workspace, POST /api/workspaces, bootstrap owner
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a workspace and become its owner")
    @Transactional
    public WorkspaceView create(@Valid @RequestBody CreateWorkspaceRequest request) {
        var actor = RequestContext.requireActor();
        String slug = uniqueSlug(request.name());

        Organisation org = new Organisation();
        org.setId(UuidV7.generate());
        org.setName(request.name().strip());
        org.setSlug(slug);
        org.setTimezone(
                request.timezone() == null || request.timezone().isBlank()
                        ? "Australia/Melbourne"
                        : recognisedZone(request.timezone()));
        org.setOwnerId(UUID.fromString(actor.id()));
        organisations.save(org);

        try {
            identityClient
                    .post()
                    .uri("/internal/memberships/bootstrap-owner")
                    .header("Authorization", "Bearer " + tokens.forService("identity"))
                    .bodyValue(new BootstrapRequest(org.getId(), UUID.fromString(actor.id())))
                    .retrieve()
                    .bodyToMono(Object.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (RuntimeException e) {
            // An organisation nobody can administer is worse than one that never existed, so the
            // half-completed write is undone rather than left for a person to discover later as
            // "my workspace exists but I cannot open it."
            log.error("Could not grant ownership of workspace {}; rolling back its creation", org.getId(), e);
            organisations.delete(org);
            throw new ApiException(
                    ErrorCode.UPSTREAM_ERROR,
                    "The workspace could not be finished setting up. Nothing was created; try again.",
                    e);
        }

        log.info("Workspace {} ({}) created by {}", org.getId(), org.getSlug(), actor.id());
        return toView(org);
    }

    // @find: get workspace, GET /api/workspaces/{workspaceId}
    /**
     * One workspace, for a person signed in to it or for a sibling service.
     *
     * <p>No permission beyond authentication, because every member may see their own workspace's
     * name. A person's token for any other workspace gets a 404 rather than a 403, so the endpoint
     * does not confirm that somebody else's workspace exists. Sibling services read workspaces
     * through {@link InternalWorkspaceController} instead; service tokens are still let through
     * here so a caller not yet moved over keeps working.
     */
    @GetMapping("/{workspaceId}")
    @Operation(summary = "One workspace")
    public WorkspaceView get(@PathVariable UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        boolean service = actor.kind() == Actor.Kind.SYSTEM || actor.kind() == Actor.Kind.AGENT;
        if (!service && !workspaceId.equals(orgOf(actor))) {
            throw ApiException.notFound("workspace", workspaceId);
        }
        return toView(
                organisations.findById(workspaceId).orElseThrow(() -> ApiException.notFound("workspace", workspaceId)));
    }

    // @find: update workspace settings, rename workspace, change timezone, PATCH /api/workspaces/{workspaceId}/settings
    /**
     * Renames the workspace or moves it to another timezone.
     *
     * <p>The orchestrator caches each workspace's timezone for ten minutes, so a new zone reaches
     * schedule suggestions and "today" within that window. Schedules already created keep the
     * zone they were created in.
     */
    @PatchMapping("/{workspaceId}/settings")
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Update workspace settings")
    @Transactional
    public WorkspaceView updateSettings(
            @PathVariable UUID workspaceId, @Valid @RequestBody UpdateSettingsRequest request) {
        requireSameOrg(workspaceId);
        var actor = RequestContext.requireActor();
        Organisation org = organisations.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound("workspace", workspaceId));

        // Both values are checked before either is applied, so a bad timezone never leaves a
        // half-made rename on the entity.
        String name = request.name() == null ? org.getName() : request.name().strip();
        if (name.isEmpty()) {
            // @Size alone accepts "" and "   ", which would save a workspace with no name.
            throw ApiException.validation("name", "A workspace needs a name.");
        }
        String timezone = request.timezone() == null ? org.getTimezone() : recognisedZone(request.timezone());

        String previousName = org.getName();
        String previousTimezone = org.getTimezone();
        org.setName(name);
        org.setTimezone(timezone);
        organisations.save(org);

        if (audit != null) {
            java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("previousName", previousName);
            detail.put("name", org.getName());
            detail.put("previousTimezone", previousTimezone);
            detail.put("timezone", org.getTimezone());
            audit.record("workspace.update", "workspace", org.getId().toString(), "succeeded", detail);
        }
        log.info(
                "Workspace {} settings changed by {}: name '{}' -> '{}', timezone {} -> {}",
                org.getId(),
                actor.id(),
                previousName,
                org.getName(),
                previousTimezone,
                org.getTimezone());
        return toView(org);
    }

    /** The path names the workspace explicitly so a stale or forged id cannot reach another. */
    private static void requireSameOrg(UUID workspaceId) {
        UUID tokenOrgId = UUID.fromString(RequestContext.requireOrgId());
        if (!tokenOrgId.equals(workspaceId)) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH);
        }
    }

    private static UUID orgOf(Actor actor) {
        try {
            return actor.orgId() == null ? null : UUID.fromString(actor.orgId());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * The zone as Java names it, or a validation error.
     *
     * <p>Checked on the way in because every reader trusts it: an unrecognised value used to be
     * saved as typed and then silently replaced by a fallback zone wherever it was read, so
     * schedules fired at the wrong hour with nothing to say why.
     */
    /**
     * Only a named region ("Europe/London", "UTC") is kept. ZoneId also accepts fixed offsets such
     * as "+05:00" or "UTC+10", which the console's Intl.DateTimeFormat rejects, so schedule times
     * would quietly show in the browser's own zone instead.
     */
    private static String recognisedZone(String timezone) {
        String id = timezone.strip();
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw ApiException.validation("timezone", "That is not a recognised time zone.");
        }
        try {
            return ZoneId.of(id).getId();
        } catch (DateTimeException notAZone) {
            throw ApiException.validation("timezone", "That is not a recognised time zone.");
        }
    }

    private String uniqueSlug(String name) {
        String base = NON_SLUG_CHARACTERS
                .matcher(name.strip().toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "workspace";
        }
        String candidate = base;
        int suffix = 2;
        while (organisations.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private record BootstrapRequest(UUID orgId, UUID userId) {}

    static WorkspaceView toView(Organisation org) {
        return new WorkspaceView(org.getId(), org.getName(), org.getSlug(), org.getTimezone(), org.getStatus());
    }
}
