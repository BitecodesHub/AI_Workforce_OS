package os.aiworkforce.organisation.web;

import java.time.Duration;
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
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creating a workspace.
 *
 * <p>Deliberately not behind {@code @RequiresPermission}: the person calling this holds no
 * permissions yet, because a workspace - and the membership that grants any permission at all -
 * does not exist until this endpoint creates it. The only requirement is a valid, authenticated
 * token, which a freshly registered account already has (signed in with no organisation selected,
 * because it belongs to none).
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

    public record WorkspaceView(UUID id, String name, String slug, String timezone, String status) {}

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
                        : request.timezone());
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

    @GetMapping("/{workspaceId}")
    @Operation(summary = "One workspace")
    public WorkspaceView get(@PathVariable UUID workspaceId) {
        // Read without a permission check beyond authentication: the workspace picker on sign-in
        // needs a name to show before the caller's token carries any workspace-scoped permission.
        return toView(
                organisations.findById(workspaceId).orElseThrow(() -> ApiException.notFound("workspace", workspaceId)));
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

    private static WorkspaceView toView(Organisation org) {
        return new WorkspaceView(org.getId(), org.getName(), org.getSlug(), org.getTimezone(), org.getStatus());
    }
}

// Week 1 update by Param2725

// Week 2 update by Param2725

// Week 3 update by Param2725

// Week 4 update by Param2725
