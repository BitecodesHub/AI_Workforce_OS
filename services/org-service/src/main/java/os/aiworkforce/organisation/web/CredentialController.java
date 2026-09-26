package os.aiworkforce.organisation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import os.aiworkforce.organisation.repository.Credentials;
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

    public CredentialController(CredentialService credentials) {
        this.credentials = credentials;
    }

    public record StoreCredentialRequest(
            @NotBlank @Size(max = 120) String kind,
            @NotBlank @Size(max = 8_000) String value,
            Instant expiresAt) {}

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
        return credentials.store(orgId(), ref, request.kind(), request.value(), request.expiresAt());
    }

    @DeleteMapping("/api/credentials/{ref}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(summary = "Remove a credential")
    public void delete(@PathVariable String ref) {
        credentials.delete(orgId(), ref);
    }

    /**
     * The decrypted value, for a sibling service.
     *
     * <p>Not exposed through the gateway and not reachable with a person's token. The caller must
     * present a service token, which the resource-server configuration verifies, and must name
     * the workspace explicitly - so a token for one service cannot be used to sweep every
     * workspace's secrets.
     */
    @GetMapping("/internal/credentials/{ref}")
    @Operation(summary = "Internal: resolve a credential for a sibling service")
    public InternalCredential reveal(
            @PathVariable String ref, @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            // A person's token must never reach this endpoint, whatever permissions it carries.
            throw new ApiException(
                    ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        return new InternalCredential(
                credentials.reveal(workspaceId, ref).orElse(null));
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
