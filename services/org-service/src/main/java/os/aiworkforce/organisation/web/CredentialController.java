package os.aiworkforce.organisation.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.organisation.service.CredentialService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Storing and listing the workspace's secrets.
 *
 * <p>Note the asymmetry: a credential can be written and listed by an administrator, and read
 * back by nobody. The only endpoint that returns a plaintext value is under {@code /internal},
 * reachable with a service token rather than a person's, and it is never routed through the
 * gateway.
 */
@RestController
@Tag(name = "Credentials")
@RequestMapping
public class CredentialController {

    private final CredentialService credentials;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private os.aiworkforce.platform.web.audit.AuditClient audit;

    public CredentialController(CredentialService credentials) {
        this.credentials = credentials;
    }

    public record StoreCredentialRequest(
            @NotBlank @Size(max = 120) String kind, @NotBlank @Size(max = 8_000) String value, Instant expiresAt) {}

    public record InternalCredential(String value) {}

    @GetMapping("/api/credentials")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Which credentials are stored, without revealing any of them")
    public List<CredentialService.CredentialView> list() {
        return credentials.list(orgId());
    }

    @PutMapping("/api/credentials/{ref}")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(summary = "Store or replace a credential")
    public CredentialService.CredentialView store(
            @PathVariable String ref, @Valid @RequestBody StoreCredentialRequest request) {
        CredentialService.CredentialView stored =
                credentials.store(orgId(), ref, request.kind(), request.value(), request.expiresAt());
        // The reference and kind only: the value is never written anywhere but the vault.
        record("credential.store", ref, java.util.Map.of("kind", String.valueOf(request.kind())));
        return stored;
    }

    @DeleteMapping("/api/credentials/{ref}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(summary = "Remove a credential")
    public void delete(@PathVariable String ref) {
        credentials.delete(orgId(), ref);
        record("credential.delete", ref, java.util.Map.of());
    }

    private void record(String action, String ref, java.util.Map<String, Object> detail) {
        if (audit != null) {
            audit.record(action, "credential", ref, "succeeded", detail);
        }
    }

    /**
     * The decrypted value, for a sibling service.
     *
     * <p>Not exposed through the gateway and not reachable with a person's token or a machine
     * key. The caller must present a service token, which the resource-server configuration
     * verifies, and must name the workspace explicitly. The token must also have been minted for
     * that same workspace: a header alone is only the caller's say-so, so without the binding one
     * service token could be replayed with a different header to sweep every workspace's secrets.
     */
    @GetMapping("/internal/credentials/{ref}")
    @Operation(summary = "Internal: resolve a credential for a sibling service")
    public InternalCredential reveal(@PathVariable String ref, @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            // A person's token must never reach this endpoint, whatever permissions it carries.
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        if (actor.orgId() == null) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "The service token names no workspace.");
        }
        if (!actor.orgId().equalsIgnoreCase(workspaceId.toString())) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH);
        }
        InternalCredential revealed = new InternalCredential(credentials.reveal(workspaceId, ref).orElse(null));
        if (audit != null) {
            // Who asked (the calling service) and for whom it acted; never the value.
            java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("service", actor.id());
            if (actor.onBehalfOf() != null) {
                detail.put("onBehalfOf", actor.onBehalfOf());
            }
            detail.put("found", revealed.value() != null);
            audit.record(workspaceId, actor, "credential.reveal", "credential", ref, "succeeded", detail);
        }
        return revealed;
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
